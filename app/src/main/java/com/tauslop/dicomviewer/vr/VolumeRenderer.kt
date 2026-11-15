package com.tauslop.dicomviewer.vr

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.SystemClock
import com.tauslop.dicom.Volume
import com.tauslop.dicom.Window
import com.tauslop.dicom.vr.OrbitCamera
import com.tauslop.dicom.vr.Projection
import com.tauslop.dicom.vr.RayGeometry
import com.tauslop.dicom.vr.RenderMode
import com.tauslop.dicom.vr.TransferFunction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * GPU volume renderer. Owns an offscreen OpenGL ES 3.0 context on a dedicated thread; every call
 * hops onto that thread. Frames are rendered into an FBO and read back into a [Bitmap].
 */
class VolumeRenderer {

    class Frame(val bitmap: Bitmap, val millis: Long)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vr-gl") }
    private val gl = executor.asCoroutineDispatcher()

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var volTex = 0
    /** A volume is fully uploaded in [volTex] (GL thread only). */
    private var hasVolume = false
    private var tfTex = 0
    private var fbo = 0
    private var colorTex = 0
    private var fbW = 0
    private var fbH = 0
    private var readBuf: ByteBuffer? = null
    private var texFromPatient = FloatArray(16)
    /** One voxel of the uploaded texture, in texture coordinates. */
    private var texel = floatArrayOf(0f, 0f, 0f)
    private val uniforms = HashMap<String, Int>()
    /** Last transfer function uploaded (by identity: adjusted curves keep the preset's name). */
    private var lastTf: TransferFunction? = null

    /** Max 3D texture edge reported by the GPU (valid after [init]). */
    var max3dSize = 0
        private set

