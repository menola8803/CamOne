package se.snc.camera.camera

import android.graphics.ColorMatrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/*
 * Live-filter i video: samma färgmatris som i fotolägena läggs på varje bildruta med OpenGL,
 * både i sökaren och i filmen som spelas in. CameraX lämnar bildrutorna till [FilterGlProcessor]
 * (en egen tråd) som ritar dem med filtret till sökaren och till videokodaren.
 */

private const val TAG = "SncCamera"

/** CameraX-effekt för sökaren och videon med appens live-filter. */
class VideoFilterEffect private constructor(private val processor: FilterGlProcessor) : CameraEffect(
    CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
    processor.executor,
    processor,
    Consumer<Throwable> { t -> Log.w(TAG, "Videofiltret: $t", t) }
) {
    constructor() : this(FilterGlProcessor())

    /** Filtret som ska gälla (null = inget filter). Kan ändras medan filmen spelas in. */
    fun setMatrix(matrix: ColorMatrix?) = processor.setMatrix(matrix)

    fun release() = processor.release()
}

/** Ritar kamerans bildrutor med en färgmatris (OpenGL ES 2) till alla utgångar (sökare och video). */
class FilterGlProcessor : SurfaceProcessor {
    private val thread = HandlerThread("SncVideoFilter").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { r -> handler.post(r) }

    // Färgmatrisen i GL-form (kolumnvis 4 × 4) och förskjutningen (0–1)
    @Volatile private var colorMat: FloatArray = identity()
    @Volatile private var colorOff: FloatArray = FloatArray(4)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var dummy: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uColor = 0
    private var uOffset = 0
    private var uTex = 0
    private var input: SurfaceTexture? = null
    private var inputTex = 0
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val texMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private var released = false
    private var frames = 0L

    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    fun setMatrix(matrix: ColorMatrix?) {
        if (matrix == null) {
            colorMat = identity()
            colorOff = FloatArray(4)
            return
        }
        // Android: 4 × 5 radvis, förskjutning i 0–255. GL: kolumnvis 4 × 4 och förskjutning i 0–1.
        val a = matrix.array
        val m = FloatArray(16)
        for (r in 0..3) for (c in 0..3) m[c * 4 + r] = a[r * 5 + c]
        colorMat = m
        colorOff = floatArrayOf(a[4] / 255f, a[9] / 255f, a[14] / 255f, 0f)
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released || !ensureGl()) {
            request.willNotProvideSurface()
            return
        }
        makeCurrent(dummy)
        val tex = newOesTexture()
        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        st.setOnFrameAvailableListener({ drawFrame(it) }, handler)
        input = st
        inputTex = tex
        Log.i(TAG, "Videofilter: indata ${request.resolution}")
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            if (!released) {
                makeCurrent(dummy)
                GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            }
            if (input === st) input = null
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released || !ensureGl()) {
            surfaceOutput.close()
            return
        }
        val surface = surfaceOutput.getSurface(executor) {
            outputs.remove(surfaceOutput)?.let { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglDestroySurface(display, it) }
            surfaceOutput.close()
        }
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "Videofilter: utgången kunde inte skapas (${surfaceOutput.size}), fel ${EGL14.eglGetError()}")
            return
        }
        outputs[surfaceOutput] = egl
        Log.i(TAG, "Videofilter: utgång ${surfaceOutput.size} (${surfaceOutput.targets})")
    }

    private fun drawFrame(st: SurfaceTexture) {
        if (released || st !== input) return
        makeCurrent(outputs.values.firstOrNull() ?: dummy)
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        val ts = st.timestamp
        val m = colorMat
        val o = colorOff
        for ((out, egl) in outputs) {
            if (!makeCurrent(egl)) continue
            out.updateTransformMatrix(outMatrix, texMatrix)
            GLES20.glViewport(0, 0, out.size.width, out.size.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTex)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, outMatrix, 0)
            GLES20.glUniformMatrix4fv(uColor, 1, false, m, 0)
            GLES20.glUniform4fv(uOffset, 1, o, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, egl, ts)
            EGL14.eglSwapBuffers(display, egl)
        }
        if (frames++ == 0L) Log.i(TAG, "Videofilter: första bildrutan ritad till ${outputs.size} utgångar")
    }

    /** Stänger tråden och OpenGL (när kameraskärmen stängs för gott). */
    fun release() {
        handler.post {
            if (released) return@post
            released = true
            outputs.values.forEach { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglDestroySurface(display, it) }
            outputs.clear()
            input?.setOnFrameAvailableListener(null)
            input = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (dummy != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, dummy)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            thread.quitSafely()
        }
    }

    private fun makeCurrent(surface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(display, surface, surface, context)

    /** Startar OpenGL första gången. False om det inte gick (då spelas video in utan filter). */
    private fun ensureGl(): Boolean {
        if (context != EGL14.EGL_NO_CONTEXT) return true
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            config = chooseConfig(recordable = true) ?: chooseConfig(recordable = false)
            checkNotNull(config) { "ingen EGL-konfiguration" }
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext ${EGL14.eglGetError()}" }
            dummy = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                ?: EGL14.EGL_NO_SURFACE
            check(makeCurrent(dummy)) { "eglMakeCurrent ${EGL14.eglGetError()}" }
            program = buildProgram()
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aTex = GLES20.glGetAttribLocation(program, "aTex")
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uColor = GLES20.glGetUniformLocation(program, "uColor")
            uOffset = GLES20.glGetUniformLocation(program, "uOffset")
            uTex = GLES20.glGetUniformLocation(program, "uTex")
            Log.i(TAG, "Videofilter: OpenGL ES ${version[0]}.${version[1]} klar")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Videofilter: OpenGL kunde inte startas: $t", t)
            context = EGL14.EGL_NO_CONTEXT
            false
        }
    }

    private fun chooseConfig(recordable: Boolean): EGLConfig? {
        val attribs = mutableListOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT
        )
        if (recordable) {
            attribs += EGLExt.EGL_RECORDABLE_ANDROID
            attribs += 1
        }
        attribs += EGL14.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attribs.toIntArray(), 0, configs, 0, 1, count, 0)
        return if (ok && count[0] > 0) configs[0] else null
    }

    private fun newOesTexture(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun buildProgram(): Int {
        val vs = compile(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
            """.trimIndent()
        )
        val fs = compile(
            GLES20.GL_FRAGMENT_SHADER,
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform mat4 uColor;
            uniform vec4 uOffset;
            varying vec2 vTex;
            void main() {
                vec4 c = texture2D(uTex, vTex);
                c.a = 1.0;
                vec4 o = uColor * c + uOffset;
                gl_FragColor = vec4(clamp(o.rgb, 0.0, 1.0), 1.0);
            }
            """.trimIndent()
        )
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "länkning: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "skuggning: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private companion object {
        fun identity(): FloatArray = FloatArray(16).also { for (i in 0..3) it[i * 4 + i] = 1f }

        fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
