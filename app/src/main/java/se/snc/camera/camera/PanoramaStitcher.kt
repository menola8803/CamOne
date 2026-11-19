package se.snc.camera.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/*
 * Panoramats hopsömnad (ren Kotlin, provkörs lokalt med syntetiska bilder).
 *
 * Varje bild (stående, ARGB) projiceras på en cylinder runt kameran. En vridning åt sidan blir då en
 * ren förskjutning i sidled och en lutning en förskjutning i höjdled. Gyrot ger en första gissning av
 * förskjutningen; den finjusteras genom att jämföra överlappet med föregående bild (ljusstyrka, tre
 * nivåer: 1/4, 1/2 och hel upplösning). Bilden läggs in i panoramat med en mjuk skarv mitt i
 * överlappet. Panoreras det åt vänster speglas bilderna, så att sömnaden alltid går åt höger, och
 * resultatet speglas tillbaka.
 */
class PanoramaStitcher(
    val frameW: Int,
    val frameH: Int,
    /** Brännvidden i bildpunkter för de stående bilderna. */
    val focal: Float,
    maxCanvasW: Int = 10_000
) {
    /** Bredden på en bild efter cylinderprojektionen. */
    val warpW: Int = (2f * focal * atan(frameW / (2f * focal))).roundToInt().coerceAtLeast(16)

    private val marginY = frameH / 5
    val canvasH = frameH + 2 * marginY
    val canvasW: Int = min(maxCanvasW, (2 * PI * focal).toInt() + warpW + 16)

    // Cylinderprojektionen per kolumn: var i bilden kolumnen hämtas och hur mycket höjden sträcks
    private val srcX = FloatArray(warpW)
    private val invCos = FloatArray(warpW)

    /** Hur många rader upptill och nedtill som saknar bild längst ut i kanterna (cylinderns krökning). */
    val validInset: Int

    init {
        for (u in 0 until warpW) {
            val theta = (u + 0.5f - warpW / 2f) / focal
            srcX[u] = focal * tan(theta) + frameW / 2f - 0.5f
            invCos[u] = 1f / cos(theta)
        }
        val thetaMax = (warpW / 2f) / focal
        validInset = ceil(frameH / 2f * (1f - cos(thetaMax))).toInt() + 1
    }

    private var canvas: IntArray? = null
    private var mirror = false
    private var pending: IntArray? = null
    private var pendingYaw = 0f
    private var pendingPitch = 0f

    private var prev: Warped? = null
    private var prevX = 0
    private var prevY = 0
    private var prevYaw = 0f
    private var prevPitch = 0f
    private var minY = Int.MAX_VALUE
    private var maxY = Int.MIN_VALUE

    /** Högerkanten på det som hittills är ihopsytt (bildpunkter). */
    var rightEdge = 0
        private set

    /** Antal bilder i panoramat. */
    var frames = 0
        private set

    /** Panoramat går åt vänster (bilderna speglas under sömnaden). */
    val leftwards get() = mirror

    /** Förskjutningarna (dx, dy) mellan bilderna som justeringen valde – för loggen och provkörningen. */
    val offsets = mutableListOf<Pair<Int, Int>>()

    /** Hur många av justeringarna som litade på gyrot (för lite mönster i överlappet). */
    var gyroOnly = 0
        private set

    /** Hur många grader panoramat täcker. */
    val degrees: Float get() = if (frames == 0) 0f else (rightEdge / focal * 180f / PI.toFloat())

    /** Kan inte ta fler bilder (hela varvet eller panoramats största bredd). */
    val full get() = rightEdge + warpW / 3 >= canvasW || rightEdge >= (2 * PI * focal).toInt()

    /** En bild efter cylinderprojektionen, med ljusstyrka i tre upplösningar för justeringen. */
    private class Warped(val argb: IntArray, val w: Int, val h: Int) {
        val l1: FloatArray = luma(argb)
        val w2 = w / 2
        val h2 = h / 2
        val l2: FloatArray = downscale(l1, w, h, w2, h2)
        val w4 = w2 / 2
        val h4 = h2 / 2
        val l4: FloatArray = downscale(l2, w2, h2, w4, h4)

        companion object {
            /** Ljusstyrka 0–255, eller -1 där bilden saknas. */
            fun luma(px: IntArray): FloatArray = FloatArray(px.size) { i ->
                val c = px[i]
                if (c ushr 24 == 0) -1f
                else 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }

            /** Halverar (medel av 2 × 2, saknas om någon saknas). */
            fun downscale(src: FloatArray, w: Int, h: Int, dw: Int, dh: Int): FloatArray {
                val out = FloatArray(dw * dh)
                for (y in 0 until dh) {
                    val r0 = (2 * y) * w
                    val r1 = (2 * y + 1).coerceAtMost(h - 1) * w
                    for (x in 0 until dw) {
                        val x0 = 2 * x
                        val x1 = (2 * x + 1).coerceAtMost(w - 1)
                        val a = src[r0 + x0]
                        val b = src[r0 + x1]
                        val c = src[r1 + x0]
                        val d = src[r1 + x1]
                        out[y * dw + x] = if (a < 0 || b < 0 || c < 0 || d < 0) -1f else (a + b + c + d) * 0.25f
                    }
                }
                return out
            }
        }
    }

    /**
     * Lägger till en stående bild ([frameW] × [frameH], ARGB) tagen vid vridningen [yaw] och lutningen
     * [pitch] (radianer från gyrot; åt höger och uppåt positivt). Bildarrayen får inte ändras efteråt.
     * False om panoramat är fullt.
     */
    fun add(frame: IntArray, yaw: Float, pitch: Float): Boolean {
        require(frame.size == frameW * frameH) { "fel storlek på bilden" }
        if (frames == 0 && pending == null) {
            // Riktningen är inte känd förrän nästa bild: den första väntar
            pending = frame
            pendingYaw = yaw
            pendingPitch = pitch
            frames = 1
            rightEdge = warpW
            return true
        }
        val first = pending
        if (first != null) {
            pending = null
            mirror = yaw < pendingYaw
            val w = warp(if (mirror) flip(first) else first)
            val c = IntArray(canvasW * canvasH).also { canvas = it }
            prevX = 0
            prevY = marginY
            blit(c, w, prevX, prevY, seamFrom = Int.MIN_VALUE, band = 0)
            prev = w
            prevYaw = pendingYaw
            prevPitch = pendingPitch
            minY = prevY
            maxY = prevY
            rightEdge = warpW
        }
        if (full) return false
        val c = canvas ?: return false
        val p = prev ?: return false
        val cur = warp(if (mirror) flip(frame) else frame)
        val dir = if (mirror) -1f else 1f
        val guessDx = ((yaw - prevYaw) * dir * focal).roundToInt().coerceIn(warpW / 20, warpW - warpW / 20)
        val guessDy = (-(pitch - prevPitch) * focal).roundToInt().coerceIn(-frameH / 6, frameH / 6)
        val (dx, dy) = align(p, cur, guessDx, guessDy)
        val nx = prevX + dx
        val ny = (prevY + dy).coerceIn(0, canvasH - frameH)
        if (nx + warpW > canvasW) return false
        // Skarven mitt i överlappet, med en mjuk övergång
        val overlapEnd = prevX + warpW
        val seam = (nx + overlapEnd) / 2
        val band = min(96, max(8, (overlapEnd - nx) / 2))
        blit(c, cur, nx, ny, seamFrom = seam - band / 2, band = band)
        offsets += dx to dy
        prev = cur
        prevX = nx
        prevY = ny
        prevYaw = yaw
        prevPitch = pitch
        minY = min(minY, ny)
        maxY = max(maxY, ny)
        rightEdge = nx + warpW
        frames++
        return true
    }

    /** Spegelvänd bild. */
    private fun flip(src: IntArray): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until frameH) {
            val r = y * frameW
            for (x in 0 until frameW) out[r + x] = src[r + frameW - 1 - x]
        }
        return out
    }

    /** Cylinderprojektion med bilinjär interpolering. Bildpunkter utanför bilden blir genomskinliga. */
    private fun warp(src: IntArray): Warped {
        val out = IntArray(warpW * frameH)
        val cy = frameH / 2f - 0.5f
        for (u in 0 until warpW) {
            val sx = srcX[u]
            if (sx < 0f || sx > frameW - 1f) continue
            val x0 = floor(sx).toInt().coerceAtMost(frameW - 2)
            val fx = sx - x0
            val k = invCos[u]
            for (v in 0 until frameH) {
                val sy = (v - cy) * k + cy
                if (sy < 0f || sy > frameH - 1f) continue
                val y0 = floor(sy).toInt().coerceAtMost(frameH - 2)
                val fy = sy - y0
                val i = y0 * frameW + x0
                out[v * warpW + u] = bilinear(src[i], src[i + 1], src[i + frameW], src[i + frameW + 1], fx, fy)
            }
        }
        return Warped(out, warpW, frameH)
    }

    private fun bilinear(a: Int, b: Int, c: Int, d: Int, fx: Float, fy: Float): Int {
        fun ch(s: Int): Int {
            val top = ((a shr s) and 0xFF) * (1 - fx) + ((b shr s) and 0xFF) * fx
            val bot = ((c shr s) and 0xFF) * (1 - fx) + ((d shr s) and 0xFF) * fx
            return (top * (1 - fy) + bot * fy + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Medelavvikelsen i ljusstyrka när [cur] läggs [sx], [sy] bildpunkter in i [prev] (bara där båda
     * har bild), var [step]:e bildpunkt. Null om överlappet är för litet.
     */
    private fun cost(prev: FloatArray, cur: FloatArray, w: Int, h: Int, sx: Int, sy: Int, step: Int, minCount: Int): Float? {
        var sum = 0f
        var n = 0
        val x0 = max(0, -sx)
        val x1 = min(w, w - sx)
        val y0 = max(0, -sy)
        val y1 = min(h, h - sy)
        var y = y0
        while (y < y1) {
            val rc = y * w
            val rp = (y + sy) * w + sx
            var x = x0
            while (x < x1) {
                val a = cur[rc + x]
                if (a >= 0f) {
                    val b = prev[rp + x]
                    if (b >= 0f) {
                        sum += abs(a - b)
                        n++
                    }
                }
                x += step
            }
            y += step
        }
        return if (n >= minCount) sum / n else null
    }

    /** Spridningen i ljusstyrka i överlappet (för lite mönster = lita på gyrot). */
    private fun texture(cur: FloatArray, w: Int, h: Int, fromX: Int): Float {
        var s = 0f
        var s2 = 0f
        var n = 0
        for (y in 0 until h step 2) for (x in max(0, fromX) until w step 2) {
            val v = cur[y * w + x]
            if (v >= 0f) {
                s += v
                s2 += v * v
                n++
            }
        }
        if (n < 50) return 0f
        val m = s / n
        return kotlin.math.sqrt(max(0f, s2 / n - m * m))
    }

    /** Förskjutningen (dx, dy) i hel upplösning mellan [p] och [c], med gyrots gissning som start. */
    private fun align(p: Warped, c: Warped, guessDx: Int, guessDy: Int): Pair<Int, Int> {
        // Överlappet i den nya bilden är dess vänstra del; för lite mönster där = lita på gyrot
        if (texture(c.l4, c.w4, c.h4, 0) < 4f) {
            gyroOnly++
            return guessDx to guessDy
        }
        // 1/4: sök brett runt gissningen
        val rx = max(6, (0.14f * c.w4).toInt())
        val ry = max(4, (0.07f * c.h4).toInt())
        val gx4 = guessDx / 4
        val gy4 = guessDy / 4
        var best = Float.MAX_VALUE
        var bx = gx4
        var by = gy4
        val minCount4 = (c.w4 * c.h4) / 40
        for (sy in gy4 - ry..gy4 + ry) for (sx in gx4 - rx..gx4 + rx) {
            if (sx <= 0 || sx >= c.w4) continue
            val v = cost(p.l4, c.l4, c.w4, c.h4, sx, sy, 2, minCount4 / 4) ?: continue
            if (v < best) {
                best = v
                bx = sx
                by = sy
            }
        }
        if (best == Float.MAX_VALUE) {
            gyroOnly++
            return guessDx to guessDy
        }
        // 1/2 och hel upplösning: finjustera
        var b2x = bx * 2
        var b2y = by * 2
        best = Float.MAX_VALUE
        val minCount2 = (c.w2 * c.h2) / 160
        for (sy in by * 2 - 2..by * 2 + 2) for (sx in bx * 2 - 2..bx * 2 + 2) {
            if (sx <= 0 || sx >= c.w2) continue
            val v = cost(p.l2, c.l2, c.w2, c.h2, sx, sy, 2, minCount2) ?: continue
            if (v < best) {
                best = v
                b2x = sx
                b2y = sy
            }
        }
        var fx = b2x * 2
        var fy = b2y * 2
        best = Float.MAX_VALUE
        val minCount1 = (c.w * c.h) / 640
        for (sy in b2y * 2 - 2..b2y * 2 + 2) for (sx in b2x * 2 - 2..b2x * 2 + 2) {
            if (sx <= 0 || sx >= c.w) continue
            val v = cost(p.l1, c.l1, c.w, c.h, sx, sy, 3, minCount1) ?: continue
            if (v < best) {
                best = v
                fx = sx
                fy = sy
            }
        }
        return fx.coerceIn(warpW / 20, warpW - warpW / 20) to fy
    }

    /**
     * Lägger in [w] i panoramat vid ([x], [y]). Till vänster om skarven fylls bara tomma bildpunkter,
     * i övergången ([band] bildpunkter från [seamFrom]) blandas bilderna, och till höger tar den nya över.
     */
    private fun blit(c: IntArray, w: Warped, x: Int, y: Int, seamFrom: Int, band: Int) {
        val src = w.argb
        for (v in 0 until w.h) {
            val cyRow = y + v
            if (cyRow < 0 || cyRow >= canvasH) continue
            val rowC = cyRow * canvasW
            val rowS = v * w.w
            for (u in 0 until w.w) {
                val cx = x + u
                if (cx < 0 || cx >= canvasW) continue
                val s = src[rowS + u]
                if (s ushr 24 == 0) continue
                val i = rowC + cx
                val d = c[i]
                if (d ushr 24 == 0) {
                    c[i] = s
                    continue
                }
                if (cx < seamFrom) continue
                if (band <= 0 || cx >= seamFrom + band) {
                    c[i] = s
                    continue
                }
                val t = (cx - seamFrom + 0.5f) / band
                c[i] = mix(d, s, t)
            }
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val it = 1f - t
        val r = (((a shr 16) and 0xFF) * it + ((b shr 16) and 0xFF) * t).toInt()
        val g = (((a shr 8) and 0xFF) * it + ((b shr 8) and 0xFF) * t).toInt()
        val bl = ((a and 0xFF) * it + (b and 0xFF) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /**
     * Det färdiga panoramat (ARGB, bredd, höjd), beskuret till det område där alla bilder har bild.
     * Null om det är färre än två bilder.
     */
    fun result(): Triple<IntArray, Int, Int>? {
        val c = canvas ?: return null
        if (frames < 2) return null
        var top = maxY + validInset
        var bottom = minY + frameH - validInset
        if (bottom - top < frameH / 3) {
            top = minY
            bottom = maxY + frameH
        }
        val w = rightEdge.coerceAtMost(canvasW)
        val h = bottom - top
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val rowC = (top + y) * canvasW
            val rowO = y * w
            if (mirror) {
                for (x in 0 until w) out[rowO + x] = c[rowC + w - 1 - x]
            } else {
                System.arraycopy(c, rowC, out, rowO, w)
            }
        }
        // Tomma bildpunkter (om bilderna hoppat i höjdled) blir svarta
        for (i in out.indices) if (out[i] ushr 24 == 0) out[i] = 0xFF000000.toInt()
        return Triple(out, w, h)
    }

    /** En liten förhandsvisning av det som hittills är ihopsytt, högst [maxW] × [maxH] bildpunkter. */
    fun preview(maxW: Int, maxH: Int): Triple<IntArray, Int, Int>? {
        val c = canvas ?: return null
        val w = rightEdge.coerceAtMost(canvasW)
        if (w <= 0) return null
        val top = max(0, minY)
        val h = min(canvasH, maxY + frameH) - top
        if (h <= 0) return null
        val scale = max(w.toFloat() / maxW, h.toFloat() / maxH).coerceAtLeast(1f)
        val pw = (w / scale).toInt().coerceAtLeast(1)
        val ph = (h / scale).toInt().coerceAtLeast(1)
        val out = IntArray(pw * ph)
        for (y in 0 until ph) {
            val sy = top + (y * scale).toInt().coerceAtMost(h - 1)
            for (x in 0 until pw) {
                val sx = (x * scale).toInt().coerceAtMost(w - 1)
                val v = c[sy * canvasW + (if (mirror) w - 1 - sx else sx)]
                out[y * pw + (x)] = if (v ushr 24 == 0) 0x00000000 else v
            }
        }
        return Triple(out, pw, ph)
    }
}
