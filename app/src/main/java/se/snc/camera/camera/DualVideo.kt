package se.snc.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import se.snc.camera.data.MediaRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/*
 * Dubbelvideo (läget Dubbel › Video): båda kamerornas sökare går genom en egen OpenGL-tråd
 * ([DualVideoRenderer], en CameraX-effekt per kamera). Tråden ritar varje kameras bild till dess sökare
 * som vanligt och – under inspelning – båda halvorna i sökarens ordning till videokodaren, med guldlinjen
 * emellan och live-filtret. [DualRecorder] spelar in bilden (H.264) och ljudet (AAC) till en MP4-fil.
 */

private const val TAG = "SncCamera"

/** CameraX-effekt för en av kamerorna i läget Dubbel (bara sökaren – inspelningen sköts av tråden). */
class DualTapEffect internal constructor(renderer: DualVideoRenderer, back: Boolean) : CameraEffect(
    CameraEffect.PREVIEW,
    renderer.executor,
    DualTapProcessor(renderer, back),
    Consumer<Throwable> { t -> Log.w(TAG, "Dubbelvideo: effektfel (${if (back) "bak" else "fram"}): $t", t) }
)

internal class DualTapProcessor(private val renderer: DualVideoRenderer, private val back: Boolean) : SurfaceProcessor {
    override fun onInputSurface(request: SurfaceRequest) = renderer.onInput(back, request)
    override fun onOutputSurface(surfaceOutput: SurfaceOutput) = renderer.onOutput(back, surfaceOutput)
}

/**
 * Den gemensamma OpenGL-tråden för båda kamerorna i dubbelvideo. Allt utom de flyktiga fälten används
 * bara på trådens egen Handler.
 */
class DualVideoRenderer {

    private val thread = HandlerThread("SncDualVideo").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { r -> handler.post(r) }

    val backEffect = DualTapEffect(this, back = true)
    val frontEffect = DualTapEffect(this, back = false)

    /** Frontkameran ligger överst i filmen (sökarens ordning). Kan ändras under inspelningen. */
    @Volatile var frontOnTop = false

    private inner class Cam(val back: Boolean) {
        var texture: SurfaceTexture? = null
        var tex = 0
        var bufW = 0
        var bufH = 0
        val texMatrix = FloatArray(16)
        var hasFrame = false
        var frames = 0L
        val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
        /** Grader medurs till stående (kamerans sensorvridning). */
        @Volatile var rotation = if (back) 90 else 270
        @Volatile var mirror = false
    }

    private val backCam = Cam(back = true)
    private val frontCam = Cam(back = false)

    // Live-filtret i filmen (sökarna får det via RenderEffect)
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
    private var released = false

    // Videokodarens yta under inspelning
    private var encoder: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encW = 0
    private var encH = 0
    private var encFrames = 0
    private var lastEncTs = -1L
    private var clockOffset: Long? = null
    private var swapPos = 0f

    private val outMatrix = FloatArray(16)
    private val halfMatrix = FloatArray(16)
    private val identityColor = identity()
    private val zeroOffset = FloatArray(4)
    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    /** Kamerornas vridning till stående och om frontkameran ska spegelvändas i filmen. */
    fun configure(backRotation: Int, frontRotation: Int, mirrorFront: Boolean) {
        backCam.rotation = backRotation
        frontCam.rotation = frontRotation
        frontCam.mirror = mirrorFront
    }

    /** Spegelvänd frontkameran i filmen (som i sökaren), enligt inställningen för frontkameran. */
    fun setMirrorFront(mirror: Boolean) {
        frontCam.mirror = mirror
    }

    fun setMatrix(matrix: ColorMatrix?) {
        if (matrix == null) {
            colorMat = identity()
            colorOff = FloatArray(4)
            return
        }
        val a = matrix.array
        val m = FloatArray(16)
        for (r in 0..3) for (c in 0..3) m[c * 4 + r] = a[r * 5 + c]
        colorMat = m
        colorOff = floatArrayOf(a[4] / 255f, a[9] / 255f, a[14] / 255f, 0f)
    }

