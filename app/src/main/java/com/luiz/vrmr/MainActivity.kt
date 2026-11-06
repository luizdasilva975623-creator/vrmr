package com.luiz.vrmr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    lateinit var view: GLSurfaceView
    lateinit var rend: Renderer
    @Volatile var rot = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f)
    @Volatile var hasRot = false
    @Volatile var hx = 0f; @Volatile var hy = 0f; @Volatile var hp = 1f; @Volatile var hv = false
    @Volatile var lmk = FloatArray(42)
    @Volatile var yawV = 0f
    @Volatile var gain = 1f
    private var night = false; private var meanS = 110f; private var cam: androidx.camera.core.Camera? = null
    var visual = false
    private var yawS = 0f
    private val ga = floatArrayOf(0f, 9.8f, 0f); private var sgn = 1
    private val raw = FloatArray(16)
    private var hl: HandLandmarker? = null
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var perm = false
    private var lastSeen = 0L
    private var prevG: IntArray? = null

    fun msg(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rend = Renderer(this)
        view = GLSurfaceView(this).apply { setEGLContextClientVersion(2); setRenderer(rend) }
        val root = FrameLayout(this); root.addView(view)
        val d = resources.displayMetrics.density
        val btn = TextView(this).apply {
            text = "2D/VR"; setTextColor(0xFFFFFFFF.toInt()); textSize = 12f; gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt()); setOnClickListener { rend.vr = !rend.vr }
        }
        root.addView(btn, FrameLayout.LayoutParams((64 * d).toInt(), (40 * d).toInt(), Gravity.TOP or Gravity.END).apply { setMargins(0, (8 * d).toInt(), (8 * d).toInt(), 0) })
        setContentView(root)
        @Suppress("DEPRECATION")
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        try {
            hl = HandLandmarker.createFromOptions(this, HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.VIDEO).setNumHands(1).setMinHandDetectionConfidence(0.35f)
                .setMinHandPresenceConfidence(0.35f).setMinTrackingConfidence(0.35f).build())
        } catch (e: Throwable) { msg("Mãos desativadas: " + e.message) }
        msg("Vol+: recentralizar | Vol-: 2D/VR | botão no canto: 2D/VR")
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
                cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, an)
            } catch (e: Throwable) { msg("Erro câmera: " + e.message) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            var bmp = img.toBitmap()
            val r = img.imageInfo.rotationDegrees
            if (r != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                android.graphics.Matrix().apply { postRotate(r.toFloat()) }, false)
            val sm = Bitmap.createScaledBitmap(bmp, 128, 72, true)
            val px = IntArray(128 * 72); sm.getPixels(px, 0, 128, 0, 0, 128, 72)
            var sum = 0L; for (v in px) sum += (v shr 8) and 255
            val mean = (sum.toFloat() / px.size).coerceAtLeast(8f)
            updateNight(mean)
            if (visual) trackMotion(IntArray(px.size) { minOf(255, (((px[it] shr 8) and 255) * 110f / mean).toInt()) })
            if (gain > 1.15f) bmp = brighten(bmp, gain)
            val res = hl?.detectForVideo(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis())
            val lm = res?.landmarks()?.firstOrNull()
            if (lm == null) { if (SystemClock.uptimeMillis() - lastSeen > 200) hv = false } else {
                lastSeen = SystemClock.uptimeMillis()
                val a = lm[4]; val i = lm[8]; val w = lm[0]; val m = lm[9]
                val size = hypot(w.x() - m.x(), w.y() - m.y()).coerceAtLeast(1e-3f)
                hp = hypot(a.x() - i.x(), a.y() - i.y()) / size
                val arr = FloatArray(42); for (k in 0..20) { arr[2 * k] = lm[k].x(); arr[2 * k + 1] = lm[k].y() }
                lmk = arr
                hx = if (hv) hx * .5f + i.x() * .5f else i.x(); hy = if (hv) hy * .5f + i.y() * .5f else i.y(); hv = true
            }
        } catch (_: Exception) { } finally { img.close() }
    }

    // Rastreamento visual de giro da cabeça (substitui giroscópio): block matching + mediana
    private fun updateNight(mean: Float) {
        meanS += (mean - meanS) * .1f
        gain += ((105f / meanS).coerceIn(1f, 6f) - gain) * .1f
        if (!night && meanS < 65f) { night = true; setExposure(true); msg("Modo noturno ligado") }
        else if (night && meanS > 140f) { night = false; setExposure(false); msg("Modo noturno desligado") }
    }
    private fun setExposure(on: Boolean) {
        val c = cam ?: return
        runOnUiThread { try { c.cameraControl.setExposureCompensationIndex(if (on) c.cameraInfo.exposureState.exposureCompensationRange.upper else 0) } catch (_: Throwable) { } }
    }
    private fun brighten(b: Bitmap, k: Float): Bitmap {
        val o = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint()
        p.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        android.graphics.Canvas(o).drawBitmap(b, 0f, 0f, p); return o
    }
    private fun trackMotion(g: IntArray) {
        val p = prevG; prevG = g; if (p == null) return
        val xs = ArrayList<Float>()
        val S = 5; val B = 18; val n = 2 * S + 1; val sad = IntArray(n * n)
        for (by in 0..2) for (bx in 0..5) {
            val x0 = S + bx * 20; val y0 = S + by * 20
            var best = Int.MAX_VALUE; var worst = 0; var bi = 0
            for (j in 0 until n) for (i in 0 until n) {
                val sx = i - S; val sy = j - S; var t = 0
                for (y in 0 until B step 2) {
                    val r = (y0 + y) * 128; val rp = (y0 + y - sy) * 128
                    for (x in 0 until B step 2) t += abs(g[r + x0 + x] - p[rp + x0 + x - sx])
                }
                sad[j * n + i] = t; if (t < best) { best = t; bi = j * n + i }; if (t > worst) worst = t
            }
            if (worst - best < 200) continue
            val bxI = bi % n
            var fx = (bxI - S).toFloat()
            if (bxI in 1 until n - 1) { val a = sad[bi - 1]; val c = sad[bi]; val d = sad[bi + 1]; val den = a - 2 * c + d; if (den > 0) fx += .5f * (a - d) / den }
            xs.add(fx)
        }
        if (xs.size < 4) return
        xs.sort()
        yawV += -xs[xs.size / 2] * (HFOV * Math.PI.toFloat() / 180f / 128f)
    }

    // Orientação = gravidade (acelerômetro) + yaw visual
    fun fuse() {
        yawS += (yawV - yawS) * .7f
        var ux = ga[0]; var uy = ga[1]; var uz = ga[2]
        val n = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-3f); ux /= n; uy /= n; uz /= n
        var fx = uz * ux; var fy = uz * uy; var fz = uz * uz - 1f
        var l = sqrt(fx * fx + fy * fy + fz * fz)
        if (l < .2f) { fx = -uy * ux; fy = 1f - uy * uy; fz = -uy * uz; l = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-3f) }
        fx /= l; fy /= l; fz /= l
        val rx = fy * uz - fz * uy; val ry = fz * ux - fx * uz; val rz = fx * uy - fy * ux
        val c = cos(yawS); val s = sin(yawS)
        val o = FloatArray(16)
        o[0] = c * rx + s * fx; o[4] = c * ry + s * fy; o[8] = c * rz + s * fz
        o[1] = -s * rx + c * fx; o[5] = -s * ry + c * fy; o[9] = -s * rz + c * fz
        o[2] = ux; o[6] = uy; o[10] = uz; o[15] = 1f
        rot = o
    }

    override fun onResume() {
        super.onResume(); view.onResume()
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val g = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (g != null) { visual = false; sm.registerListener(this, g, 10000) }
        else {
            val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (a != null) { visual = true; sm.registerListener(this, a, 10000); msg("Sem giroscópio: usando rastreio visual") }
            else msg("Sem sensores de movimento: painéis ficam fixos")
        }
    }
    override fun onPause() {
        super.onPause(); view.onPause()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }
    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        if (k == KeyEvent.KEYCODE_VOLUME_UP) { rend.recenter = true; return true }
        if (k == KeyEvent.KEYCODE_VOLUME_DOWN) { rend.vr = !rend.vr; return true }
        return super.onKeyDown(k, e)
    }
    override fun onSensorChanged(e: SensorEvent) {
        if (visual) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            if (abs(x) > 4f) sgn = if (x > 0) 1 else -1
            val nx = -sgn * y; val ny = sgn * x; val a = .25f
            ga[0] += (nx - ga[0]) * a; ga[1] += (ny - ga[1]) * a; ga[2] += (z - ga[2]) * a
            hasRot = true; return
        }
        SensorManager.getRotationMatrixFromVector(raw, e.values)
        val out = FloatArray(16)
        @Suppress("DEPRECATION")
        if (windowManager.defaultDisplay.rotation == Surface.ROTATION_270)
            SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
        else SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
        if (hasRot) { val o = rot; for (i in 0..14) out[i] = o[i] * .4f + out[i] * .6f; ortho(out) }
        rot = out; hasRot = true
    }
    private fun ortho(m: FloatArray) {
        var n = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]); m[0] /= n; m[1] /= n; m[2] /= n
        val d = m[4] * m[0] + m[5] * m[1] + m[6] * m[2]; m[4] -= d * m[0]; m[5] -= d * m[1]; m[6] -= d * m[2]
        n = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]); m[4] /= n; m[5] /= n; m[6] /= n
        m[8] = m[1] * m[6] - m[2] * m[5]; m[9] = m[2] * m[4] - m[0] * m[6]; m[10] = m[0] * m[5] - m[1] * m[4]
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
