package com.ovra.dash

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Takes camera frames (OES texture), stamps the overlay bar on them and draws the result to the
 * video encoder's surface and, when the app is open, to the on-screen preview.
 */
class GlRenderer(private val videoWidth: Int, private val videoHeight: Int) {

    /** Called on the GL thread about 4x a second with the share of pixels that changed (0..1) per camera. */
    var motionListener: ((rear: Float, front: Float) -> Unit)? = null
    @Volatile var motionEnabled = false

    private val thread = HandlerThread("ovra-gl").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewW = 0
    private var previewH = 0

    private var oesTex = 0
    private var frontTex = 0
    private var overlayTex = 0
    private lateinit var frontTexture: SurfaceTexture
    lateinit var frontSurface: Surface
        private set
    private val frontSt = FloatArray(16)
    private val frontMatrix = FloatArray(16)
    private var frontReady = false
    @Volatile var showFront = false
    @Volatile var frontRotationDegrees = 0

    // motion detection: cameras are rendered into a tiny offscreen image and compared over time
    private var motionFbo = 0
    private var motionTexture = 0
    private val motionPixels = ByteBuffer.allocateDirect(MOTION_W * MOTION_H * 4).order(ByteOrder.nativeOrder())
    private var prevRear: ByteArray? = null
    private var prevFront: ByteArray? = null
    private var lastMotionCheck = 0L
    private var oesProgram = 0
    private var texProgram = 0
    private lateinit var cameraTexture: SurfaceTexture
    lateinit var cameraSurface: Surface
        private set

    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    @Volatile var rotationDegrees = 0          // extra clockwise rotation applied to the camera image

    private var pendingOverlay: Bitmap? = null
    private var overlayHeightFraction = 0.07f
    private var hasOverlay = false

    private val fullQuad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texQuad = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private var overlayQuad = fullQuad

    /** Creates GL objects. Returns once the camera input surface is ready. */
    fun start() {
        val latch = CountDownLatch(1)
        handler.post {
            try { setup() } catch (e: Exception) { Log.e(TAG, "GL setup failed", e) }
            latch.countDown()
        }
        latch.await()
    }