    private fun cam(back: Boolean) = if (back) backCam else frontCam

    internal fun onInput(back: Boolean, request: SurfaceRequest) {
        if (released || !ensureGl()) {
            request.willNotProvideSurface()
            return
        }
        makeCurrent(dummy)
        val c = cam(back)
        val tex = newOesTexture()
        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        st.setOnFrameAvailableListener({ onFrame(c, it) }, handler)
        c.texture = st
        c.tex = tex
        c.bufW = request.resolution.width
        c.bufH = request.resolution.height
        c.hasFrame = false
        c.frames = 0
        Log.i(TAG, "Dubbelvideo: indata ${if (back) "bak" else "fram"} ${request.resolution} (vridning ${c.rotation}°)")
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            if (!released) {
                makeCurrent(dummy)
                GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            }
            if (c.texture === st) {
                c.texture = null
                c.hasFrame = false
            }
        }
    }

    internal fun onOutput(back: Boolean, out: SurfaceOutput) {
        if (released || !ensureGl()) {
            out.close()
            return
        }
        val c = cam(back)
        val surface = out.getSurface(executor) {
            c.outputs.remove(out)?.let { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglDestroySurface(display, it) }
            out.close()
        }
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "Dubbelvideo: sökaren kunde inte kopplas (${out.size}), fel ${EGL14.eglGetError()}")
            return
        }
        c.outputs[out] = egl
    }

    private fun onFrame(c: Cam, st: SurfaceTexture) {
        if (released || st !== c.texture) return
        makeCurrent(c.outputs.values.firstOrNull() ?: dummy)
        st.updateTexImage()
        st.getTransformMatrix(c.texMatrix)
        c.hasFrame = true
        val ts = st.timestamp
        for ((out, egl) in c.outputs) {
            if (!makeCurrent(egl)) continue
            out.updateTransformMatrix(outMatrix, c.texMatrix)
            GLES20.glViewport(0, 0, out.size.width, out.size.height)
            draw(c.tex, outMatrix, identityColor, zeroOffset)
            EGLExt.eglPresentationTimeANDROID(display, egl, ts)
            EGL14.eglSwapBuffers(display, egl)
        }
        if (c.frames++ == 0L) Log.i(TAG, "Dubbelvideo: första bildrutan från ${if (c.back) "bak" else "fram"}kameran")
        // Bakkameran bestämmer takten i filmen; frontkamerans senaste bild används
        if (c.back && encoder != EGL14.EGL_NO_SURFACE) drawComposite(ts)
    }

    /** Båda halvorna till videokodaren, i sökarens ordning, med guldlinjen och live-filtret. */
    private fun drawComposite(ts: Long) {
        val offset = clockOffset ?: DualVideoMath.clockOffset(ts, System.nanoTime(), SystemClock.elapsedRealtimeNanos())
            .also { clockOffset = it }
        val mono = ts - offset
        if (lastEncTs >= 0 && mono - lastEncTs < 28_000_000L) return // högst ca 30 bilder per sekund
        if (!makeCurrent(encoder)) return
        val dt = if (lastEncTs >= 0) mono - lastEncTs else 0L
        swapPos = DualVideoMath.stepSwap(swapPos, frontOnTop, dt)
        val p = DualVideoMath.ease(swapPos)
        val half = encH / 2
        GLES20.glViewport(0, 0, encW, encH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val m = colorMat
        val o = colorOff
        // Avstånd från filmens överkant: bakkameran 0 → half när frontkameran glider upp
        drawHalf(frontCam, ((1f - p) * half).toInt(), half, m, o)
        drawHalf(backCam, (p * half).toInt(), half, m, o)
        if (swapPos == 0f || swapPos == 1f) {
            val line = DualVideoMath.dividerPx(half)
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(0, encH - half - line / 2, encW, line)
            GLES20.glClearColor(0xC9 / 255f, 0xA5 / 255f, 0x4A / 255f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
        EGLExt.eglPresentationTimeANDROID(display, encoder, mono)
        EGL14.eglSwapBuffers(display, encoder)
        lastEncTs = mono
        if (encFrames++ == 0) {
            Log.i(TAG, "Dubbelvideo: första bildrutan i filmen ${encW}x$encH (bak ${backCam.bufW}x${backCam.bufH}, fram ${frontCam.bufW}x${frontCam.bufH}, klocka ${if (offset == 0L) "monotonic" else "realtime"})")
            Log.i(TAG, "Dubbelvideo: kamerans transform i matrisen – bak ${texInfo(backCam)}, fram ${texInfo(frontCam)}")
        }
    }

    private fun texInfo(c: Cam): String {
        val t = c.texMatrix
        val m = "[%.2f %.2f %.2f %.2f %.2f %.2f]".format(java.util.Locale.ROOT, t[0], t[1], t[4], t[5], t[12], t[13])
        return "${if (DualVideoMath.texRotated(t)) "vriden" else "ej vriden"}, " +
            "${if (DualVideoMath.texMirrored(t)) "speglad" else "ej speglad"} (sensor ${c.rotation}°, " +
            "spegla ${if (c.mirror) "ja" else "nej"}) $m"
    }

    private fun drawHalf(c: Cam, topPx: Int, half: Int, color: FloatArray, offset: FloatArray) {
        if (!c.hasFrame || c.texture == null || c.bufW <= 0) return
        GLES20.glViewport(0, encH - topPx - half, encW, half)
        // Kamerans vridning till stående och frontkamerans spegling finns oftast redan i texMatrix
        val a = DualVideoMath.halfMatrixFor(c.texMatrix, c.rotation, c.mirror, c.bufW, c.bufH, encW.toFloat() / half)
        Matrix.multiplyMM(halfMatrix, 0, c.texMatrix, 0, a, 0)
        draw(c.tex, halfMatrix, color, offset)
    }

    private fun draw(tex: Int, texMatrix: FloatArray, color: FloatArray, offset: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(uColor, 1, false, color, 0)
        GLES20.glUniform4fv(uOffset, 1, offset, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Kopplar in videokodarens yta. [done] får true när det gick (på trådens Handler). */
    fun attachEncoder(surface: Surface, width: Int, height: Int, done: (Boolean) -> Unit) {
        handler.post {
            if (released || !ensureGl()) {
                done(false)
                return@post
            }
            val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "Dubbelvideo: kodarens yta kunde inte kopplas, fel ${EGL14.eglGetError()}")
                done(false)
                return@post
            }
            encoder = egl
            encW = width
            encH = height
            encFrames = 0
            lastEncTs = -1L
            clockOffset = null
            swapPos = if (frontOnTop) 1f else 0f
            done(true)
        }
    }

    /** Kopplar bort kodarens yta efter den sista bildrutan och kör sedan [then] (på trådens Handler). */
    fun detachEncoder(then: (frames: Int) -> Unit) {
        handler.post {
            val frames = encFrames
            if (encoder != EGL14.EGL_NO_SURFACE) {
                makeCurrent(dummy)
                EGL14.eglDestroySurface(display, encoder)
                encoder = EGL14.EGL_NO_SURFACE
            }
            then(frames)
        }
    }

    /** Stänger tråden och OpenGL (när kameraskärmen stängs för gott). */
    fun release() {
        handler.post {
            if (released) return@post
            released = true
            for (c in listOf(backCam, frontCam)) {
                c.outputs.values.forEach { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglDestroySurface(display, it) }
                c.outputs.clear()
                c.texture?.setOnFrameAvailableListener(null)
                c.texture = null
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (encoder != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoder)
                encoder = EGL14.EGL_NO_SURFACE
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

    private fun makeCurrent(surface: EGLSurface): Boolean = EGL14.eglMakeCurrent(display, surface, surface, context)

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
            Log.i(TAG, "Dubbelvideo: OpenGL ES ${version[0]}.${version[1]} klar")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Dubbelvideo: OpenGL kunde inte startas: $t", t)
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

/**
 * Skriver bild och ljud till MP4. Bilden börjar på 0 med sin första (hel)bild; ljud från före den kastas.
 * Prover som kommer innan båda spåren är kända väntar i en kö.
 */
private class DualMuxer(
    fd: java.io.FileDescriptor,
    orientation: Int,
    @Volatile var expectAudio: Boolean,
    location: android.location.Location? = null
) {
    private val lock = Any()
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        setOrientationHint(orientation)
        if (location != null) setLocation(location.latitude.toFloat(), location.longitude.toFloat())
    }
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var stopped = false
    private var baseUs = -1L
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L
    private val pending = ArrayList<Triple<Boolean, ByteArray, MediaCodec.BufferInfo>>()
    var videoSamples = 0
        private set
    var audioSamples = 0
        private set
    val hasAudio get() = audioTrack >= 0

    fun addTrack(video: Boolean, format: MediaFormat) = synchronized(lock) {
        if (started || stopped) return@synchronized
        if (video && videoTrack < 0) videoTrack = muxer.addTrack(format)
        if (!video && audioTrack < 0) audioTrack = muxer.addTrack(format)
        maybeStart()
    }

    /** Ljudet gick inte att starta: filmen blir utan ljud. */
    fun audioGone() = synchronized(lock) {
        expectAudio = false
        maybeStart()
    }

    fun write(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (stopped || info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return@synchronized
        if (!started) {
            if (pending.size < 600) {
                val copy = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(copy, 0, info.size)
                val bi = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending += Triple(video, copy, bi)
            }
            return@synchronized
        }
        writeNow(video, buf, info)
    }

    private fun maybeStart() {
        if (started || videoTrack < 0 || (expectAudio && audioTrack < 0)) return
        muxer.start()
        started = true
        for ((video, bytes, info) in pending) writeNow(video, ByteBuffer.wrap(bytes), info)
        pending.clear()
    }

    private fun writeNow(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!video && audioTrack < 0) return
        if (baseUs < 0) {
            if (!video) return // ljud från före den första bilden
            baseUs = info.presentationTimeUs
        }
        var pts = info.presentationTimeUs - baseUs
        if (pts < 0) return
        if (video) {
            if (pts <= lastVideoUs) pts = lastVideoUs + 1
            lastVideoUs = pts
        } else {
            if (pts <= lastAudioUs) pts = lastAudioUs + 1
            lastAudioUs = pts
        }
        val bi = MediaCodec.BufferInfo().apply {
            set(info.offset, info.size, pts, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
        }
        runCatching { muxer.writeSampleData(if (video) videoTrack else audioTrack, buf, bi) }
            .onSuccess { if (video) videoSamples++ else audioSamples++ }
    }

    /** Avslutar filen. True om den har bildrutor. */
    fun stop(): Boolean = synchronized(lock) {
        if (stopped) return@synchronized false
        stopped = true
        var ok = started && videoSamples > 0
        if (started) runCatching { muxer.stop() }.onFailure { ok = false }
        runCatching { muxer.release() }
        ok
    }

    val durationUs get() = maxOf(lastVideoUs, 0L)
}

/**
 * En inspelning i dubbelvideo: H.264-kodare som [DualVideoRenderer] ritar till, AAC-ljud från mikrofonen
 * och MP4-filen i appens album (DUAL_….mp4).
 */
class DualRecorder(private val context: Context, private val renderer: DualVideoRenderer) {

    private val resolver = context.contentResolver
    private var codecThread: HandlerThread? = null
    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: DualMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var audioThread: Thread? = null
    @Volatile private var stopRequested = false
    private val videoDone = CountDownLatch(1)
    private var audioDone = CountDownLatch(0)
    private var finished = false

    /** Filmens storlek (efter start). */
    var width = 0
        private set
    var height = 0
        private set

    /** Startar inspelningen. False om det inte gick (då är allt städat). */
    suspend fun start(withAudio: Boolean, orientationHint: Int, location: android.location.Location? = null): Boolean =
        withContext(Dispatchers.IO) {
        try {
            val (codec, size) = createVideoCodec()
            width = size.first
            height = size.second
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, MediaRepository.newFileName("DUAL", "mp4"))
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, MediaRepository.RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val newUri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("MediaStore gav ingen adress")
            uri = newUri
            val fd = resolver.openFileDescriptor(newUri, "rw") ?: error("filen kunde inte öppnas")
            pfd = fd
            val m = DualMuxer(fd.fileDescriptor, orientationHint, expectAudio = withAudio, location = location)
            muxer = m
            val t = HandlerThread("SncDualEncoder").apply { start() }
            codecThread = t
            codec.setCallback(videoCallback(m), Handler(t.looper))
            codec.configure(videoFormat(width, height), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            inputSurface = surface
            codec.start()
            videoCodec = codec
            val attached = CompletableDeferred<Boolean>()
            renderer.attachEncoder(surface, width, height) { attached.complete(it) }
            if (withTimeoutOrNull(3000) { attached.await() } != true) error("kodarens yta kunde inte kopplas in")
            if (withAudio) startAudio(m)
            Log.i(TAG, "Dubbelvideo: inspelning startad ${width}x$height, ljud ${if (withAudio) "på" else "av"}, vridning $orientationHint°")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Dubbelvideo: inspelningen kunde inte startas: $t", t)
            cleanup(ok = false)
            false
        }
    }

    /** Stoppar inspelningen och sparar filmen. Ger filmens adress, eller null om inget kunde sparas. */
    suspend fun stop(): Uri? = withContext(Dispatchers.IO) {
        if (finished) return@withContext null
        stopRequested = true
        val detached = CompletableDeferred<Int>()
        renderer.detachEncoder { frames ->
            runCatching { videoCodec?.signalEndOfInputStream() }
            detached.complete(frames)
        }
        val frames = withTimeoutOrNull(2500) { detached.await() }
        if (frames == null) runCatching { videoCodec?.signalEndOfInputStream() }
        videoDone.await(3, TimeUnit.SECONDS)
        audioDone.await(3, TimeUnit.SECONDS)
        val m = muxer
        val result = cleanup(ok = true)
        Log.i(
            TAG,
            "Dubbelvideo: inspelning klar – ${frames ?: "?"} bildrutor ritade, ${m?.videoSamples ?: 0} i filmen, " +
                "${m?.audioSamples ?: 0} ljudprov, ${(m?.durationUs ?: 0L) / 1000} ms, ${if (result != null) "sparad" else "inte sparad"}"
        )
        result
    }

    /** Stänger kodare och fil. Ger adressen om filmen sparades. */
    private fun cleanup(ok: Boolean): Uri? {
        if (finished) return null
        finished = true
        stopRequested = true
        val saved = (muxer?.stop() ?: false) && ok
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        codecThread?.quitSafely()
        codecThread = null
        runCatching { pfd?.close() }
        pfd = null
        val u = uri
        uri = null
        if (u != null) {
            if (saved) {
                runCatching { resolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
            } else {
                runCatching { resolver.delete(u, null, null) }
            }
        }
        return if (saved) u else null
    }

    private fun videoCallback(m: DualMuxer) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            m.addTrack(video = true, format = format)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            runCatching {
                val buf = codec.getOutputBuffer(index)
                if (buf != null) m.write(video = true, buf = buf, info = info)
                codec.releaseOutputBuffer(index, false)
            }
            if (eos) videoDone.countDown()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "Dubbelvideo: kodarfel: $e", e)
            videoDone.countDown()
        }
    }

    /** H.264-kodare och den största storleken (stående 3:4) som den klarar i 30 bilder per sekund. */
    private fun createVideoCodec(): Pair<MediaCodec, Pair<Int, Int>> {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = runCatching { codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull()
        val size = DualVideoMath.VIDEO_SIZES.firstOrNull { (w, h) ->
            caps == null || runCatching { caps.areSizeAndRateSupported(w, h, 30.0) }.getOrDefault(false)
        } ?: DualVideoMath.VIDEO_SIZES.last()
        Log.i(TAG, "Dubbelvideo: kodare ${codec.name}, storlek ${size.first}x${size.second}")
        return codec to size
    }

    private fun videoFormat(w: Int, h: Int) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * 30 * 0.3).toInt().coerceIn(4_000_000, 20_000_000))
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }

    // ------------------------------------------------------------------ ljud

    @SuppressLint("MissingPermission")
    private fun startAudio(m: DualMuxer) {
        val latch = CountDownLatch(1)
        audioDone = latch
        audioThread = Thread({
            var record: AudioRecord? = null
            var codec: MediaCodec? = null
            try {
                val rate = 48_000
                val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuf > 0) { "mikrofonen saknas ($minBuf)" }
                fun open(source: Int): AudioRecord? {
                    val r = runCatching {
                        AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, 16_384))
                    }.getOrNull() ?: return null
                    if (r.state == AudioRecord.STATE_INITIALIZED) return r
                    r.release()
                    return null
                }
                // Kamerans mikrofoninställning i första hand, annars den vanliga mikrofonen
                val rec = open(MediaRecorder.AudioSource.CAMCORDER) ?: open(MediaRecorder.AudioSource.MIC)
                    ?: error("mikrofonen kunde inte öppnas")
                record = rec
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                }
                val aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec = aac
                aac.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                aac.start()
                rec.startRecording()
                audioLoop(rec, aac, rate, m)
            } catch (t: Throwable) {
                Log.w(TAG, "Dubbelvideo: ljudet kunde inte spelas in: $t")
                if (!m.hasAudio) m.audioGone()
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                latch.countDown()
            }
        }, "SncDualAudio").apply { start() }
    }

    private fun audioLoop(record: AudioRecord, codec: MediaCodec, rate: Int, m: DualMuxer) {
        val info = MediaCodec.BufferInfo()
        val ts = AudioTimestamp()
        var anchorNs = -1L
        var framesRead = 0L
        var eosSent = false
        var eosAt = 0L
        while (true) {
            if (!eosSent) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ptsUs = if (anchorNs >= 0) (anchorNs + framesRead * 1_000_000_000L / rate) / 1000 else System.nanoTime() / 1000
                    if (stopRequested) {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                        eosAt = System.nanoTime()
                    } else {
                        val ib = codec.getInputBuffer(inIdx)
                        val n = if (ib != null) {
                            ib.clear()
                            record.read(ib, minOf(ib.remaining(), 4096))
                        } else 0
                        if (n > 0) {
                            if (anchorNs < 0) {
                                // Tiden för det allra första provet: helst från ljudets egen tidsstämpel
                                anchorNs = if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                    ts.nanoTime - ts.framePosition * 1_000_000_000L / rate
                                } else {
                                    System.nanoTime() - (n / 2) * 1_000_000_000L / rate
                                }
                            }
                            val pts = (anchorNs + framesRead * 1_000_000_000L / rate) / 1000
                            framesRead += n / 2
                            codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                        }
                    }
                }
            }
            // Töm kodaren
            while (true) {
                val o = codec.dequeueOutputBuffer(info, if (eosSent) 10_000 else 0)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    m.addTrack(video = false, format = codec.outputFormat)
                    continue
                }
                if (o < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.getOutputBuffer(o)?.let { m.write(video = false, buf = it, info = info) }
                codec.releaseOutputBuffer(o, false)
                if (eos) return
            }
            if (eosSent && System.nanoTime() - eosAt > 2_000_000_000L) return
        }
    }
}