    /** Creates the EGL context and compiles the shaders. Throws with a readable message on failure. */
    suspend fun init() = withContext(gl) {
        if (context != EGL14.EGL_NO_CONTEXT) return@withContext
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "No OpenGL ES 3 config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "OpenGL ES 3 context unavailable" }
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        val v = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, v, 0)
        max3dSize = v[0]
        program = link(Shaders.VERTEX, Shaders.FRAGMENT)
        for (name in listOf("u_vol", "u_tf", "u_texFromPatient", "u_origin00", "u_dOriginX", "u_dOriginY", "u_dir",
            "u_step", "u_refStep", "u_huMin", "u_huMax", "u_tfSize", "u_stopAlpha", "u_shade", "u_texel",
            "u_mode", "u_winLo", "u_winHi", "u_bodyHu", "u_bodyRun")) {
            uniforms[name] = GLES30.glGetUniformLocation(program, name)
        }
        val tex = IntArray(2)
        GLES30.glGenTextures(2, tex, 0)
        volTex = tex[0]; tfTex = tex[1]
    }

    /**
     * Uploads [vol] as an R16F 3D texture of Hounsfield values, slice by slice, reporting
     * progress 0..1. Volumes larger than the GPU limit are reduced by striding.
     */
    suspend fun upload(vol: Volume, onProgress: (Float) -> Unit) = withContext(gl) {
        check(context != EGL14.EGL_NO_CONTEXT) { "Renderer not initialised" }
        val stride = generateSequence(1) { it * 2 }.first { s ->
            maxOf(vol.nx, vol.ny, vol.nz) / s <= max3dSize || s >= 8
        }
        val nx = vol.nx / stride; val ny = vol.ny / stride; val nz = vol.nz / stride
        // glTexStorage3D storage is immutable: replace the texture for each new volume.
        deleteVolume()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        volTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, volTex)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_3D, 1, GLES30.GL_R16F, nx, ny, nz)
        for (p in listOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_LINEAR)
        }
        for (p in listOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
        }
        val buf = ByteBuffer.allocateDirect(nx * ny * 2).order(ByteOrder.nativeOrder())
        val sb = buf.asShortBuffer()
        val slope = vol.rescale.slope.toFloat()
        val icpt = vol.rescale.intercept.toFloat()
        for (k in 0 until nz) {
            // Cancelled (series/folder changed): stop early; the partial texture is freed by the
            // next deleteVolume (freeVolume, upload or release), and hasVolume stays false.
            ensureActive()
            sb.clear()
            val kk = k * stride
            for (j in 0 until ny) {
                val row = (kk * vol.ny + j * stride) * vol.nx
                for (i in 0 until nx) sb.put(Half.fromFloat(vol.data[row + i * stride] * slope + icpt))
            }
            buf.rewind()
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, k, nx, ny, 1, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, buf)
            if (k % 8 == 0 || k == nz - 1) onProgress((k + 1).toFloat() / nz)
        }
        GLES30.glFinish()
        // Strided texture covers the same physical box only approximately; fine for k·stride ≤ n.
        texFromPatient = RayGeometry.patientToTexture(vol)
        texel = floatArrayOf(1f / nx, 1f / ny, 1f / nz)
        checkGl("upload")
        hasVolume = true
    }

    /**
     * Renders a [w]×[h] frame of the uploaded volume. [mode] VR uses [tf]; MIP/MinIP show the
     * projected HU through [window].
     */
    suspend fun render(
        camera: OrbitCamera, tf: TransferFunction, w: Int, h: Int, stepMm: Double, shade: Boolean,
        mode: RenderMode = RenderMode.VR, window: Window? = null,
    ): Frame = withContext(gl) {
        // Never sample a freed or half-uploaded texture.
        check(hasVolume) { "No volume uploaded" }
        val start = SystemClock.elapsedRealtime()
        ensureTarget(w, h)
        if (tf !== lastTf) uploadTf(tf)

        val (o00, dir) = camera.ray(0.0, 0.0, w, h)
        val (o10, _) = camera.ray(1.0, 0.0, w, h)
        val (o01, _) = camera.ray(0.0, 1.0, w, h)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, volTex)
        GLES30.glUniform1i(u("u_vol"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tfTex)
        GLES30.glUniform1i(u("u_tf"), 1)
        GLES30.glUniformMatrix4fv(u("u_texFromPatient"), 1, false, texFromPatient, 0)
        GLES30.glUniform3f(u("u_origin00"), o00.x.toFloat(), o00.y.toFloat(), o00.z.toFloat())
        val dx = o10 - o00
        val dy = o01 - o00
        GLES30.glUniform3f(u("u_dOriginX"), dx.x.toFloat(), dx.y.toFloat(), dx.z.toFloat())
        GLES30.glUniform3f(u("u_dOriginY"), dy.x.toFloat(), dy.y.toFloat(), dy.z.toFloat())
        GLES30.glUniform3f(u("u_dir"), dir.x.toFloat(), dir.y.toFloat(), dir.z.toFloat())
        GLES30.glUniform1f(u("u_step"), stepMm.toFloat())
        GLES30.glUniform1f(u("u_refStep"), TransferFunction.REFERENCE_STEP_MM.toFloat())
        GLES30.glUniform1f(u("u_huMin"), TransferFunction.LUT_HU_MIN.toFloat())
        GLES30.glUniform1f(u("u_huMax"), TransferFunction.LUT_HU_MAX.toFloat())
        GLES30.glUniform1f(u("u_tfSize"), TransferFunction.LUT_SIZE.toFloat())
        GLES30.glUniform1f(u("u_stopAlpha"), 0.95f)
        GLES30.glUniform1i(u("u_shade"), if (shade) 1 else 0)
        GLES30.glUniform3f(u("u_texel"), texel[0], texel[1], texel[2])
        val win = window ?: Window(0.0, 1.0)
        GLES30.glUniform1i(u("u_mode"), mode.ordinal)
        GLES30.glUniform1f(u("u_winLo"), (win.center - win.width / 2).toFloat())
        GLES30.glUniform1f(u("u_winHi"), (win.center + win.width / 2).toFloat())
        GLES30.glUniform1f(u("u_bodyHu"), Projection.BODY_HU.toFloat())
        GLES30.glUniform1f(u("u_bodyRun"), Projection.BODY_RUN_MM.toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        val buf = readBuf!!
        buf.rewind()
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        checkGl("render")
        buf.rewind()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf) // row 0 = framebuffer y 0 = image top row
        Frame(bmp, SystemClock.elapsedRealtime() - start)
    }

    /**
     * Frees the volume texture and the render target (the big GPU allocations) but keeps the
     * context and shaders for the next volume. Safe to call at any time: it runs on the GL thread
     * after any render or upload already queued, and later renders fail until the next upload.
     */
    suspend fun freeVolume() = withContext(gl + NonCancellable) {
        if (context == EGL14.EGL_NO_CONTEXT) return@withContext
        deleteVolume()
        deleteTarget()
    }

    /** Frees GPU resources and the context. Idempotent. */
    suspend fun release() = withContext(gl + NonCancellable) {
        if (context == EGL14.EGL_NO_CONTEXT) return@withContext
        deleteVolume()
        deleteTarget()
        if (tfTex != 0) GLES30.glDeleteTextures(1, intArrayOf(tfTex), 0)
        GLES30.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        EGL14.eglReleaseThread()
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        display = EGL14.EGL_NO_DISPLAY
        program = 0; tfTex = 0; lastTf = null
        uniforms.clear()
    }

    /** Stops the GL thread after already-queued work (call after [release]). */
    fun shutdown() = executor.shutdown()

    /** GL thread only. */
    private fun deleteVolume() {
        hasVolume = false
        if (volTex != 0) GLES30.glDeleteTextures(1, intArrayOf(volTex), 0)
        volTex = 0
    }

    /** GL thread only. */
    private fun deleteTarget() {
        if (colorTex != 0) GLES30.glDeleteTextures(1, intArrayOf(colorTex), 0)
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        fbo = 0; colorTex = 0; fbW = 0; fbH = 0
        readBuf = null
    }

    private fun u(name: String) = uniforms.getValue(name)

    private fun uploadTf(tf: TransferFunction) {
        val lut = tf.lut()
        val buf = ByteBuffer.allocateDirect(lut.size).order(ByteOrder.nativeOrder()).put(lut)
        buf.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tfTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, TransferFunction.LUT_SIZE, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        for (p in listOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, p, GLES30.GL_LINEAR)
        }
        for (p in listOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, p, GLES30.GL_CLAMP_TO_EDGE)
        }
        lastTf = tf
    }

    private fun ensureTarget(w: Int, h: Int) {
        if (w == fbW && h == fbH && fbo != 0) return
        if (fbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
            GLES30.glGenTextures(1, ids, 0); colorTex = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, colorTex, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete" }
        fbW = w; fbH = h
        readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: ${GLES30.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: ${GLES30.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun checkGl(where: String) {
        val e = GLES30.glGetError()
        check(e == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(e)} in $where" }
    }
}

/** Float → IEEE 754 half (binary16), round-to-nearest. android.util.Half needs API 26. */
internal object Half {
    fun fromFloat(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = ((bits ushr 23) and 0xFF) - 127 + 15
        val mant = bits and 0x7FFFFF
        return when {
            exp <= 0 -> sign            // tiny values flush to zero (HU are integers)
            exp >= 31 -> sign or 0x7C00 // overflow → infinity
            else -> sign or (((exp shl 10) or (mant ushr 13)) + ((mant ushr 12) and 1))
        }.toShort()
    }
}
