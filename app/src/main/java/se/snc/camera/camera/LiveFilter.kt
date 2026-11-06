package se.snc.camera.camera

import android.graphics.ColorMatrix
import se.snc.camera.editor.PhotoFilter
import se.snc.camera.editor.scale
import se.snc.camera.editor.then

/**
 * Live-filter i sökaren: ett av redigerarens filter följt av förstärkning per färgkanal.
 * Samma färgmatris används i sökaren (RenderEffect) och på den sparade bilden, så att
 * bilden blir som man såg den.
 */
data class LiveFilter(
    val preset: PhotoFilter = PhotoFilter.ORIGINAL,
    /** 0..2, där 1 = oförändrat. */
    val red: Float = 1f,
    val green: Float = 1f,
    val blue: Float = 1f
) {
    val rgbChanged get() = red != 1f || green != 1f || blue != 1f
    val isActive get() = preset != PhotoFilter.ORIGINAL || rgbChanged

    fun matrix(): ColorMatrix {
        val base = preset.matrix()
        return if (rgbChanged) base.then(scale(red, green, blue)) else base
    }

    fun with(channel: ColorChannel, value: Float): LiveFilter {
        // Lätt att hitta tillbaka till 100 %: värden nära 1 fastnar på exakt 1.
        val v = value.coerceIn(0f, MAX_GAIN).let { if (kotlin.math.abs(it - 1f) < 0.04f) 1f else it }
        return when (channel) {
            ColorChannel.RED -> copy(red = v)
            ColorChannel.GREEN -> copy(green = v)
            ColorChannel.BLUE -> copy(blue = v)
        }
    }

    companion object {
        const val MAX_GAIN = 2f
    }
}

enum class ColorChannel { RED, GREEN, BLUE }
