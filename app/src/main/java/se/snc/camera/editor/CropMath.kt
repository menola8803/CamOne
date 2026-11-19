package se.snc.camera.editor

import kotlin.math.abs

/*
 * Beskärning i redigeraren (ren Kotlin, provkörs lokalt). Rutan anges i delar av den roterade bilden
 * (0–1), så att den följer med när bilden vrids eller speglas.
 */

/** Beskärningsrutan: vänster, topp, höger, botten i delar av bilden (0–1). */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val isWhole get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

    /** Bilden vrids 90° medurs: rutan följer med. */
    fun rotatedCw() = CropRect(1f - bottom, left, 1f - top, right)

    /** Bilden vrids 90° moturs. */
    fun rotatedCcw() = CropRect(top, 1f - right, bottom, 1f - left)

    /** Bilden speglas vågrätt. */
    fun flipped() = CropRect(1f - right, top, 1f - left, bottom)

    /** Bilden speglas lodrätt (spegling av en bild som är vriden 90° eller 270°). */
    fun flippedV() = CropRect(left, 1f - bottom, right, 1f - top)

    /** Rutan i bildpunkter (x, y, bredd, höjd) för en bild på [w] × [h]. */
    fun toPixels(w: Int, h: Int): IntArray {
        val x = (left * w).toInt().coerceIn(0, w - 1)
        val y = (top * h).toInt().coerceIn(0, h - 1)
        val cw = ((right - left) * w).toInt().coerceIn(1, w - x)
        val ch = ((bottom - top) * h).toInt().coerceIn(1, h - y)
        return intArrayOf(x, y, cw, ch)
    }

    companion object {
        val WHOLE = CropRect(0f, 0f, 1f, 1f)
    }
}

/** Hörnen i beskärningsrutan. */
enum class CropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

object CropMath {

    /** Minsta rutan (del av bildens sida). */
    const val MIN = 0.08f

    /**
     * Den största rutan i mitten av en bild på [imageW] × [imageH] med formatet [ratio] (bredd/höjd i
     * bildpunkter). Null ger hela bilden.
     */
    fun centered(imageW: Int, imageH: Int, ratio: Float?): CropRect {
        if (ratio == null || imageW <= 0 || imageH <= 0) return CropRect.WHOLE
        val img = imageW.toFloat() / imageH
        return if (img > ratio) {
            val w = ratio / img
            CropRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        } else {
            val h = img / ratio
            CropRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        }
    }

    /** Flyttar hela rutan [dx], [dy] (delar av bilden) utan att den går utanför. */
    fun move(r: CropRect, dx: Float, dy: Float): CropRect {
        val mx = dx.coerceIn(-r.left, 1f - r.right)
        val my = dy.coerceIn(-r.top, 1f - r.bottom)
        return CropRect(r.left + mx, r.top + my, r.right + mx, r.bottom + my)
    }

    /**
     * Drar hörnet [corner] [dx], [dy] (delar av bilden). Med ett låst format [ratio] (bredd/höjd i
     * bildpunkter) följer den andra sidan med, och motsatt hörn står still.
     */
    fun dragCorner(r: CropRect, corner: CropCorner, dx: Float, dy: Float, imageW: Int, imageH: Int, ratio: Float?): CropRect {
        val left = corner == CropCorner.TOP_LEFT || corner == CropCorner.BOTTOM_LEFT
        val top = corner == CropCorner.TOP_LEFT || corner == CropCorner.TOP_RIGHT
        // Det fasta hörnet
        val ax = if (left) r.right else r.left
        val ay = if (top) r.bottom else r.top
        var x = (if (left) r.left else r.right) + dx
        var y = (if (top) r.top else r.bottom) + dy
        x = if (left) x.coerceIn(0f, ax - MIN) else x.coerceIn(ax + MIN, 1f)
        y = if (top) y.coerceIn(0f, ay - MIN) else y.coerceIn(ay + MIN, 1f)
        if (ratio != null && imageW > 0 && imageH > 0) {
            // r = (w · imageW) / (h · imageH) → h = w · imageW / (ratio · imageH)
            val k = imageW / (ratio * imageH)
            var w = abs(x - ax)
            var h = abs(y - ay)
            // Den sida som dragits mest bestämmer; den andra räknas fram
            if (w * k >= h) h = w * k else w = h / k
            // Får inte gå utanför bilden
            val maxW = if (left) ax else 1f - ax
            val maxH = if (top) ay else 1f - ay
            if (w > maxW) { w = maxW; h = w * k }
            if (h > maxH) { h = maxH; w = h / k }
            x = if (left) ax - w else ax + w
            y = if (top) ay - h else ay + h
        }
        return CropRect(minOf(x, ax), minOf(y, ay), maxOf(x, ax), maxOf(y, ay))
    }

    /** Närmaste hörnet till punkten ([px], [py] i delar av bilden), om det ligger inom [slop]. */
    fun hitCorner(r: CropRect, px: Float, py: Float, slopX: Float, slopY: Float): CropCorner? {
        val corners = listOf(
            CropCorner.TOP_LEFT to (r.left to r.top),
            CropCorner.TOP_RIGHT to (r.right to r.top),
            CropCorner.BOTTOM_LEFT to (r.left to r.bottom),
            CropCorner.BOTTOM_RIGHT to (r.right to r.bottom)
        )
        return corners
            .filter { (_, p) -> abs(p.first - px) <= slopX && abs(p.second - py) <= slopY }
            .minByOrNull { (_, p) -> abs(p.first - px) / slopX + abs(p.second - py) / slopY }
            ?.first
    }

    /** Formatet (bredd/höjd) för ett val som 4:3, vänt efter bildens riktning ([portrait]) och [turned]. */
    fun ratioFor(long: Float, short: Float, portrait: Boolean, turned: Boolean): Float {
        val landscapeRatio = long / short
        val tall = portrait != turned
        return if (tall) 1f / landscapeRatio else landscapeRatio
    }
}
