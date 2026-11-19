package se.snc.camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import androidx.exifinterface.media.ExifInterface
import se.snc.camera.R
import se.snc.camera.data.AppSettings

/*
 * Vattenstämpeln: appens text i nedre vänstra hörnet (rättvänd bild), med en tunn guldlinje framför och
 * – om man vill – brännvidd, bländare, tid, ISO och datum på raden under. Samma ritning används för
 * fotot, sökarens förhandsvisning och förhandsvisningen i Inställningar.
 */
object Watermark {

    /** Det som ska stå: rubriken och (valfritt) uppgiftsraden. */
    data class Spec(val title: String, val details: String?)

    class Fonts(val title: Typeface, val details: Typeface)

    @Volatile private var cached: Fonts? = null

    fun fonts(context: Context): Fonts = cached ?: run {
        val title = runCatching { ResourcesCompat.getFont(context, R.font.cinzel_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val details = runCatching { ResourcesCompat.getFont(context, R.font.plus_jakarta_sans_semibold) }.getOrNull() ?: Typeface.DEFAULT
        Fonts(title, details).also { cached = it }
    }

    /** Vattenstämpeln för ett foto med EXIF-uppgifterna [exif] – null om den är avstängd. */
    fun specFor(s: AppSettings, exif: Map<String, String>): Spec? {
        if (!s.watermark) return null
        val details = if (s.watermarkDetails) {
            WatermarkText.details(
                exif[ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM],
                exif[ExifInterface.TAG_F_NUMBER],
                exif[ExifInterface.TAG_EXPOSURE_TIME],
                exif[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY],
                exif[ExifInterface.TAG_DATETIME_ORIGINAL] ?: exif[ExifInterface.TAG_DATETIME]
            )
        } else null
        return Spec(WatermarkText.title(s.watermarkText), details)
    }

    /** Ritar vattenstämpeln i en yta på [width] × [height] bildpunkter (nedre vänstra hörnet). */
    fun draw(canvas: Canvas, width: Float, height: Float, spec: Spec, fonts: Fonts) {
        val m = WatermarkText.metrics(minOf(width, height))
        val shadow = Color.argb(150, 0, 0, 0)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.title
            textSize = m.titleSize
            color = Color.WHITE
            setShadowLayer(m.titleSize * 0.14f, 0f, m.titleSize * 0.05f, shadow)
        }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.details
            textSize = m.detailSize
            color = Color.argb(235, 255, 255, 255)
            setShadowLayer(m.detailSize * 0.18f, 0f, m.detailSize * 0.06f, shadow)
        }
        val details = spec.details
        // Nerifrån: uppgiftsraden, mellanrum, rubriken
        val bottom = height - m.margin
        val detailBaseline = bottom - detailPaint.descent()
        val titleBaseline = if (details != null) {
            detailBaseline + detailPaint.ascent() - m.gap - titlePaint.descent()
        } else {
            bottom - titlePaint.descent()
        }
        val top = titleBaseline + titlePaint.ascent() * 0.82f
        val x = m.margin + m.bar * 3.2f
        // Guldlinjen framför texten
        val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xC9, 0xA5, 0x4A)
            setShadowLayer(m.bar, 0f, 0f, shadow)
        }
        canvas.drawRoundRect(m.margin, top, m.margin + m.bar, bottom - detailPaint.descent() * 0.2f, m.bar / 2, m.bar / 2, gold)
        canvas.drawText(spec.title, x, titleBaseline, titlePaint)
        if (details != null) canvas.drawText(details, x, detailBaseline, detailPaint)
    }

    /**
     * Lägger vattenstämpeln på fotot. Ger samma bitmapp om den gick att rita på, annars en ny (den gamla
     * återvinns då av den som anropar).
     */
    fun apply(context: Context, bitmap: Bitmap, spec: Spec): Bitmap {
        val target = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        draw(Canvas(target), target.width.toFloat(), target.height.toFloat(), spec, fonts(context))
        Log.i("SncCamera", "Vattenstämpel: ${target.width}x${target.height} \"${spec.title}\"${spec.details?.let { " – $it" } ?: ""}")
        return target
    }
}
