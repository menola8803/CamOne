package se.snc.camera.camera

import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * HDR: 3–5 bilder med olika exponering läggs ihop till en bild där både ljusa och mörka partier
 * syns. Ren Kotlin utan Android (provkörs på en dator), samma YUV-bilder som nattläget.
 *
 * 1. Den första bilden (vald exponering) är referens. Ljusskillnaden mot de andra mäts i bilderna
 *    själva, och varje bild räknas om till referensens ljus så att den kan flyttas på plats med
 *    samma flytt som nattläget (FrameAligner).
 * 2. Varje bild får vikt efter hur väl exponerad den är i området (mjukt, ca 32–100 pixlar),
 *    utfrätta pixlar får ingen vikt, och där en bild inte stämmer med referensen (något har
 *    rört sig) används referensen – inga spökbilder.
 * 3. Bilderna blandas med vikterna (exponeringsfusion) och får lite kontrast.
 * 4. Ultra HDR: för varje 2 × 2-block räknas hur mycket ljusare det verkliga ljuset är än den
 *    sparade bilden (högst 4×). Det blir en gain map, så att HDR-skärmar visar ljusa partier ljusare.
 */

/** HDR-bilden och (för Ultra HDR) gain map i halv upplösning (ett värde per 2 × 2-block, 0–255). */
class HdrResult(val frame: YuvFrame, val gain: ByteArray?, val ratios: FloatArray)

object HdrMerge {
    /** Hur mycket ljusare än vitt i bilden en HDR-skärm får visa de ljusaste partierna. */
    const val MAX_BOOST = 4f
    private const val SIGMA = 0.2f

    /** Kamerans ljushet (0–255) till linjärt ljus (gamma 2,2). */
    val LIN = FloatArray(256) { (it / 255f).pow(2.2f) }

    /** Räknar om en bild med ljusskillnaden [ratio] mot referensen till referensens ljus. */
    fun mapLut(ratio: Float): IntArray = IntArray(256) { y ->
        (255f * (LIN[y] / ratio).coerceIn(0f, 1f).pow(1f / 2.2f)).roundToInt().coerceIn(0, 255)
    }

    /**
     * Ljusare skuggor och lite kontrast i ljusare partier: x + 0,35·x(1 − x)² + 0,12·x(1 − x)(2x − 1).
     * Svart och vitt ändras inte.
     */
    fun toneLut(): IntArray = IntArray(256) { i ->
        val x = i / 255f
        val y = x + 0.35f * x * (1 - x) * (1 - x) + 0.12f * x * (1 - x) * (2 * x - 1)
        (y * 255f).roundToInt().coerceIn(0, 255)
    }

    /**
     * Ljusskillnaden mellan [f] och [ref] (linjärt ljus, t.ex. 0,25 för −2 EV), mätt där båda är
     * väl exponerade. Med [off] jämförs flyttade punkter, annars samma punkter.
     */
    fun ratioTo(ref: YuvFrame, f: YuvFrame, fallback: Float, off: Offsets?): Float {
        var si = 0.0
        var sr = 0.0
        var n = 0
        val w = ref.width
        val h = ref.height
        var by = 1
        while (by < ref.ch - 1) {
            var bx = 1
            while (bx < ref.cw - 1) {
                val x = 2 * bx
                val y = 2 * by
                var dx = 0
                var dy = 0
                if (off != null) {
                    val c = off.cellOf(bx, by)
                    dx = off.dx[c]
                    dy = off.dy[c]
                }
                val sx = x + dx
                val sy = y + dy
                if (sx in 0 until w && sy in 0 until h) {
                    val a = ref.y[y * w + x].toInt() and 0xFF
                    val b = f.y[sy * w + sx].toInt() and 0xFF
                    if (a in 16..235 && b in 16..235) {
                        si += LIN[b]
                        sr += LIN[a]
                        n++
                    }
                }
                bx += 3
            }
            by += 3
        }
        if (n < 300 || sr <= 0.0) return fallback
        return (si / sr).toFloat().coerceIn(1f / 64f, 64f)
    }

