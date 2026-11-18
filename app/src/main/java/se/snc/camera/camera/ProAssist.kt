package se.snc.camera.camera

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import se.snc.camera.R
import se.snc.camera.ui.theme.GlassLine
import se.snc.camera.ui.theme.GlassStrong
import se.snc.camera.ui.theme.Usb
import java.util.Locale

/** Pro-hjälpmedlen som kan slås på var för sig. */
enum class AssistTool { HISTOGRAM, PEAKING, ZEBRA }

/** Vad analysen ska räkna fram just nu. */
data class AssistOptions(
    val histogram: Boolean,
    val peaking: Boolean,
    val zebra: Boolean,
    /** Frontkameran visas spegelvänd i sökaren – lagret speglas likadant. */
    val mirror: Boolean
) {
    val any get() = histogram || peaking || zebra
}

/**
 * En bildruta med hjälpmedel: [overlay] (fokusmarkering och zebra, skärmens riktning, samma bildyta som
 * sökaren i 4:3) och [histogram] (0–1 per fack). [clipLow]/[clipHigh] = en märkbar del är helt svart eller
 * utfrätt.
 */
class AssistFrame(
    val overlay: Bitmap?,
    val histogram: FloatArray?,
    val clipLow: Boolean,
    val clipHigh: Boolean,
    val seq: Long
)

/**
 * Räknar fram hjälpmedlen ur en liten analysbild (YUV, ca 640 × 480) högst ca 14 gånger per sekund.
 * Bara Y-planet används. Lagret ritas i tre bitmappar som turas om, så att sökaren aldrig ritar en
 * bitmapp som skrivs i samtidigt.
 */
class AssistAnalyzer(
    private val options: () -> AssistOptions,
    private val onFrame: (AssistFrame) -> Unit
) : ImageAnalysis.Analyzer {

    private companion object {
        const val TAG = "SncCamera"
        const val MIN_INTERVAL_MS = 70L
        const val LOG_INTERVAL_MS = 4_000L
        /** Klippning visas när minst så här stor del av bilden ligger i det yttersta facket. */
        const val CLIP_SHARE = 0.005f
    }

    private var y = ByteArray(0)
    private var pixels = IntArray(0)
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var next = 0
    private val counts = IntArray(AssistMath.BINS)
    private val stats = AssistMath.Stats()
    private var phase = 0
    private var seq = 0L
    private var lastMs = 0L
    private var lastLog = 0L
    private var logged = false

    override fun analyze(image: ImageProxy) {
        try {
            val o = options()
            val now = SystemClock.uptimeMillis()
            if (!o.any || now - lastMs < MIN_INTERVAL_MS) return
            lastMs = now
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val stride = plane.rowStride
            val buf = plane.buffer
            buf.rewind()
            val n = minOf(buf.remaining(), stride * h)
            if (y.size < stride * h) y = ByteArray(stride * h)
            buf.get(y, 0, n)
            val rotation = image.imageInfo.rotationDegrees

            var hist: FloatArray? = null
            var clipLow = false
            var clipHigh = false
            if (o.histogram) {
                AssistMath.histogram(y, stride, w, h, counts)
                hist = AssistMath.normalize(counts)
                clipLow = AssistMath.share(counts, 0) >= CLIP_SHARE
                clipHigh = AssistMath.share(counts, AssistMath.BINS - 1) >= CLIP_SHARE
            }

            var bmp: Bitmap? = null
            if (o.peaking || o.zebra) {
                val (ow, oh) = AssistMath.outputSize(w, h, rotation)
                if (pixels.size != ow * oh) pixels = IntArray(ow * oh)
                AssistMath.overlay(y, stride, w, h, rotation, o.mirror, o.peaking, o.zebra, phase, pixels, stats)
                phase = (phase + 2) % (AssistMath.ZEBRA_STRIPE * 2)
                val b = bitmaps[next]?.takeIf { it.width == ow && it.height == oh }
                    ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { bitmaps[next] = it }
                b.setPixels(pixels, 0, ow, 0, 0, ow, oh)
                next = (next + 1) % bitmaps.size
                bmp = b
            }

            onFrame(AssistFrame(bmp, hist, clipLow, clipHigh, ++seq))

            if (!logged || now - lastLog > LOG_INTERVAL_MS) {
                logged = true
                lastLog = now
                val total = stats.total.coerceAtLeast(1)
                Log.i(
                    TAG,
                    "Hjälpmedel: bildruta ${w}x$h vriden $rotation°, " +
                        "histogram=${o.histogram} kant=${if (o.peaking) String.format(Locale.US, "%.2f %%", 100f * stats.peak / total) else "av"} " +
                        "(tröskel ${stats.threshold}) zebra=${if (o.zebra) String.format(Locale.US, "%.2f %%", 100f * stats.zebra / total) else "av"}"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Hjälpmedel: bildrutan kunde inte läsas", t)
        } finally {
            image.close()
        }
    }
}

/**
 * Fokusmarkering och zebra ovanpå sökaren. Lagret har samma 4:3-bildyta som sökaren och beskärs på samma
 * sätt (ContentScale.Crop = FILL_CENTER). Läser flödet själv, så att bara lagret ritas om.
 */
@Composable
fun AssistOverlay(frames: StateFlow<AssistFrame?>, modifier: Modifier = Modifier) {
    val frame by frames.collectAsStateWithLifecycle()
    val f = frame ?: return
    val bmp = f.overlay ?: return
    val image = remember(f.seq) { bmp.asImageBitmap() }
    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.fillMaxSize())
}

/** Histogrammet i en liten glasruta: ljusstyrkan i guld, röd kant till vänster/höger vid klippning. */
@Composable
fun HistogramBox(frames: StateFlow<AssistFrame?>, modifier: Modifier = Modifier) {
    val frame by frames.collectAsStateWithLifecycle()
    val f = frame ?: return
    val hist = f.histogram ?: return
    val shape = RoundedCornerShape(12.dp)
    val description = stringResource(R.string.cd_histogram)
    Box(
        modifier
            .size(width = 124.dp, height = 64.dp)
            .clip(shape)
            .background(GlassStrong)
            .border(1.dp, GlassLine, shape)
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(0f, h)
                for (i in hist.indices) {
                    val x = w * i / (hist.size - 1)
                    lineTo(x, h - hist[i] * h)
                }
                lineTo(w, h)
                close()
            }
            drawPath(path, Usb.Gold.copy(alpha = 0.45f))
            drawPath(path, Usb.GoldL, style = Stroke(width = 1.dp.toPx()))
            val clipW = 3.dp.toPx()
            if (f.clipLow) drawRect(ClipRed, Offset(0f, 0f), Size(clipW, h))
            if (f.clipHigh) drawRect(ClipRed, Offset(w - clipW, 0f), Size(clipW, h))
        }
    }
}

private val ClipRed = androidx.compose.ui.graphics.Color(0xFFFF3B30)
