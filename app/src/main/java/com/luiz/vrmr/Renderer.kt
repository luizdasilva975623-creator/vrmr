package com.luiz.vrmr

import android.graphics.*
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

const val HFOV = 68f
val TH = tan(Math.toRadians(HFOV / 2.0)).toFloat()
val TV = TH * 9f / 16f

class Panel(val title: String, val w: Float, val h: Float, bw: Int, bh: Int) {
    val bmp: Bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    var px = 0f; var py = 0f; var pz = 0f; var tex = 0; var dirty = true; var hover = -1; var pinned = false
}

class Renderer(val act: MainActivity) : GLSurfaceView.Renderer {
    var st: SurfaceTexture? = null
    @Volatile var vr = true; @Volatile var recenter = true
    private var oes = 0; private var camP = 0; private var panP = 0; private var linP = 0
    private var W = 1; private var H = 1
    private val stm = FloatArray(16); private val proj = FloatArray(16); private val V = FloatArray(16)
    private val Ve = FloatArray(16); private val T = FloatArray(16); private val vp = FloatArray(16)
    private val m = FloatArray(16); private val mvp = FloatArray(16)
    private fun fb(n: Int) = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val quad = fb(16).apply { put(floatArrayOf(-.5f, -.5f, 0f, 0f, .5f, -.5f, 1f, 0f, -.5f, .5f, 0f, 1f, .5f, .5f, 1f, 1f)) }
    private val hb = fb(600); private val jb = fb(42)
    private val bones = intArrayOf(0,1,1,2,2,3,3,4, 0,5,5,6,6,7,7,8, 5,9,9,10,10,11,11,12, 9,13,13,14,14,15,15,16, 13,17,17,18,18,19,19,20, 0,17)
    private val names = listOf("Início", "Mapas", "Jogos", "YouTube", "Config.", "Modelo 3D", "Google", "Loja")
    private val menu = Panel("Menu", 0.5f, 0.75f, 512, 768)
    private val cursor = Panel("c", 0.04f, 0.04f, 64, 64)
    private val panels = mutableListOf(menu)
    private var frames = 0; private var K = 1f; private var curAspect = 1f
    private var gaze = 0f; private var menuYaw = 0f
    private var pinchPrev = false; private var cursorPinch = false
    private var dragging: Panel? = null; private var cursorVis = false
    private val ipd = 0.064f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun sh(t: Int, s: String) = GLES20.glCreateShader(t).also { GLES20.glShaderSource(it, s); GLES20.glCompileShader(it) }
    private fun prog(v: String, f: String) = GLES20.glCreateProgram().also {
        GLES20.glAttachShader(it, sh(GLES20.GL_VERTEX_SHADER, v)); GLES20.glAttachShader(it, sh(GLES20.GL_FRAGMENT_SHADER, f))
        GLES20.glBindAttribLocation(it, 0, "aPos"); GLES20.glBindAttribLocation(it, 1, "aTex"); GLES20.glLinkProgram(it) }

