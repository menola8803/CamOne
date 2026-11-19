package se.snc.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.Surface
import se.snc.camera.data.MediaRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/*
 * Slow motion utan CameraX: när telefonen inte har höghastighetsprofilerna som CameraX kräver
 * (CamcorderProfile) men kameran ändå klarar höghastighetsvideo (CONSTRAINED_HIGH_SPEED_VIDEO),
 * öppnar appen kameran själv med Camera2 i en höghastighetssession. Bildrutorna går till sökaren
 * (TextureView) och till en egen videokodare (MediaCodec); tidsstämplarna sträcks ut så att filmen
 * spelas upp i 30 bilder per sekund, alltså 4× (120), 8× (240) eller 16× (480) långsammare.
 */

private const val TAG = "SncCamera"

/** En bildfrekvens för slow motion: storlek och kamera (Camera2-id). */
data class SlowOption(val fps: Int, val width: Int, val height: Int, val cameraId: String) {
    val label: String get() = "${minOf(width, height)}p"
}

object SlowMotionProbe {
    /** Större än 1080p tas inte med (4K i höghastighet klarar kodaren sällan). */
    private const val MAX_PIXELS = 1920 * 1080

    /**
     * Bildfrekvenserna (minst 120) som bakkamerorna klarar i en höghastighetssession, med den största
     * storleken för varje. Kamerorna gås igenom i telefonens ordning, så huvudkameran (0) vinner.
     */
    fun camera2Options(context: Context): List<SlowOption> {
        val cm = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val byFps = sortedMapOf<Int, SlowOption>()
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        for (id in ids) {
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO !in caps) continue
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val sizes = runCatching { map.highSpeedVideoSizes }.getOrNull() ?: continue
            for (size in sizes) {
                val pixels = size.width * size.height
                if (pixels > MAX_PIXELS) continue
                val ranges = runCatching { map.getHighSpeedVideoFpsRangesFor(size) }.getOrNull() ?: continue
                for (r in ranges) {
                    if (r.lower != r.upper || r.upper < 120) continue
                    val cur = byFps[r.upper]
                    if (cur == null || (cur.cameraId == id && pixels > cur.width * cur.height)) {
                        byFps[r.upper] = SlowOption(r.upper, size.width, size.height, id)
                    }
                }
            }
        }
        return byFps.values.toList()
    }

    /** Sökarens bildfrekvens innan inspelningen: [30, fps] om kameran har det, annars [fps, fps]. */
    fun previewRange(context: Context, opt: SlowOption): Range<Int> = runCatching {
        val cm = context.getSystemService(CameraManager::class.java)
        val map = cm.getCameraCharacteristics(opt.cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        map?.getHighSpeedVideoFpsRangesFor(android.util.Size(opt.width, opt.height))
            ?.firstOrNull { it.lower == 30 && it.upper == opt.fps }
    }.getOrNull() ?: Range(opt.fps, opt.fps)

    fun sensorOrientation(context: Context, cameraId: String): Int = runCatching {
        context.getSystemService(CameraManager::class.java)
            .getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION)
    }.getOrNull() ?: 90
}

/**
 * Kameran i slow motion (Camera2). Allt körs på en egen tråd. [open] startar sökaren; [startRecording]
 * och [stopRecording] spelar in. Efter varje inspelning görs en ny kodare och session för nästa.
 */
