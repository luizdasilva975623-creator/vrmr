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
    @Volatile var rot = FloatArray(16); @Volatile var hasRot = false
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
        hl = HandLandmarker.createFromOptions(this, HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.VIDEO).setNumHands(1).build())
        val req = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> perm = ok; if (ok) startCamera() }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) perm = true
        else req.launch(Manifest.permission.CAMERA)
    }

    fun startCamera() {
        if (!perm || rend.st == null) return
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            val prev = Preview.Builder().setTargetResolution(Size(1280, 720)).build()
            prev.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { rq ->
                val s = rend.st!!
                s.setDefaultBufferSize(rq.resolution.width, rq.resolution.height)
                val sf = Surface(s)
                rq.provideSurface(sf, ContextCompat.getMainExecutor(this)) { sf.release() }
            }
            val an = ImageAnalysis.Builder().setTargetResolution(Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            an.setAnalyzer(exec) { analyze(it) }
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, an)
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
            if (lm == null) { hv = false } else {
                val a = lm[4]; val i = lm[8]; val w = lm[0]; val m = lm[9]
                val size = hypot(w.x() - m.x(), w.y() - m.y()).coerceAtLeast(1e-3f)
                hp = hypot(a.x() - i.x(), a.y() - i.y()) / size
                hx = i.x(); hy = i.y(); hv = true
            }
        } catch (_: Exception) { } finally { img.close() }
    }

    override fun onResume() {
        super.onResume(); view.onResume()
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_GAME)
    }
    override fun onPause() {
        super.onPause(); view.onPause()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(raw, e.values)
        val out = FloatArray(16)
        @Suppress("DEPRECATION")
        if (windowManager.defaultDisplay.rotation == Surface.ROTATION_270)
            SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
        else SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
        rot = out; hasRot = true
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
