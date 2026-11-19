package se.snc.camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.atan

/*
 * Läget Panorama: telefonen hålls stående och sveps åt höger eller vänster. Gyrot räknar hur långt
 * kameran vridits; var gång den vridits ungefär en tredjedel av bildens bredd sparas en bild, som sys
 * ihop med de tidigare (PanoramaStitcher, egen tråd). En guldlinje med en prick i sökaren visar om
 * telefonen lutar uppåt eller nedåt.
 */

private const val TAG = "SncCamera"

/** Gyrot under ett panorama: vridning åt sidan (höger positiv) och lutning (uppåt positiv), i radianer. */
class PanoramaMotion(context: Context) : SensorEventListener {
    private val manager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var lastNs = 0L
    private var running = false

    val available get() = gyro != null

    @Volatile var yaw = 0f
        private set
    @Volatile var pitch = 0f
        private set
    /** Vridningens fart (rad/s, utjämnad). */
    @Volatile var speed = 0f
        private set

    fun start(): Boolean {
        val s = gyro ?: return false
        if (running) return true
        yaw = 0f
        pitch = 0f
        speed = 0f
        lastNs = 0L
        running = true
        manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        val t = event.timestamp
        if (lastNs != 0L) {
            val dt = ((t - lastNs) / 1e9f).coerceIn(0f, 0.1f)
            // Stående telefon: sidled = runt telefonens y-axel, lutning = runt x-axeln
            val gy = -event.values[1]
            yaw += gy * dt
            pitch += event.values[0] * dt
            speed = speed * 0.8f + abs(gy) * 0.2f
        }
        lastNs = t
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Läget under ett panorama, för sökaren. */
data class PanoramaStatus(
    val frames: Int = 0,
    val degrees: Int = 0,
    /** Lutning i grader (uppåt positiv) sedan starten. */
    val pitchDeg: Float = 0f,
    /** Sveps för fort. */
    val tooFast: Boolean = false
)

/**
 * Ett panorama som tas: tar emot bildrutor från analysen, väljer ut bilderna med gyrot och syr ihop dem
 * på en egen tråd.
 */
class PanoramaSession(
    private val motion: PanoramaMotion,
    /** Brännvidd i bildpunkter i analysbilden (samma åt alla håll: kvadratiska bildpunkter). */
    private val focalLong: Float,
    private val onStatus: (PanoramaStatus) -> Unit,
    private val onPreview: (Bitmap) -> Unit,
    /** Panoramat är fullt (hela varvet eller största bredden) – ska avslutas. */
    private val onFull: () -> Unit
) : ImageAnalysis.Analyzer {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "SncPanorama") }
    private val queued = AtomicInteger(0)
    @Volatile private var stitcher: PanoramaStitcher? = null
    @Volatile var active = false
        private set
    @Volatile private var lastKeptYaw = 0f
    @Volatile private var keptAny = false
    @Volatile private var stepRad = 0.35f
    private var lastStatusMs = 0L
    @Volatile private var fullSignalled = false
    @Volatile private var failed = false

    fun start() {
        stitcher = null
        keptAny = false
        fullSignalled = false
        failed = false
        lastKeptYaw = 0f
        active = true
    }

    /**
     * Slutar ta emot bilder. Ger panoramat (ARGB, bredd, höjd), antal bilder, grader och om det slutade
     * för att svepet gick för fort (inget överlapp) när tråden är klar.
     */
    fun finish(done: (Triple<IntArray, Int, Int>?, Int, Int, Boolean) -> Unit) {
        active = false
        worker.execute {
            val s = stitcher
            val result = runCatching { s?.result() }.onFailure { Log.w(TAG, "Panorama: kunde inte sättas ihop: $it", it) }.getOrNull()
            val frames = s?.frames ?: 0
            val deg = s?.degrees?.toInt() ?: 0
            val gap = s?.gap == true
            Log.i(
                TAG,
                "Panorama: klart – $frames bilder, $deg°, ${result?.second ?: 0}x${result?.third ?: 0}, " +
                    "${if (s?.leftwards == true) "åt vänster" else "åt höger"}, gyro ensamt ${s?.gyroOnly ?: 0}, " +
                    "${if (gap) "slutade vid för stort hopp, " else ""}förskjutningar ${s?.offsets}"
            )
            stitcher = null
            done(result, frames, deg, gap)
        }
    }