class SlowMotionCamera(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onReady()
        fun onRecordingStarted()
        /** [uri] = den sparade filmen, null om den inte gick att spara. */
        fun onRecordingStopped(uri: Uri?)
        fun onError(stage: String, error: Throwable?)
    }

    private val thread = HandlerThread("SncSlowMotion").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val manager = context.getSystemService(CameraManager::class.java)
    private val resolver = context.contentResolver

    private var option: SlowOption? = null
    private var previewRange: Range<Int>? = null
    private var previewSurface: Surface? = null
    private var device: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null

    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var track = -1
    private var firstPtsUs = -1L
    private var lastOutUs = -1L
    private var samples = 0
    private var recording = false
    private var stopping = false
    private var closed = true
    /** Ökar när kameran stängs eller öppnas på nytt; svar från en gammal kamera kastas. */
    private var generation = 0

    fun open(opt: SlowOption, texture: SurfaceTexture) {
        handler.post { openInternal(opt, texture, attempt = 1) }
    }

    fun startRecording(orientationDegrees: Int, location: android.location.Location? = null) {
        handler.post { startInternal(orientationDegrees, location) }
    }

    fun stopRecording() {
        handler.post { stopInternal() }
    }

    /** Stänger kameran och väntar (högst 1,5 s) tills det är gjort, så att CameraX kan öppna den direkt. */
    fun closeAndWait() {
        val done = CountDownLatch(1)
        handler.post {
            closeInternal()
            done.countDown()
        }
        runCatching { done.await(1500, TimeUnit.MILLISECONDS) }
    }

    fun release() {
        handler.post {
            closeInternal()
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // kamerabehörigheten finns innan kameraskärmen visas
    private fun openInternal(opt: SlowOption, texture: SurfaceTexture, attempt: Int) {
        closeInternal()
        closed = false
        val gen = ++generation
        option = opt
        previewRange = SlowMotionProbe.previewRange(context, opt)
        texture.setDefaultBufferSize(opt.width, opt.height)
        previewSurface = Surface(texture)
        Log.i(TAG, "Slowmo: öppnar kamera ${opt.cameraId} ${opt.width}x${opt.height} @ ${opt.fps} (försök $attempt)")
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (gen != generation || closed) {
                    d.close()
                    return
                }
                device = d
                try {
                    prepareEncoder()
                    createSession(gen)
                } catch (t: Throwable) {
                    listener.onError("förberedelse", t)
                }
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (gen == generation && !closed) {
                    device = null
                    listener.onError("frånkopplad", null)
                }
            }

            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                if (gen != generation || closed) return
                device = null
                // Kameran kan vara upptagen en kort stund (CameraX stänger den precis)
                if (attempt < 4 && (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE || error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)) {
                    handler.postDelayed({ if (gen == generation && !closed) openInternal(opt, texture, attempt + 1) }, 400L * attempt)
                } else {
                    listener.onError("kamerafel $error", null)
                }
            }
        }
        try {
            manager.openCamera(opt.cameraId, executor, callback)
        } catch (t: Throwable) {
            if (attempt < 4) {
                handler.postDelayed({ if (gen == generation && !closed) openInternal(opt, texture, attempt + 1) }, 400L * attempt)
            } else {
                listener.onError("öppna", t)
            }
        }
    }

    /** Kodaren förbereds (inte startad) så att dess yta kan ingå i sessionen från början. */
    private fun prepareEncoder() {
        val opt = option ?: return
        val bitrate = (opt.width.toLong() * opt.height * opt.fps * 0.12).toLong().coerceIn(8_000_000L, 120_000_000L).toInt()
        fun format(fast: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, opt.width, opt.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, opt.fps)
            setInteger(MediaFormat.KEY_CAPTURE_RATE, opt.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (fast) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, opt.fps)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
        }
        var codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.setCallback(encoderCallback, handler)
            codec.configure(format(fast = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            // Vissa kodare vill inte ha arbetstakten angiven: försök utan
            Log.w(TAG, "Slowmo: kodaren med arbetstakt ${opt.fps} gick inte, försöker utan: $t")
            codec.release()
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.setCallback(encoderCallback, handler)
            codec.configure(format(fast = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        encoderSurface = codec.createInputSurface()
        encoder = codec
    }

    private fun createSession(gen: Int) {
        val d = device ?: return
        val preview = previewSurface ?: return
        val input = encoderSurface ?: return
        val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(input))
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_HIGH_SPEED,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation || closed) {
                        s.close()
                        return
                    }
                    session = s as CameraConstrainedHighSpeedCaptureSession
                    try {
                        startPreview()
                        Log.i(TAG, "Slowmo: sessionen är klar")
                        listener.onReady()
                    } catch (t: Throwable) {
                        listener.onError("sökare", t)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen == generation) listener.onError("session", null)
                }
            }
        )
        d.createCaptureSession(cfg)
    }

    /** Bara sökaren (före och efter inspelningen). */
    private fun startPreview() {
        val s = session ?: return
        val d = device ?: return
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.addTarget(previewSurface ?: return)
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, previewRange ?: Range(option!!.fps, option!!.fps))
        s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), null, handler)
    }

    private fun startInternal(orientationDegrees: Int, location: android.location.Location?) {
        if (recording || closed) return
        val s = session
        val d = device
        val codec = encoder
        val input = encoderSurface
        val preview = previewSurface
        val opt = option
        if (s == null || d == null || codec == null || input == null || preview == null || opt == null) {
            listener.onError("inte redo", null)
            return
        }
        var newUri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, MediaRepository.newFileName("VID", "mp4").replace(".mp4", "_SLOMO${opt.fps}.mp4"))
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, MediaRepository.RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            newUri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("MediaStore gav ingen adress")
            val fd = resolver.openFileDescriptor(newUri, "rw") ?: error("filen kunde inte öppnas")
            pfd = fd
            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                it.setOrientationHint(orientationDegrees)
                if (location != null) it.setLocation(location.latitude.toFloat(), location.longitude.toFloat())
            }
            uri = newUri
            track = -1
            firstPtsUs = -1L
            lastOutUs = -1L
            samples = 0
            codec.start()
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(preview)
            b.addTarget(input)
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(opt.fps, opt.fps))
            s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), null, handler)
            recording = true
            stopping = false
            Log.i(TAG, "Slowmo: inspelning startad (${opt.fps} fps, vridning $orientationDegrees°)")
            listener.onRecordingStarted()
        } catch (t: Throwable) {
            Log.w(TAG, "Slowmo: inspelningen kunde inte startas: $t", t)
            runCatching { muxer?.release() }
            muxer = null
            runCatching { pfd?.close() }
            pfd = null
            uri = null
            newUri?.let { u -> runCatching { resolver.delete(u, null, null) } }
            listener.onError("start", t)
            // Kodaren kan ha startats: gör en ny session med ny kodare
            rebuildSession()
        }
    }

    private fun stopInternal() {
        if (!recording || stopping) return
        stopping = true
        runCatching { startPreview() } // tillbaka till bara sökaren
        runCatching { encoder?.signalEndOfInputStream() }
            .onFailure { finish(failed = false) }
        // Om kodaren aldrig skickar slutet: avsluta ändå
        val gen = generation
        handler.postDelayed({ if (gen == generation && recording && stopping) finish(failed = false) }, 3000L)
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val m = muxer ?: return
            if (track >= 0) return
            track = m.addTrack(format)
            m.start()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== encoder) {
                runCatching { codec.releaseOutputBuffer(index, false) }
                return
            }
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val m = muxer
            val opt = option
            if (m != null && opt != null && track >= 0 && info.size > 0 &&
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            ) {
                if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                // Sträck ut tiden: 240 bilder per sekund spelas upp i 30 → 8 gånger långsammare
                var out = (info.presentationTimeUs - firstPtsUs) * opt.fps / 30
                if (out <= lastOutUs) out = lastOutUs + 1
                lastOutUs = out
                val buf = codec.getOutputBuffer(index)
                if (buf != null) {
                    val bi = MediaCodec.BufferInfo()
                    bi.set(info.offset, info.size, out, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    runCatching { m.writeSampleData(track, buf, bi) }.onSuccess { samples++ }
                }
            }
            runCatching { codec.releaseOutputBuffer(index, false) }
            if (eos) finish(failed = false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== encoder) return
            Log.w(TAG, "Slowmo: kodarfel: $e", e)
            if (recording) finish(failed = true)
        }
    }

    /** Avslutar filmen: kodare och fil stängs, filen blir synlig (eller tas bort om den är tom). */
    private fun finish(failed: Boolean) {
        if (!recording) return
        recording = false
        stopping = false
        var ok = samples > 0 && !failed
        val codec = encoder
        encoder = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        if (track >= 0) runCatching { muxer?.stop() }.onFailure { ok = false }
        runCatching { muxer?.release() }
        muxer = null
        runCatching { pfd?.close() }
        pfd = null
        val u = uri
        uri = null
        if (u != null) {
            if (ok) {
                runCatching { resolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
            } else {
                runCatching { resolver.delete(u, null, null) }
            }
        }
        Log.i(TAG, "Slowmo: inspelning klar ($samples bildrutor, ${if (ok) "sparad" else "inte sparad"})")
        listener.onRecordingStopped(if (ok) u else null)
        rebuildSession()
    }

    /** Ny kodare och session (kodarens yta kan inte återanvändas efter en inspelning). */
    private fun rebuildSession() {
        if (closed || device == null) return
        runCatching { encoder?.release() }
        encoder = null
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        try {
            prepareEncoder()
            createSession(generation)
        } catch (t: Throwable) {
            listener.onError("ny session", t)
        }
    }

    private fun closeInternal() {
        if (recording) {
            runCatching { encoder?.signalEndOfInputStream() }
            closed = true
            finish(failed = false)
        }
        closed = true
        generation++
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { encoder?.release() }
        encoder = null
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        runCatching { previewSurface?.release() }
        previewSurface = null
    }
}
