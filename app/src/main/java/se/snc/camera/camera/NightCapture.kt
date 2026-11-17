package se.snc.camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Androiddelen av nattfotot: bildrutor från CameraX (ImageAnalysis, YUV i full upplösning) läses
 * in, läggs ihop i NightStack.kt och blir en Bitmap. Telefonens gyro avgör om den står stilla
 * (stativ) – då tas fler bilder med längre exponering.
 */

/** Antal bilder i ett nattfoto: i handen (kortare exponering) och på stativ (längre exponering). */
object NightFrames {
    const val HAND = 8
    const val STEADY = 16

    fun forSteady(steady: Boolean): Int = if (steady) STEADY else HAND
}

/** Planen från kameran kopieras hit. Återanvänds mellan bildrutorna så att minnet inte växer. */
class PlaneBuffers {
    var y = ByteArray(0)
    var u = ByteArray(0)
    var v = ByteArray(0)
}

private fun readPlane(buffer: ByteBuffer, old: ByteArray): ByteArray {
    buffer.rewind()
    val n = buffer.remaining()
    val arr = if (old.size >= n) old else ByteArray(n)
    buffer.get(arr, 0, n)
    return arr
}

/** Läser en YUV_420_888-bild från CameraX. Bredd och höjd avrundas nedåt till jämna tal. */
fun ImageProxy.toFrameView(buf: PlaneBuffers): FrameView {
    val p = planes
    buf.y = readPlane(p[0].buffer, buf.y)
    buf.u = readPlane(p[1].buffer, buf.u)
    buf.v = readPlane(p[2].buffer, buf.v)
    return FrameView(
        width = width and 1.inv(),
        height = height and 1.inv(),
        y = buf.y,
        yRow = p[0].rowStride,
        u = buf.u,
        v = buf.v,
        uvRow = p[1].rowStride,
        uvPix = p[1].pixelStride
    )
}

/**
 * En tagning i nattläget: tar emot bildrutor tills det räcker ([targetFrames] för nattfoto,
 * [durationNs] för ljusspår och silkesvatten) och lämnar resultatet i [result].
 * [onFrame] anropas från analystråden; [finishNow] och [cancel] från huvudtråden.
 */
class StackSession(
    val style: StackStyle,
    private val targetFrames: Int,
    private val durationNs: Long,
    private val pool: ExecutorService,
    private val parts: Int,
    private val onProgress: (Float) -> Unit
) {
    class Result(val frame: YuvFrame, val frames: Int, val rotationDegrees: Int)

    val result = CompletableDeferred<Result>()

    private val lock = Any()
    private var night: NightMerger? = null
    private var long: LongExposure? = null
    private var startNs = -1L
    private var rotation = 0

    @Volatile
    var finished = false
        private set

    fun onFrame(f: FrameView, rotationDegrees: Int, timestampNs: Long) {
        synchronized(lock) {
            if (finished) return
            try {
                rotation = rotationDegrees
                if (style == StackStyle.NIGHT) {
                    val m = night ?: NightMerger(pool, parts, f.width, f.height, targetFrames).also { night = it }
                    val done = m.add(f)
                    onProgress(m.accepted.toFloat() / targetFrames)
                    if (done) complete()
                } else {
                    if (startNs < 0) startNs = timestampNs
                    val le = long ?: LongExposure(pool, parts, f.width, f.height, style == StackStyle.TRAILS).also { long = it }
                    le.add(f)
                    val p = ((timestampNs - startNs).toFloat() / durationNs).coerceIn(0f, 1f)
                    onProgress(p)
                    if (p >= 1f || le.isFull) complete()
                }
            } catch (t: Throwable) {
                // Även OutOfMemoryError: tagningen avbryts men appen lever vidare
                finished = true
                night = null
                long = null
                result.completeExceptionally(t)
            }
        }
    }

    /** Tidsgränsen nådd: lägg ihop de bilder som hann komma. */
    fun finishNow() {
        synchronized(lock) {
            if (finished) return
            try {
                complete()
            } catch (t: Throwable) {
                finished = true
                result.completeExceptionally(t)
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            finished = true
            night = null
            long = null
            result.cancel()
        }
    }

    private fun complete() {
        finished = true
        val frame: YuvFrame?
        val count: Int
        if (style == StackStyle.NIGHT) {
            frame = night?.finish(tone = true)
            count = night?.accepted ?: 0
        } else {
            frame = long?.finish()
            count = long?.frames ?: 0
        }
        night = null
        long = null
        if (frame == null) {
            result.completeExceptionally(IllegalStateException("Inga bildrutor kom från kameran"))
        } else {
            result.complete(Result(frame, count, rotation))
        }
    }
}

object StackRender {
    private const val BAND = 64

    /**
     * Gör en Bitmap av [f]: beskuren till [longOverShort] (lång/kort sida, null = hela 4:3-bilden),
     * roterad med [rotationDegrees] och spegelvänd om [mirror] (frontkameran).
     */
    fun toBitmap(
        f: YuvFrame,
        longOverShort: Float?,
        rotationDegrees: Int,
        mirror: Boolean,
        pool: ExecutorService,
        parts: Int
    ): Bitmap {
        val crop = centerCropRect(f.width, f.height, longOverShort)
        val x0 = crop[0]
        val y0 = crop[1]
        val w = crop[2]
        val h = crop[3]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val buf = IntArray(w * BAND)
        var row = 0
        while (row < h) {
            val rows = min(BAND, h - row)
            val start = row
            pool.parallelRanges(rows, parts) { from, to ->
                yuvToArgbRows(f, x0, y0, w, start + from, start + to, buf, from * w)
            }
            bmp.setPixels(buf, 0, w, 0, start, w, rows)
            row += rows
        }
        val degrees = ((rotationDegrees % 360) + 360) % 360
        if (degrees == 0 && !mirror) return bmp
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        val out = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }
}

/**
 * Känner av om telefonen står stilla (gyrot). Stilla i minst 0,7 s = stativ eller bord.
 * [onSteady] anropas på huvudtråden när läget ändras.
 */
class MotionMeter(context: Context, private val onSteady: (Boolean) -> Unit) : SensorEventListener {
    private companion object {
        /** rad/s – en hand darrar mer än så här, ett stativ mindre. */
        const val STILL = 0.015f
        const val HOLD_NS = 700_000_000L
    }

    private val manager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var level = 1f
    private var stillSince = 0L
    private var running = false

    var steady = false
        private set

    fun start() {
        val sensor = gyro ?: return
        if (running) return
        running = true
        level = 1f
        stillSince = 0L
        manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        running = false
        manager?.unregisterListener(this)
        if (steady) {
            steady = false
            onSteady(false)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        level = level * 0.85f + sqrt(x * x + y * y + z * z) * 0.15f
        val now = event.timestamp
        val still = level < STILL
        if (!still) {
            stillSince = 0L
        } else if (stillSince == 0L) {
            stillSince = now
        }
        val s = still && now - stillSince >= HOLD_NS
        if (s != steady) {
            steady = s
            onSteady(s)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
