package se.snc.camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.snc.camera.data.MediaRepository
import se.snc.camera.util.awaitTask
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Mjukvaruporträtt: hittar personen med ML Kit och gör bakgrunden oskarp.
 * Används när kamerans HAL inte erbjuder ett eget porträttläge (vanligt på LineageOS).
 */
class PortraitProcessor(private val context: Context, private val media: MediaRepository) {

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    /** Returnerar true om bilden ändrades. */
    suspend fun process(uri: Uri, jpegQuality: Int): Boolean = withContext(Dispatchers.Default) {
        val source = media.loadBitmap(uri, maxDimension = 4096) ?: return@withContext false
        val mask = runCatching { segmenter.process(InputImage.fromBitmap(source, 0)).awaitTask() }.getOrNull()
            ?: return@withContext false

        val mw = mask.width
        val mh = mask.height
        val bytes = mask.buffer
        bytes.rewind()
        val buffer: FloatBuffer = bytes.asFloatBuffer()
        val alpha = IntArray(mw * mh)
        var sum = 0f
        for (i in 0 until mw * mh) {
            val c = buffer.get()
            sum += c
            // Mjuk övergång kring kanten
            val t = ((c - 0.35f) / 0.4f).coerceIn(0f, 1f)
            val s = t * t * (3 - 2 * t)
            alpha[i] = ((s * 255f).toInt() shl 24) or 0x00FFFFFF
        }
        // Ingen person hittades – låt bilden vara
        if (sum / (mw * mh) < 0.02f) return@withContext false

        val w = source.width
        val h = source.height
        val maskBitmap = Bitmap.createBitmap(alpha, mw, mh, Bitmap.Config.ARGB_8888)
        val maskFull = Bitmap.createScaledBitmap(maskBitmap, w, h, true)
        maskBitmap.recycle()

        val background = blur(source)
        val foreground = source.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(foreground).drawBitmap(maskFull, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            isFilterBitmap = true
        })
        maskFull.recycle()
        Canvas(background).drawBitmap(foreground, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        foreground.recycle()

        val ok = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    background.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                } ?: false
            }.getOrDefault(false)
        }
        background.recycle()
        source.recycle()
        ok
    }

    /** Snabb oskärpa: skala ned, boxblur några varv, skala upp. */
    private fun blur(src: Bitmap): Bitmap {
        val factor = max(8, max(src.width, src.height) / 320)
        val sw = max(1, src.width / factor)
        val sh = max(1, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(3) {
            boxBlur(px, sw, sh, 2, horizontal = true)
            boxBlur(px, sw, sh, 2, horizontal = false)
        }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        val big = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        small.recycle()
        return if (big.isMutable) big else big.copy(Bitmap.Config.ARGB_8888, true).also { big.recycle() }
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val out = IntArray(px.size)
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in max(0, i - r)..min(len - 1, i + r)) {
                    val idx = if (horizontal) line * w + k else k * w + line
                    val c = px[idx]
                    a += (c ushr 24) and 0xFF
                    rr += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                }
                val idx = if (horizontal) line * w + i else i * w + line
                out[idx] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        System.arraycopy(out, 0, px, 0, px.size)
    }

    fun close() {
        runCatching { segmenter.close() }
    }
}
