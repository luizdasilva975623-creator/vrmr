package com.luiz.vrmr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : ComponentActivity(), SensorEventListener {
    lateinit var view: GLSurfaceView
    lateinit var rend: Renderer
    @Volatile var rot = floatArrayOf(1f,0f,0f,0f, 0f,0f,1f,0f, 0f,-1f,0f,0f, 0f,0f,0f,1f); @Volatile var hasRot = false
    private val grav = FloatArray(3); private val geo = FloatArray(3); private var lastSeen = 0L; private var useFb = false; private var haveA = false; private var haveM = false
    fun msg(t: String) = runOnUiThread { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show() }
    @Volatile var hx = 0f; @Volatile var hy = 0f; @Volatile var hp = 1f; @Volatile var hv = false
    private val raw = FloatArray(16)
    private var hl: HandLandmarker? = null
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var perm = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rend = Renderer(this)
        view = GLSurfaceView(this).apply { setEGLContextClientVersion(2); setRenderer(rend) }
        setContentView(view)
        @Suppress("DEPRECATION")
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        try {
            hl = HandLandmarker.createFromOptions(this, HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.VIDEO).setNumHands(1).setMinHandDetectionConfidence(0.35f).setMinHandPresenceConfidence(0.35f).setMinTrackingConfidence(0.35f).build())
        } catch (e: Throwable) { msg("Mãos desativadas: " + e.message) }
        msg("Vol+: recentralizar | Vol-: alternar 2D/VR")
        val req = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> perm = ok; if (ok) startCamera() }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) perm = true
        else req.launch(Manifest.permission.CAMERA)
    }

    fun startCamera() {
        if (!perm || rend.st == null) return
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
          try {
            val p = f.get()
            val prev = Preview.Builder().setTargetResolution(Size(1280, 720)).build()
            prev.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { rq ->
                val s = rend.st!!
                s.setDefaultBufferSize(rq.resolution.width, rq.resolution.height)
                val sf = Surface(s)
                rq.provideSurface(sf, ContextCompat.getMainExecutor(this)) { sf.release() }
            }
            val an = ImageAnalysis.Builder().setTargetResolution(Size(640, 360))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            an.setAnalyzer(exec) { analyze(it) }
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, an)
          } catch (e: Throwable) { msg("Erro câmera: " + e.message) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            var bmp = img.toBitmap()
            val r = img.imageInfo.rotationDegrees
            if (r != 0) bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                android.graphics.Matrix().apply { postRotate(r.toFloat()) }, false)
            val res = hl?.detectForVideo(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis())
            val lm = res?.landmarks()?.firstOrNull()
            if (lm == null) { if (SystemClock.uptimeMillis() - lastSeen > 200) hv = false } else {
                lastSeen = SystemClock.uptimeMillis()
                val a = lm[4]; val i = lm[8]; val w = lm[0]; val m = lm[9]
                val size = hypot(w.x() - m.x(), w.y() - m.y()).coerceAtLeast(1e-3f)
                hp = hypot(a.x() - i.x(), a.y() - i.y()) / size
                hx = if (hv) hx * .5f + i.x() * .5f else i.x(); hy = if (hv) hy * .5f + i.y() * .5f else i.y(); hv = true
            }
        } catch (_: Exception) { } finally { img.close() }
    }

    private fun ortho(m: FloatArray) {
        var n = kotlin.math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]); m[0] /= n; m[1] /= n; m[2] /= n
        val d = m[4] * m[0] + m[5] * m[1] + m[6] * m[2]; m[4] -= d * m[0]; m[5] -= d * m[1]; m[6] -= d * m[2]
        n = kotlin.math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]); m[4] /= n; m[5] /= n; m[6] /= n
        m[8] = m[1] * m[6] - m[2] * m[5]; m[9] = m[2] * m[4] - m[0] * m[6]; m[10] = m[0] * m[5] - m[1] * m[4]
    }
    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        if (k == KeyEvent.KEYCODE_VOLUME_UP) { rend.recenter = true; return true }
        if (k == KeyEvent.KEYCODE_VOLUME_DOWN) { rend.vr = !rend.vr; return true }
        return super.onKeyDown(k, e)
    }
    override fun onResume() {
        super.onResume(); view.onResume()
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val g = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        useFb = g == null
        if (g != null) sm.registerListener(this, g, 10000)
        else {
            val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER); val m = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (a != null && m != null) { sm.registerListener(this, a, 10000); sm.registerListener(this, m, 10000) }
            else msg("Sem sensores de movimento: painéis ficam fixos")
        }
    }
    override fun onPause() {
        super.onPause(); view.onPause()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { for (i in 0..2) grav[i] = if (haveA) grav[i] * .8f + e.values[i] * .2f else e.values[i]; haveA = true }
            Sensor.TYPE_MAGNETIC_FIELD -> { for (i in 0..2) geo[i] = if (haveM) geo[i] * .8f + e.values[i] * .2f else e.values[i]; haveM = true }
            else -> SensorManager.getRotationMatrixFromVector(raw, e.values)
        }
        if (e.sensor.type == Sensor.TYPE_ACCELEROMETER || e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            if (!(haveA && haveM) || !SensorManager.getRotationMatrix(raw, null, grav, geo)) return
        }
        val out = FloatArray(16)
        @Suppress("DEPRECATION")
        if (windowManager.defaultDisplay.rotation == Surface.ROTATION_270)
            SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
        else SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
        if (hasRot) { val o = rot; val a = if (useFb) .12f else .6f; for (i in 0..14) out[i] = o[i] * (1 - a) + out[i] * a; ortho(out) }
        rot = out; hasRot = true
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
