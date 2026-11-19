package se.snc.camera.scan

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Dokumentskannerns räkningar i ren Kotlin (provkörs lokalt): hitta ett papper i bilden, ordna hörnen
 * och jämna ut belysningen så att papperet blir vitt och texten mörk.
 *
 * Hörnen anges alltid normaliserade (0–1) i ordningen övre vänster, övre höger, nedre höger, nedre vänster:
 * x0, y0, x1, y1, x2, y2, x3, y3.
 */
object DocumentMath {

    /** Rutnätet som papperet söks i har ungefär så här många punkter på bredden. */
    const val GRID = 160

    /** Hörn en bit innanför kanten, när inget papper hittas. */
    fun defaultQuad(inset: Float = 0.08f): FloatArray =
        floatArrayOf(inset, inset, 1 - inset, inset, 1 - inset, 1 - inset, inset, 1 - inset)

    /**
     * Medelvärdet av [factor] × [factor] punkter i Y-planet ([stride] byte per rad) – ger ett litet rutnät
     * (bredd [w] / factor) att söka i.
     */
    fun downscale(y: ByteArray, stride: Int, w: Int, h: Int, factor: Int): IntArray {
        val gw = w / factor
        val gh = h / factor
        val out = IntArray(gw * gh)
        val n = factor * factor
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var sum = 0
                val x0 = gx * factor
                for (dy in 0 until factor) {
                    val row = (gy * factor + dy) * stride + x0
                    for (dx in 0 until factor) sum += y[row + dx].toInt() and 0xFF
                }
                out[gy * gw + gx] = sum / n
            }
        }
        return out
    }

    /** Ljusstyrkan (0–255) i ett ARGB-rutnät. */
    fun luminance(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val c = argb[i]
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
    }

    /** Otsus tröskel: delar ljusstyrkorna i två grupper med så stor skillnad som möjligt. */
    fun otsu(lum: IntArray): Int {
        val hist = IntArray(256)
        for (v in lum) hist[v.coerceIn(0, 255)]++
        val total = lum.size.toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    private fun box3(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            var n = 0
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    s += src[yy * w + xx]
                    n++
                }
            }
            out[y * w + x] = s / n
        }
        return out
    }

    /** Ordnar fyra punkter (x, y, x, y …) som övre vänster, övre höger, nedre höger, nedre vänster. */
    fun order(p: FloatArray): FloatArray {
        val idx = (0 until 4).toList()
        val tl = idx.minBy { p[it * 2] + p[it * 2 + 1] }
        val br = idx.maxBy { p[it * 2] + p[it * 2 + 1] }
        val rest = idx.filter { it != tl && it != br }
        val tr = if (rest.size == 2) rest.maxBy { p[it * 2] - p[it * 2 + 1] } else idx.maxBy { p[it * 2] - p[it * 2 + 1] }
        val bl = if (rest.size == 2) rest.first { it != tr } else idx.minBy { p[it * 2] - p[it * 2 + 1] }
        return floatArrayOf(p[tl * 2], p[tl * 2 + 1], p[tr * 2], p[tr * 2 + 1], p[br * 2], p[br * 2 + 1], p[bl * 2], p[bl * 2 + 1])
    }

    /** Ytan av fyrhörningen (skosnöresformeln), i samma enheter som punkterna i kvadrat. */
    fun area(q: FloatArray): Float {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return abs(s) / 2f
    }

    /** Konvex fyrhörning (alla hörn svänger åt samma håll). */
    fun isConvex(q: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = q[((i + 1) % 4) * 2] - q[i * 2]
            val ay = q[((i + 1) % 4) * 2 + 1] - q[i * 2 + 1]
            val bx = q[((i + 2) % 4) * 2] - q[((i + 1) % 4) * 2]
            val by = q[((i + 2) % 4) * 2 + 1] - q[((i + 1) % 4) * 2 + 1]
            val cross = ax * by - ay * bx
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) continue
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /**
     * Letar efter ett papper i rutnätet [lum] ([w] × [h]): ljusa punkter (Otsus tröskel), den största
     * sammanhängande ljusa ytan som inte är hela bakgrunden, och dess ytterpunkter som hörn. Ger hörnen
     * normaliserade, eller null om inget tydligt papper finns.
     */
    fun detect(lumIn: IntArray, w: Int, h: Int): FloatArray? {
        if (w < 8 || h < 8) return null
        val lum = box3(lumIn, w, h)
        val t = otsu(lum)
        // För liten skillnad mellan ljust och mörkt: inget tydligt papper
        var darkSum = 0L
        var darkN = 0
        var brightSum = 0L
        var brightN = 0
        for (v in lum) if (v > t) {
            brightSum += v
            brightN++
        } else {
            darkSum += v
            darkN++
        }
        if (brightN == 0 || darkN == 0) return null
        if (brightSum / brightN - darkSum / darkN < 30) return null

        val label = IntArray(w * h)
        val queue = IntArray(w * h)
        var next = 0
        val total = w * h
        data class Comp(val id: Int, val size: Int, val borders: Int)
        val comps = mutableListOf<Comp>()
        for (start in 0 until total) {
            if (label[start] != 0 || lum[start] <= t) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            var size = 0
            var top = false
            var bottom = false
            var left = false
            var right = false
            while (head < tail) {
                val i = queue[head++]
                size++
                val x = i % w
                val y = i / w
                if (y == 0) top = true
                if (y == h - 1) bottom = true
                if (x == 0) left = true
                if (x == w - 1) right = true
                if (x > 0 && label[i - 1] == 0 && lum[i - 1] > t) { label[i - 1] = next; queue[tail++] = i - 1 }
                if (x < w - 1 && label[i + 1] == 0 && lum[i + 1] > t) { label[i + 1] = next; queue[tail++] = i + 1 }
                if (y > 0 && label[i - w] == 0 && lum[i - w] > t) { label[i - w] = next; queue[tail++] = i - w }
                if (y < h - 1 && label[i + w] == 0 && lum[i + w] > t) { label[i + w] = next; queue[tail++] = i + w }
            }
            val borders = listOf(top, bottom, left, right).count { it }
            comps += Comp(next, size, borders)
        }
        val pick = comps
            .filter { it.size >= total * 0.08 && it.size <= total * 0.92 && it.borders < 4 }
            .maxByOrNull { it.size } ?: return null

        // Ytterpunkterna: minsta och största x + y respektive x − y
        var minSum = Int.MAX_VALUE
        var maxSum = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE
        var maxDiff = Int.MIN_VALUE
        val p = IntArray(8)
        for (i in 0 until total) {
            if (label[i] != pick.id) continue
            val x = i % w
            val y = i / w
            val s = x + y
            val d = x - y
            if (s < minSum) { minSum = s; p[0] = x; p[1] = y }
            if (d > maxDiff) { maxDiff = d; p[2] = x; p[3] = y }
            if (s > maxSum) { maxSum = s; p[4] = x; p[5] = y }
            if (d < minDiff) { minDiff = d; p[6] = x; p[7] = y }
        }
        val quad = FloatArray(8) { k -> (p[k] + 0.5f) / (if (k % 2 == 0) w else h) }
        if (!isConvex(quad)) return null
        // Ytan ska stämma ungefär med den ljusa ytan (annars är det ingen fyrkant)
        val quadArea = area(quad) * w * h
        val ratio = quadArea / pick.size
        if (ratio < 0.75f || ratio > 1.35f) return null
        // Ingen sida får vara orimligt kort
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val len = hypot((quad[j * 2] - quad[i * 2]) * w, (quad[j * 2 + 1] - quad[i * 2 + 1]) * h)
            if (len < minOf(w, h) * 0.12f) return null
        }
        return quad
    }

    /**
     * Vrider normaliserade hörn [rotation] grader medurs (som bilden vrids till upprätt) och ordnar dem
     * på nytt.
     */
    fun rotate(q: FloatArray, rotation: Int): FloatArray {
        val r = ((rotation % 360) + 360) % 360
        if (r == 0) return q.copyOf()
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = q[i * 2]
            val y = q[i * 2 + 1]
            val (nx, ny) = when (r) {
                90 -> (1 - y) to x
                180 -> (1 - x) to (1 - y)
                else -> y to (1 - x)
            }
            out[i * 2] = nx
            out[i * 2 + 1] = ny
        }
        return order(out)
    }

    /** Mjukar upp hörnen mellan bildrutor (sökaren): nytt = gammalt + (ny − gammalt) · [k]. */
    fun smooth(old: FloatArray?, new: FloatArray, k: Float = 0.5f): FloatArray {
        if (old == null) return new
        // Hoppade papperet långt (ett annat papper): ta det nya direkt
        var jump = 0f
        for (i in 0 until 8) jump = maxOf(jump, abs(new[i] - old[i]))
        if (jump > 0.2f) return new
        return FloatArray(8) { i -> old[i] + (new[i] - old[i]) * k }
    }

    /** Storleken (bredd, höjd) på det rätade papperet, högst [maxLong] bildpunkter på längden. */
    fun outputSize(q: FloatArray, imageW: Int, imageH: Int, maxLong: Int): Pair<Int, Int> {
        fun dist(a: Int, b: Int) = hypot((q[b * 2] - q[a * 2]) * imageW, (q[b * 2 + 1] - q[a * 2 + 1]) * imageH)
        val w = maxOf(dist(0, 1), dist(3, 2))
        val h = maxOf(dist(0, 3), dist(1, 2))
        val scale = minOf(1f, maxLong / maxOf(w, h))
        return (w * scale).roundToInt().coerceAtLeast(16) to (h * scale).roundToInt().coerceAtLeast(16)
    }

    /**
     * Jämnar ut belysningen i ett rätat papper ([px] ARGB, [w] × [h], ändras på plats): papperets
     * ljusstyrka skattas i block (det ljusaste i varje block, utspätt och utjämnat) och varje punkt delas
     * med den – skuggor och ojämnt ljus försvinner, papperet blir vitt. [color] = behåll färgerna, annars
     * gråskala.
     */
    fun enhance(px: IntArray, w: Int, h: Int, color: Boolean) {
        val lum = luminance(px)
        val bs = maxOf(8, minOf(w, h) / 40)
        val gw = (w + bs - 1) / bs
        val gh = (h + bs - 1) / bs
        val grid = IntArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var m = 0
            val y1 = minOf(h, (gy + 1) * bs)
            val x1 = minOf(w, (gx + 1) * bs)
            var y = gy * bs
            while (y < y1) {
                var x = gx * bs
                while (x < x1) {
                    val v = lum[y * w + x]
                    if (v > m) m = v
                    x += 2
                }
                y += 2
            }
            grid[gy * gw + gx] = m
        }
        // Utspädning (största i grannskapet) så att textblock inte blir "mörkt papper", sedan utjämning
        var g = IntArray(grid.size) { i ->
            val gx = i % gw
            val gy = i / gw
            var m = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = gx + dx
                val yy = gy + dy
                if (xx in 0 until gw && yy in 0 until gh) m = maxOf(m, grid[yy * gw + xx])
            }
            m
        }
        repeat(2) { g = box3(g, gw, gh) }
        val lo = 60f
        val hi = 232f
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / bs - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = minOf(gh - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / bs - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = minOf(gw - 1, x0 + 1)
                val tx = fx - x0
                val top = g[y0 * gw + x0] * (1 - tx) + g[y0 * gw + x1] * tx
                val bottom = g[y1 * gw + x0] * (1 - tx) + g[y1 * gw + x1] * tx
                val bg = maxOf(24f, top * (1 - ty) + bottom * ty)
                val i = y * w + x
                if (color) {
                    val c = px[i]
                    val r = curve(((c shr 16) and 0xFF) * 255f / bg, lo, hi)
                    val gg = curve(((c shr 8) and 0xFF) * 255f / bg, lo, hi)
                    val b = curve((c and 0xFF) * 255f / bg, lo, hi)
                    px[i] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
                } else {
                    val v = curve(lum[i] * 255f / bg, lo, hi)
                    px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }
    }

    /** Sträcker [lo]–[hi] till 0–255 och gör mörka partier lite mörkare (text blir tydlig). */
    private fun curve(v: Float, lo: Float, hi: Float): Int {
        val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        return (t.pow(1.3f) * 255f).roundToInt()
    }
}