    /**
     * Lägger ihop [frames] (den första är referensen). [nominal] är den väntade ljusskillnaden för
     * varje bild (2^EV), som används om den inte går att mäta. [wantGain] = räkna fram gain map.
     */
    fun merge(
        pool: ExecutorService,
        parts: Int,
        frames: List<YuvFrame>,
        nominal: FloatArray,
        wantGain: Boolean
    ): HdrResult {
        val ref = frames[0]
        val w = ref.width
        val h = ref.height
        val cw = w / 2
        val ch = h / 2
        val n = frames.size
        val refPyr = Pyramid.of(ref.view(), pool, parts)
        val aligner = FrameAligner(pool, parts, ref, refPyr)
        val offsets = Array(n) { aligner.zero() }
        val ratios = FloatArray(n) { 1f }
        val luts = arrayOfNulls<IntArray>(n)
        luts[0] = IntArray(256) { it }

        // 1. Ljusskillnad och flytt för varje bild (två varv: grov skillnad → flytt → exakt skillnad)
        for (i in 1 until n) {
            val f = frames[i]
            if (f.width != w || f.height != h) continue
            var r = ratioTo(ref, f, nominal[i], null)
            var lut = mapLut(r)
            val pyr = Pyramid.of(f.view(), pool, parts, lut)
            val off = aligner.align(f.view(), pyr, lut)
            r = ratioTo(ref, f, r, off)
            lut = mapLut(r)
            ratios[i] = r
            luts[i] = lut
            offsets[i] = off
        }
        val usable = (0 until n).filter { frames[it].width == w && frames[it].height == h }
        val darkest = usable.minBy { ratios[it] }

        // 2. Hur väl exponerad varje bild är, ruta för ruta (32 × 32 pixlar), utjämnat
        val cellsX = aligner.cellsX
        val cellsY = aligner.cellsY
        val cb = FrameAligner.CELL_BLOCKS
        val we = Array(n) { FloatArray(cellsX * cellsY) { 1e-4f } }
        for (i in usable) {
            val f = frames[i]
            val off = offsets[i]
            val wi = we[i]
            pool.parallelRanges(cellsY, parts) { from, to ->
                for (cy in from until to) {
                    for (cx in 0 until cellsX) {
                        val c = cy * cellsX + cx
                        val dx = off.dx[c]
                        val dy = off.dy[c]
                        var sum = 0L
                        var cnt = 0
                        var by = cy * cb
                        while (by < min(ch, cy * cb + cb)) {
                            var bx = cx * cb
                            while (bx < min(cw, cx * cb + cb)) {
                                val sx = 2 * bx + dx
                                val sy = 2 * by + dy
                                if (sx in 0 until w && sy in 0 until h) {
                                    sum += f.y[sy * w + sx].toInt() and 0xFF
                                    cnt++
                                }
                                bx += 2
                            }
                            by += 2
                        }
                        if (cnt > 0) {
                            val d = sum.toFloat() / cnt / 255f - 0.5f
                            wi[c] = exp(-(d * d) / (2 * SIGMA * SIGMA)) + 1e-3f
                        }
                    }
                }
            }
            we[i] = blurCells(blurCells(wi, cellsX, cellsY), cellsX, cellsY)
        }

        // 3. Blanda block för block
        val out = YuvFrame.allocate(w, h)
        val gain = if (wantGain) ByteArray(cw * ch) else null
        val tone = toneLut()
        val logMax = ln(MAX_BOOST)
        val minSdr = 1f / 255f
        pool.parallelRanges(ch, parts) { from, to ->
            val p = IntArray(4 * n)
            val uu = IntArray(n)
            val vv = IntArray(n)
            val avg = IntArray(n)
            val wts = FloatArray(n)
            val inb = BooleanArray(n)
            val outY = IntArray(4)
            val acc = FloatArray(4)
            for (by in from until to) {
                val fy = (by + 0.5f) / cb - 0.5f
                val cy0 = floor(fy).toInt().coerceIn(0, cellsY - 1)
                val cy1 = min(cy0 + 1, cellsY - 1)
                val ay = (fy - cy0).coerceIn(0f, 1f)
                for (bx in 0 until cw) {
                    val fx = (bx + 0.5f) / cb - 0.5f
                    val cx0 = floor(fx).toInt().coerceIn(0, cellsX - 1)
                    val cx1 = min(cx0 + 1, cellsX - 1)
                    val ax = (fx - cx0).coerceIn(0f, 1f)
                    val cell = (by / cb) * cellsX + bx / cb
                    for (i in 0 until n) {
                        wts[i] = 0f
                        inb[i] = false
                        if (i !in usable) continue
                        val f = frames[i]
                        val off = offsets[i]
                        val dx = off.dx[cell]
                        val dy = off.dy[cell]
                        val sx = 2 * bx + dx
                        val sy = 2 * by + dy
                        if (sx < 0 || sy < 0 || sx + 1 >= w || sy + 1 >= h) continue
                        inb[i] = true
                        val o = sy * w + sx
                        val a = f.y[o].toInt() and 0xFF
                        val b = f.y[o + 1].toInt() and 0xFF
                        val c = f.y[o + w].toInt() and 0xFF
                        val d = f.y[o + w + 1].toInt() and 0xFF
                        p[4 * i] = a
                        p[4 * i + 1] = b
                        p[4 * i + 2] = c
                        p[4 * i + 3] = d
                        avg[i] = (a + b + c + d + 2) shr 2
                        val mx = max(max(a, b), max(c, d))
                        val ux = (bx + Math.floorDiv(dx + 1, 2)).coerceIn(0, cw - 1)
                        val uy = (by + Math.floorDiv(dy + 1, 2)).coerceIn(0, ch - 1)
                        uu[i] = f.u[uy * cw + ux].toInt() and 0xFF
                        vv[i] = f.v[uy * cw + ux].toInt() and 0xFF
                        val wi = we[i]
                        val e = (wi[cy0 * cellsX + cx0] * (1 - ax) + wi[cy0 * cellsX + cx1] * ax) * (1 - ay) +
                            (wi[cy1 * cellsX + cx0] * (1 - ax) + wi[cy1 * cellsX + cx1] * ax) * ay
                        var clip = ((250 - mx) / 16f).coerceIn(0f, 1f)
                        if (i == darkest) clip = max(clip, 0.05f)
                        wts[i] = e * clip
                    }
                    // Rörelse: stämmer inte bilden med referensen används referensen
                    val refAvg = avg[0]
                    if (refAvg in 12..243) {
                        for (i in 1 until n) {
                            if (wts[i] <= 0f) continue
                            val ai = avg[i]
                            if (ai !in 12..243) continue
                            val pred = luts[i]?.get(ai) ?: ai
                            val diff = abs(pred - refAvg).toFloat()
                            val t = max(12f, 0.15f * refAvg)
                            val g = when {
                                diff <= t -> 1f
                                diff >= 2 * t -> 0f
                                else -> (2 * t - diff) / t
                            }
                            wts[i] *= g
                        }
                    }
                    var ws = 0f
                    for (i in 0 until n) ws += wts[i]
                    if (ws < 1e-6f) {
                        // Allt utfrätt: den mörkaste bilden om den finns här, annars referensen (alltid på plats)
                        for (i in 0 until n) wts[i] = 0f
                        wts[if (inb[darkest]) darkest else 0] = 1f
                        ws = 1f
                    }
                    var su = 0f
                    var sv = 0f
                    var rad = 0f
                    acc[0] = 0f
                    acc[1] = 0f
                    acc[2] = 0f
                    acc[3] = 0f
                    for (i in 0 until n) {
                        val wt = wts[i]
                        if (wt <= 0f) continue
                        acc[0] += wt * p[4 * i]
                        acc[1] += wt * p[4 * i + 1]
                        acc[2] += wt * p[4 * i + 2]
                        acc[3] += wt * p[4 * i + 3]
                        su += wt * uu[i]
                        sv += wt * vv[i]
                        if (gain != null) rad += wt * LIN[avg[i]] / ratios[i]
                    }
                    var toned = 0
                    for (k in 0..3) {
                        val v = tone[(acc[k] / ws).roundToInt().coerceIn(0, 255)]
                        outY[k] = v
                        toned += v
                    }
                    val o = (2 * by) * w + 2 * bx
                    out.y[o] = outY[0].toByte()
                    out.y[o + 1] = outY[1].toByte()
                    out.y[o + w] = outY[2].toByte()
                    out.y[o + w + 1] = outY[3].toByte()
                    val bIdx = by * cw + bx
                    out.u[bIdx] = (su / ws).roundToInt().coerceIn(0, 255).toByte()
                    out.v[bIdx] = (sv / ws).roundToInt().coerceIn(0, 255).toByte()
                    if (gain != null) {
                        val sdr = LIN[((toned + 2) shr 2).coerceIn(0, 255)]
                        val gn = (rad / ws / max(sdr, minSdr)).coerceIn(1f, MAX_BOOST)
                        gain[bIdx] = (255f * ln(gn) / logMax).roundToInt().coerceIn(0, 255).toByte()
                    }
                }
            }
        }
        val smoothGain = gain?.let { blurBytes(it, cw, ch, pool, parts) }
        return HdrResult(out, smoothGain, ratios)
    }