    fun setEncoderSurface(surface: Surface?) = handler.post {
        if (encoderEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, encoderEgl)
            encoderEgl = EGL14.EGL_NO_SURFACE
        }
        if (surface != null && surface.isValid) {
            encoderEgl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        }
    }

    fun setPreview(texture: SurfaceTexture?, width: Int, height: Int) = handler.post {
        if (previewEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, previewEgl)
            previewEgl = EGL14.EGL_NO_SURFACE
        }
        if (texture != null && display != EGL14.EGL_NO_DISPLAY) {
            try {
                previewEgl = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
                previewW = width
                previewH = height
            } catch (e: Exception) {
                Log.w(TAG, "preview surface failed: ${e.message}")
            }
        }
    }

    /** Overlay bitmap is drawn across the bottom of the video (width = video width). */
    fun setOverlay(bitmap: Bitmap) {
        handler.post {
            pendingOverlay?.recycle()
            pendingOverlay = bitmap
        }
    }

    fun release() {
        handler.post {
            try {
                if (encoderEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderEgl)
                if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewEgl)
                if (::cameraTexture.isInitialized) cameraTexture.release()
                if (::cameraSurface.isInitialized) cameraSurface.release()
                if (::frontTexture.isInitialized) frontTexture.release()
                if (::frontSurface.isInitialized) frontSurface.release()
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            } catch (_: Exception) {}
            thread.quitSafely()
        }
    }

    // ---------------- GL setup ----------------

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

        oesProgram = buildProgram(VERTEX, FRAG_OES)
        texProgram = buildProgram(VERTEX, FRAG_2D)

        val tex = IntArray(4)
        GLES20.glGenTextures(4, tex, 0)
        oesTex = tex[0]
        overlayTex = tex[1]
        frontTex = tex[2]
        motionTexture = tex[3]
        for (t in listOf(oesTex, frontTex)) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        // offscreen target for motion detection
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, MOTION_W, MOTION_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        motionFbo = fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, motionTexture, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        cameraTexture = SurfaceTexture(oesTex)
        cameraTexture.setDefaultBufferSize(videoWidth, videoHeight)
        cameraTexture.setOnFrameAvailableListener({ drawFrame() }, handler)
        cameraSurface = Surface(cameraTexture)

        frontTexture = SurfaceTexture(frontTex)
        frontTexture.setDefaultBufferSize(FRONT_W, FRONT_H)
        frontTexture.setOnFrameAvailableListener({
            try {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                frontTexture.updateTexImage()
                frontTexture.getTransformMatrix(frontSt)
                frontReady = true
            } catch (_: Exception) {}
        }, handler)
        frontSurface = Surface(frontTexture)
    }

    private fun drawFrame() {
        try {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            cameraTexture.updateTexImage()
            cameraTexture.getTransformMatrix(stMatrix)
            val ts = cameraTexture.timestamp

            pendingOverlay?.let { bmp ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                overlayHeightFraction = bmp.height.toFloat() / videoHeight
                val top = -1f + 2f * overlayHeightFraction
                overlayQuad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, top, 1f, top))
                bmp.recycle()
                pendingOverlay = null
                hasOverlay = true
            }

            // texture matrix = SurfaceTexture transform x rotation about the centre
            val rot = FloatArray(16)
            Matrix.setIdentityM(rot, 0)
            Matrix.translateM(rot, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(rot, 0, rotationDegrees.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
            Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, rot, 0)
            if (frontReady) {
                val frot = FloatArray(16)
                Matrix.setIdentityM(frot, 0)
                Matrix.translateM(frot, 0, 0.5f, 0.5f, 0f)
                Matrix.rotateM(frot, 0, frontRotationDegrees.toFloat(), 0f, 0f, 1f)
                Matrix.translateM(frot, 0, -0.5f, -0.5f, 0f)
                Matrix.multiplyMM(frontMatrix, 0, frontSt, 0, frot, 0)
            }

            val now = System.currentTimeMillis()
            if (motionEnabled && now - lastMotionCheck >= 250) {
                lastMotionCheck = now
                checkMotion()
            }

            if (encoderEgl != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, encoderEgl, encoderEgl, context)
                drawScene(videoWidth, videoHeight)
                EGLExt.eglPresentationTimeANDROID(display, encoderEgl, ts)
                EGL14.eglSwapBuffers(display, encoderEgl)
            }
            if (previewEgl != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, previewEgl, previewEgl, context)
                drawScene(previewW, previewH)
                EGL14.eglSwapBuffers(display, previewEgl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "draw failed: ${e.message}")
        }
    }

    private fun sampleCamera(tex: Int, matrix: FloatArray): ByteArray {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
        GLES20.glViewport(0, 0, MOTION_W, MOTION_H)
        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        drawQuad(oesProgram, fullQuad, matrix)
        motionPixels.position(0)
        GLES20.glReadPixels(0, 0, MOTION_W, MOTION_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, motionPixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val luma = ByteArray(MOTION_W * MOTION_H)
        for (i in luma.indices) {
            val r = motionPixels.get(i * 4).toInt() and 0xFF
            val g = motionPixels.get(i * 4 + 1).toInt() and 0xFF
            val b = motionPixels.get(i * 4 + 2).toInt() and 0xFF
            luma[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }
        return luma
    }

    private fun changedShare(prev: ByteArray?, cur: ByteArray): Float {
        if (prev == null) return 0f
        var avgPrev = 0L
        var avgCur = 0L
        for (i in cur.indices) { avgPrev += prev[i].toInt() and 0xFF; avgCur += cur[i].toInt() and 0xFF }
        // ignore global brightness shifts (headlights, clouds, auto exposure)
        val shift = ((avgCur - avgPrev) / cur.size).toInt()
        var changed = 0
        for (i in cur.indices) {
            val d = (cur[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF) - shift
            if (d > 28 || d < -28) changed++
        }
        return changed.toFloat() / cur.size
    }

    private fun checkMotion() {
        val rear = sampleCamera(oesTex, texMatrix)
        val rearScore = changedShare(prevRear, rear)
        prevRear = rear
        var frontScore = 0f
        if (frontReady && showFront) {
            val front = sampleCamera(frontTex, frontMatrix)
            frontScore = changedShare(prevFront, front)
            prevFront = front
        }
        motionListener?.invoke(rearScore, frontScore)
    }

    /** Forget the reference images (e.g. when the guard is re-armed). */
    fun resetMotion() = handler.post { prevRear = null; prevFront = null }

    private fun drawScene(w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // camera image
        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        drawQuad(oesProgram, fullQuad, texMatrix)

        // cabin camera inset (top-right corner, 16:9, 30% of the width)
        if (showFront && frontReady) {
            val iw = (w * 0.30f).toInt()
            val ih = iw * 9 / 16
            val margin = (w * 0.015f).toInt()
            GLES20.glViewport(w - iw - margin, h - ih - margin, iw, ih)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frontTex)
            drawQuad(oesProgram, fullQuad, frontMatrix)
            GLES20.glViewport(0, 0, w, h)
        }

        // overlay bar
        if (hasOverlay) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(texProgram)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
            drawQuad(texProgram, overlayQuad, FLIP_Y)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun drawQuad(program: Int, positions: FloatBuffer, matrix: FloatArray) {
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        val uMat = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val uSampler = GLES20.glGetUniformLocation(program, "sTex")
        GLES20.glUniform1i(uSampler, 0)
        GLES20.glUniformMatrix4fv(uMat, 1, false, matrix, 0)
        positions.position(0)
        texQuad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, positions)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, texQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    companion object {
        private const val TAG = "OvraGl"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        const val FRONT_W = 1280
        const val FRONT_H = 720
        private const val MOTION_W = 64
        private const val MOTION_H = 36

        // bitmaps are top-down, GL textures bottom-up
        private val FLIP_Y = FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            Matrix.translateM(it, 0, 0f, 1f, 0f)
            Matrix.scaleM(it, 0, 1f, -1f, 1f)
        }

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values); position(0)
            }

        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """

        private const val FRAG_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """

        private const val FRAG_2D = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """
    }
}