    override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
        camP = prog("attribute vec2 aPos;attribute vec2 aTex;uniform mat4 uStm;varying vec2 vT;void main(){vT=(uStm*vec4(aTex,0.,1.)).xy;gl_Position=vec4(aPos*2.,0.,1.);}",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 vT;uniform samplerExternalOES uTex;uniform float uG;uniform float uL;void main(){vec3 c=min(texture2D(uTex,vT).rgb*uG,vec3(1.));gl_FragColor=vec4(mix(c,sqrt(c),uL),1.);}")
        panP = prog("attribute vec2 aPos;attribute vec2 aTex;uniform mat4 uMvp;varying vec2 vT;void main(){vT=vec2(aTex.x,1.-aTex.y);gl_Position=uMvp*vec4(aPos,0.,1.);}",
            "precision mediump float;varying vec2 vT;uniform sampler2D uTex;void main(){gl_FragColor=texture2D(uTex,vT);}")
        linP = prog("attribute vec2 aPos;void main(){gl_Position=vec4(aPos,0.,1.);gl_PointSize=18.;}",
            "precision mediump float;uniform vec4 uCol;void main(){gl_FragColor=uCol;}")
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); oes = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        st = SurfaceTexture(oes)
        menu.tex = 0; cursor.tex = 0; for (p in panels) p.tex = 0
        drawMenu(); drawCursor(false)
        act.runOnUiThread { act.startCamera() }
    }
    override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) { W = w; H = h }

    private fun drawMenu() {
        val c = Canvas(menu.bmp); val bw = menu.bmp.width.toFloat(); val bh = menu.bmp.height.toFloat()
        c.drawColor(0xEE141418.toInt()); val hdr = bh * .08f
        paint.color = Color.WHITE; paint.textSize = 30f; c.drawText("Menu", bw / 2, hdr * .7f, paint)
        val cw = bw / 2; val ch = (bh - hdr) / 4
        for (i in names.indices) {
            val col = i % 2; val row = i / 2
            paint.color = if (i == menu.hover) 0xFF3D5AFE.toInt() else 0xFF2A2A31.toInt()
            c.drawRoundRect(col * cw + 10, hdr + row * ch + 10, (col + 1) * cw - 10, hdr + (row + 1) * ch - 10, 24f, 24f, paint)
            paint.color = Color.WHITE; paint.textSize = 34f
            c.drawText(names[i], col * cw + cw / 2, hdr + row * ch + ch / 2 + 12, paint)
        }
        menu.dirty = true
    }
    private fun drawApp(p: Panel) {
        val c = Canvas(p.bmp); c.drawColor(0xEE141418.toInt()); val bw = p.bmp.width.toFloat()
        paint.color = 0xFF2A2A31.toInt(); c.drawRect(0f, 0f, bw, p.bmp.height * .08f, paint)
        paint.color = Color.WHITE; paint.textSize = 30f
        c.drawText(p.title, bw / 2, p.bmp.height * .056f, paint); c.drawText("X", bw - 40, p.bmp.height * .056f, paint)
        paint.textSize = 36f; c.drawText("Janela flutuante: " + p.title, bw / 2, p.bmp.height / 2f, paint); p.dirty = true
    }
    private fun drawCursor(pin: Boolean) {
        val c = Canvas(cursor.bmp); c.drawColor(0, PorterDuff.Mode.CLEAR)
        paint.color = if (pin) 0xFF00E676.toInt() else 0xFFFFFFFF.toInt(); c.drawCircle(32f, 32f, 20f, paint); cursor.dirty = true
    }
    private fun ensure(p: Panel) {
        if (p.tex == 0) {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); p.tex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        if (p.dirty) { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, p.bmp, 0); p.dirty = false }
    }

    private fun hit(p: Panel, x: Float, y: Float, z: Float): FloatArray? {
        val l = hypot(p.px, p.py).coerceAtLeast(1e-3f); val nx = -p.px / l; val ny = -p.py / l
        val den = x * nx + y * ny; if (den > -1e-4f) return null
        val t = (p.px * nx + p.py * ny) / den; if (t <= 0) return null
        val lx = x * t - p.px; val ly = y * t - p.py; val lz = z * t - p.pz
        val u = (lx * -ny + ly * nx) / p.w + .5f; val v = lz / (p.h * K) + .5f
        return if (u in 0f..1f && v in 0f..1f) floatArrayOf(u, v, x * t, y * t, z * t, nx, ny) else null
    }
    private fun place(p: Panel, yaw: Float, d: Float) { p.px = sin(yaw) * d; p.py = cos(yaw) * d; p.pz = 0f }

    private fun logic(R: FloatArray) {
        if (!act.hv) { cursorVis = false; pinchPrev = false; dragging = null; return }
        val dx = (act.hx * 2 - 1) * TH; val dy = (1 - act.hy * 2) * TV; val dz = -1f
        var X = R[0] * dx + R[4] * dy + R[8] * dz; var Y = R[1] * dx + R[5] * dy + R[9] * dz; var Z = R[2] * dx + R[6] * dy + R[10] * dz
        val n = sqrt(X * X + Y * Y + Z * Z); X /= n; Y /= n; Z /= n
        val pinch = if (pinchPrev) act.hp < 0.45f else act.hp < 0.30f
        var hp: Panel? = null; var h: FloatArray? = null
        for (p in panels.asReversed()) { val r = hit(p, X, Y, Z); if (r != null) { hp = p; h = r; break } }
        dragging?.let { d -> if (pinch) { val len = sqrt(d.px * d.px + d.py * d.py + d.pz * d.pz); d.px = X * len; d.py = Y * len; d.pz = Z * len } }
        if (h != null) { cursor.px = h[2] + h[5] * .01f; cursor.py = h[3] + h[6] * .01f; cursor.pz = h[4] } else { cursor.px = X * 1.4f; cursor.py = Y * 1.4f; cursor.pz = Z * 1.4f }
        cursorVis = true
        val idx = if (hp === menu && h!![1] < .92f) (((1f - h[1]) - .08f) / .92f * 4).toInt().coerceIn(0, 3) * 2 + (h[0] * 2).toInt().coerceIn(0, 1) else -1
        if (idx != menu.hover) { menu.hover = idx; drawMenu() }
        if (pinch && !pinchPrev && hp != null && dragging == null) {
            if (h!![1] > .92f) { if (h[0] > .88f && hp !== menu) panels.remove(hp) else { dragging = hp; hp.pinned = true } }
            else if (hp === menu && idx >= 0) open(names[idx])
        }
        if (!pinch) dragging = null
        pinchPrev = pinch
        if (pinch != cursorPinch) { cursorPinch = pinch; drawCursor(pinch) }
    }
    private fun open(name: String) {
        if (panels.any { it.title == name }) return
        val p = Panel(name, 0.9f, 0.6f, 768, 512); place(p, gaze + .2f + .6f * (panels.size - 1), 1.6f); drawApp(p); panels.add(p)
    }

    private fun drawPanel(p: Panel) {
        ensure(p)
        val l = hypot(p.px, p.py).coerceAtLeast(1e-3f); val nx = -p.px / l; val ny = -p.py / l
        java.util.Arrays.fill(m, 0f)
        m[0] = -ny * p.w; m[1] = nx * p.w; m[6] = p.h * K; m[8] = nx; m[9] = ny; m[12] = p.px; m[13] = p.py; m[14] = p.pz; m[15] = 1f
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0)
        GLES20.glUseProgram(panP)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(panP, "uMvp"), 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex)
        quad.position(0); GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2); GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // Esqueleto neon da mão (espaço da imagem da câmera)
    private fun drawHand() {
        if (!act.hv) return
        val l = act.lmk; val pin = cursorPinch
        val r = if (pin) 0f else .7f; val g = if (pin) .9f else .3f; val b = if (pin) .46f else 1f
        GLES20.glUseProgram(linP); val uc = GLES20.glGetUniformLocation(linP, "uCol")
        GLES20.glEnableVertexAttribArray(0)
        for (pass in 0..1) {
            val t = if (pass == 0) .028f else .008f
            hb.clear()
            for (k in 0 until 21) {
                val a = bones[2 * k]; val c = bones[2 * k + 1]
                val ax = l[2 * a] * 2 - 1; val ay = 1 - l[2 * a + 1] * 2; val bx = l[2 * c] * 2 - 1; val by = 1 - l[2 * c + 1] * 2
                val ddx = (bx - ax) * curAspect; val ddy = by - ay; val len = hypot(ddx, ddy); if (len < 1e-5f) continue
                val qx = -ddy / len * t / curAspect; val qy = ddx / len * t
                hb.put(floatArrayOf(ax + qx, ay + qy, ax - qx, ay - qy, bx + qx, by + qy, ax - qx, ay - qy, bx - qx, by - qy, bx + qx, by + qy))
            }
            hb.flip()
            if (pass == 0) GLES20.glUniform4f(uc, r * .45f, g * .45f, b * .45f, .45f) else GLES20.glUniform4f(uc, .9f, .9f, .9f, 1f)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, hb)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, hb.limit() / 2)
        }
        jb.clear(); for (k in 0 until 21) { jb.put(l[2 * k] * 2 - 1); jb.put(1 - l[2 * k + 1] * 2) }; jb.flip()
        GLES20.glUniform4f(uc, r, g, b, 1f)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, jb)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 21)
    }

    override fun onDrawFrame(g: GL10?) {
        val s = st ?: return
        try { s.updateTexImage(); s.getTransformMatrix(stm) } catch (_: Exception) { }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (act.visual) act.fuse()
        val R = act.rot; frames++
        val vrm = vr; val eyes = if (vrm) 2 else 1
        curAspect = (W / eyes.toFloat()) / H; K = curAspect * (TV / TH)
        if (hypot(R[8], R[9]) > .3f) gaze = atan2(-R[8], -R[9])
        if (recenter && (act.hasRot || frames > 120)) {
            menu.pinned = false; menuYaw = gaze - .4f
            for (i in 1 until panels.size) place(panels[i], gaze + .2f + .6f * (i - 1), 1.6f)
            recenter = false
        }
        if (!menu.pinned) {
            var d = gaze - .4f - menuYaw
            while (d > Math.PI) d -= (2 * Math.PI).toFloat(); while (d < -Math.PI) d += (2 * Math.PI).toFloat()
            menuYaw += d * .06f; place(menu, menuYaw, 1.4f)
        }
        logic(R)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        Matrix.frustumM(proj, 0, -TH * .05f, TH * .05f, -TV * .05f, TV * .05f, .05f, 30f); Matrix.transposeM(V, 0, R, 0)
        for (e in 0 until eyes) {
            GLES20.glViewport(e * W / eyes, 0, W / eyes, H)
            quad.position(0); GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(0)
            quad.position(2); GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(1)
            GLES20.glUseProgram(camP)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(camP, "uStm"), 1, false, stm, 0)
            val gn = act.gain; GLES20.glUniform1f(GLES20.glGetUniformLocation(camP, "uG"), gn); GLES20.glUniform1f(GLES20.glGetUniformLocation(camP, "uL"), ((gn - 1f) / 3f).coerceIn(0f, 1f))
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            Matrix.setIdentityM(T, 0); Matrix.translateM(T, 0, if (!vrm) 0f else if (e == 0) ipd / 2 else -ipd / 2, 0f, 0f)
            Matrix.multiplyMM(Ve, 0, T, 0, V, 0); Matrix.multiplyMM(vp, 0, proj, 0, Ve, 0)
            for (p in panels) drawPanel(p)
            drawHand()
            if (cursorVis) drawPanel(cursor)
        }
    }
}