    fun cancel() {
        active = false
        worker.execute { stitcher = null }
    }

    fun release() {
        active = false
        worker.shutdown()
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!active || failed) return
            val now = System.currentTimeMillis()
            val yaw = motion.yaw
            val pitch = motion.pitch
            // För fort: gyrot säger det, eller så hinner sömnaden inte med (bilder väntar)
            val tooFast = motion.speed > 0.9f || queued.get() >= 2
            val s = stitcher
            if (now - lastStatusMs > 80) {
                lastStatusMs = now
                onStatus(
                    PanoramaStatus(
                        frames = s?.frames ?: 0,
                        degrees = s?.degrees?.toInt() ?: 0,
                        pitchDeg = Math.toDegrees(pitch.toDouble()).toFloat(),
                        tooFast = tooFast
                    )
                )
            }
            val keep = !keptAny || abs(yaw - lastKeptYaw) >= stepRad
            if (!keep || queued.get() >= 4) return
            val frame = copyUpright(image) ?: return
            val (px, w, h) = frame
            keptAny = true
            lastKeptYaw = yaw
            queued.incrementAndGet()
            worker.execute {
                try {
                    if (!active && stitcher == null) return@execute
                    val st = stitcher ?: PanoramaStitcher(w, h, focalLong).also {
                        stitcher = it
                        // En ny bild när kameran vridits ungefär en tredjedel av bildens bredd
                        stepRad = 0.36f * 2f * atan(w / (2f * it.focal))
                        Log.i(TAG, "Panorama: start ${w}x$h, brännvidd ${it.focal.toInt()} px, steg ${Math.toDegrees(stepRad.toDouble()).toInt()}°, bredd högst ${it.canvasW}")
                    }
                    val t0 = System.nanoTime()
                    val ok = st.add(px, yaw, pitch)
                    if (st.frames <= 3 || st.frames % 4 == 0) {
                        Log.i(TAG, "Panorama: bild ${st.frames} vid ${Math.toDegrees(yaw.toDouble()).toInt()}° (${(System.nanoTime() - t0) / 1_000_000} ms)")
                    }
                    if (ok) st.preview(720, 160)?.let { (p, pw, ph) -> onPreview(Bitmap.createBitmap(p, pw, ph, Bitmap.Config.ARGB_8888)) }
                    if ((!ok || st.full) && !fullSignalled) {
                        fullSignalled = true
                        onFull()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Panorama: bilden kunde inte sys in: $t", t)
                    failed = true
                    if (!fullSignalled) {
                        fullSignalled = true
                        onFull()
                    }
                } finally {
                    queued.decrementAndGet()
                }
            }
        } finally {
            image.close()
        }
    }

    /** RGBA-bilden från analysen, vriden stående, som ARGB (pixlar, bredd, höjd). */
    private fun copyUpright(image: ImageProxy): Triple<IntArray, Int, Int>? {
        val plane = image.planes.firstOrNull() ?: return null
        val sw = image.width
        val sh = image.height
        val stride = plane.rowStride
        val ps = plane.pixelStride
        val buf = plane.buffer
        buf.rewind()
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val rot = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val dw = if (rot == 90 || rot == 270) sh else sw
        val dh = if (rot == 90 || rot == 270) sw else sh
        val out = IntArray(dw * dh)
        val limit = bytes.size - 3
        for (y in 0 until sh) {
            val row = y * stride
            // Var i den stående bilden raden hamnar: start och steg per bildpunkt
            val start: Int
            val step: Int
            when (rot) {
                90 -> { start = sh - 1 - y; step = dw }           // (x, y) → (sh-1-y, x)
                180 -> { start = (sh - 1 - y) * dw + sw - 1; step = -1 }
                270 -> { start = (sw - 1) * dw + y; step = -dw }  // (x, y) → (y, sw-1-x)
                else -> { start = y * dw; step = 1 }
            }
            var o = start
            var i = row
            for (x in 0 until sw) {
                if (i <= limit) {
                    out[o] = (0xFF shl 24) or ((bytes[i].toInt() and 0xFF) shl 16) or
                        ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
                }
                o += step
                i += ps
            }
        }
        return Triple(out, dw, dh)
    }
}