    /** 3 × 3-medel över rutnätet. */
    private fun blurCells(a: FloatArray, cx: Int, cy: Int): FloatArray {
        val out = FloatArray(a.size)
        for (y in 0 until cy) {
            for (x in 0 until cx) {
                var s = 0f
                var n = 0
                for (j in max(0, y - 1)..min(cy - 1, y + 1)) {
                    for (i in max(0, x - 1)..min(cx - 1, x + 1)) {
                        s += a[j * cx + i]
                        n++
                    }
                }
                out[y * cx + x] = s / n
            }
        }
        return out
    }

    /** 3 × 3-medel av en bytebild (gain map), så att den blir jämn. */
    private fun blurBytes(a: ByteArray, w: Int, h: Int, pool: ExecutorService, parts: Int): ByteArray {
        val out = ByteArray(a.size)
        pool.parallelRanges(h, parts) { from, to ->
            for (y in from until to) {
                for (x in 0 until w) {
                    var s = 0
                    var n = 0
                    for (j in max(0, y - 1)..min(h - 1, y + 1)) {
                        val r = j * w
                        for (i in max(0, x - 1)..min(w - 1, x + 1)) {
                            s += a[r + i].toInt() and 0xFF
                            n++
                        }
                    }
                    out[y * w + x] = ((s + n / 2) / n).toByte()
                }
            }
        }
        return out
    }
}
