package se.snc.camera.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.annotation.StringRes
import se.snc.camera.R
import kotlin.math.hypot

/** Ett färgfilter definierat som en 4×5 färgmatris. */
enum class PhotoFilter(@StringRes val label: Int) {
    ORIGINAL(R.string.filter_original),
    VIVID(R.string.filter_vivid),
    WARM(R.string.filter_warm),
    COOL(R.string.filter_cool),
    GOLDEN(R.string.filter_golden),
    FILM(R.string.filter_film),
    FADE(R.string.filter_fade),
    VINTAGE(R.string.filter_vintage),
    DRAMATIC(R.string.filter_dramatic),
    MONO(R.string.filter_mono),
    NOIR(R.string.filter_noir),
    SEPIA(R.string.filter_sepia);

    fun matrix(): ColorMatrix = when (this) {
        ORIGINAL -> ColorMatrix()
        VIVID -> saturation(1.4f).then(contrast(1.08f))
        WARM -> scale(1.08f, 1.0f, 0.86f).then(offset(8f, 4f, 0f))
        COOL -> scale(0.9f, 1.0f, 1.12f).then(offset(0f, 2f, 8f))
        GOLDEN -> scale(1.1f, 1.02f, 0.82f).then(saturation(1.15f)).then(offset(10f, 6f, -4f))
        FILM -> contrast(1.12f).then(scale(1.04f, 1.0f, 0.94f)).then(offset(0f, 4f, 14f)).then(saturation(0.9f))
        FADE -> contrast(0.82f).then(saturation(0.8f)).then(offset(18f, 18f, 18f))
        VINTAGE -> sepia().mix(ColorMatrix(), 0.45f).then(contrast(0.9f)).then(offset(14f, 8f, 0f))
        DRAMATIC -> contrast(1.35f).then(saturation(0.75f))
        MONO -> saturation(0f)
        NOIR -> saturation(0f).then(contrast(1.45f)).then(offset(-12f, -12f, -12f))
        SEPIA -> sepia()
    }
}

/** Justeringar i redigeraren, alla i intervallet -1..1 (vinjett 0..1). */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val vignette: Float = 0f
) {
    val isDefault get() = this == Adjustments()
}

data class EditState(
    val filter: PhotoFilter = PhotoFilter.ORIGINAL,
    val intensity: Float = 1f,
    val adjustments: Adjustments = Adjustments(),
    /** 0, 90, 180, 270 */
    val rotation: Int = 0,
    val flipHorizontal: Boolean = false
) {
    val isUnchanged get() = filter == PhotoFilter.ORIGINAL && adjustments.isDefault && rotation == 0 && !flipHorizontal
}

// --------------------------- Matrisverktyg ---------------------------

/** Returnerar en matris där [this] körs först och sedan [next]. */
fun ColorMatrix.then(next: ColorMatrix): ColorMatrix = ColorMatrix().also { it.setConcat(next, this) }

/** Linjär blandning mellan två matriser (för filterstyrka). */
fun ColorMatrix.mix(other: ColorMatrix, amountOfOther: Float): ColorMatrix {
    val a = array
    val b = other.array
    val out = FloatArray(20) { i -> a[i] + (b[i] - a[i]) * amountOfOther }
    return ColorMatrix(out)
}

fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

fun contrast(c: Float): ColorMatrix {
    val t = 128f * (1f - c)
    return ColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

fun scale(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }

fun offset(r: Float, g: Float, b: Float) = ColorMatrix(
    floatArrayOf(
        1f, 0f, 0f, 0f, r,
        0f, 1f, 0f, 0f, g,
        0f, 0f, 1f, 0f, b,
        0f, 0f, 0f, 1f, 0f
    )
)

fun sepia() = ColorMatrix(
    floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
)

/** Bygger den slutliga färgmatrisen: filter (med styrka) följt av justeringar. */
fun buildColorMatrix(state: EditState): ColorMatrix {
    val filterMatrix = ColorMatrix().mix(state.filter.matrix(), state.intensity.coerceIn(0f, 1f))
    val a = state.adjustments
    var m = filterMatrix
    if (a.warmth != 0f) m = m.then(scale(1f + 0.14f * a.warmth, 1f + 0.02f * a.warmth, 1f - 0.14f * a.warmth))
    if (a.saturation != 0f) m = m.then(saturation(1f + a.saturation))
    if (a.contrast != 0f) m = m.then(contrast(1f + 0.6f * a.contrast))
    if (a.brightness != 0f) m = m.then(offset(a.brightness * 70f, a.brightness * 70f, a.brightness * 70f))
    return m
}

/** Roterar/speglar en bitmapp. */
fun Bitmap.transformed(rotation: Int, flipHorizontal: Boolean): Bitmap {
    if (rotation % 360 == 0 && !flipHorizontal) return this
    val matrix = Matrix().apply {
        if (flipHorizontal) postScale(-1f, 1f)
        postRotate(rotation.toFloat())
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** Renderar slutbilden i full upplösning. */
fun renderEdited(source: Bitmap, state: EditState): Bitmap {
    val oriented = source.transformed(state.rotation, state.flipHorizontal)
    val out = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(buildColorMatrix(state))
    }
    canvas.drawBitmap(oriented, 0f, 0f, paint)
    val v = state.adjustments.vignette
    if (v > 0f) {
        val cx = out.width / 2f
        val cy = out.height / 2f
        val radius = hypot(cx, cy)
        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(0x00000000, 0x00000000, ((0xC0 * v).toInt().coerceIn(0, 255) shl 24)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Paint().apply { this.shader = shader })
    }
    if (oriented !== source) oriented.recycle()
    return out
}
