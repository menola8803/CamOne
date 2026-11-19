package se.snc.camera.camera

import kotlin.math.roundToInt

/*
 * Vattenstämpelns text (ren Kotlin, provkörs lokalt): rad två byggs av fotots EXIF-uppgifter –
 * brännvidd (35 mm-ekvivalent), bländare, slutartid, ISO och datum.
 */
object WatermarkText {

    const val DEFAULT_TITLE = "SNC Camera"

    /** Rubriken: användarens text, eller "SNC Camera" om den är tom. Högst 40 tecken. */
    fun title(text: String): String = text.trim().take(40).ifEmpty { DEFAULT_TITLE }

    /** Ett EXIF-tal som "1.88", "188/100" eller "24". */
    fun number(v: String?): Double? {
        val s = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val slash = s.indexOf('/')
        return if (slash > 0) {
            val a = s.substring(0, slash).toDoubleOrNull() ?: return null
            val b = s.substring(slash + 1).toDoubleOrNull() ?: return null
            if (b == 0.0) null else a / b
        } else s.toDoubleOrNull()
    }

    /** Slutartid: "1/120 s", "0,5 s" blir "1/2 s", långa tider "2 s" eller "1.5 s". */
    fun exposure(seconds: Double?): String? {
        val t = seconds?.takeIf { it > 0 } ?: return null
        if (t >= 0.95) {
            val r = (t * 10).roundToInt() / 10.0
            return if (r == r.toLong().toDouble()) "${r.toLong()} s" else "$r s"
        }
        return "1/${(1 / t).roundToInt().coerceAtLeast(1)} s"
    }

    /** Bländare med en decimal: 1.88 → "f/1.9", 2.0 → "f/2". */
    fun aperture(f: Double?): String? {
        val v = f?.takeIf { it > 0 } ?: return null
        val r = (v * 10).roundToInt() / 10.0
        return if (r == r.toLong().toDouble()) "f/${r.toLong()}" else "f/$r"
    }

    /** EXIF-datum "2026:11:19 14:03:27" → "2026-11-19 14:03". */
    fun date(exifDate: String?): String? {
        val s = exifDate?.trim() ?: return null
        if (s.length < 16 || s[4] != ':' || s[7] != ':') return null
        return s.substring(0, 10).replace(':', '-') + " " + s.substring(11, 16)
    }

    /**
     * Rad två: "24 mm · f/1.9 · 1/120 s · ISO 100 · 2026-11-19 14:03" (det som saknas hoppas över).
     * Null om inget finns.
     */
    fun details(focal35: String?, fNumber: String?, exposureTime: String?, iso: String?, dateTime: String?): String? {
        val parts = listOfNotNull(
            number(focal35)?.takeIf { it > 0 }?.let { "${it.roundToInt()} mm" },
            aperture(number(fNumber)),
            exposure(number(exposureTime)),
            number(iso)?.takeIf { it > 0 }?.let { "ISO ${it.roundToInt()}" },
            date(dateTime)
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Måtten i bildpunkter för en bild där den kortaste sidan är [shortSide]. */
    data class Metrics(val margin: Float, val titleSize: Float, val detailSize: Float, val gap: Float, val bar: Float)

    fun metrics(shortSide: Float): Metrics = Metrics(
        margin = shortSide * 0.045f,
        titleSize = shortSide * 0.040f,
        detailSize = shortSide * 0.022f,
        gap = shortSide * 0.010f,
        bar = shortSide * 0.006f
    )
}
