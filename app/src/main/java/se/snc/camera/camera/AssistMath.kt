package se.snc.camera.camera

import kotlin.math.abs

/**
 * Pro-hjälpmedlen räknade på sökarens ljusstyrka (Y-planet i en liten analysbild, t.ex. 640 × 480):
 * histogram, fokusmarkering (skarpa kanter) och zebra (nästan utfrätt). Ren Kotlin utan Android,
 * så att det kan provköras lokalt.
 */
object AssistMath {
    const val BINS = 64
    /** Zebra från den här ljusstyrkan (0–255, ca 96 %). */
    const val ZEBRA_LEVEL = 245
    /** Lägsta kantstyrka som markeras (andraderivatan i båda riktningarna). */
    const val PEAK_MIN = 24
    /** Kanter markeras när de är så här många gånger starkare än bildens medelvärde. */
    const val PEAK_FACTOR = 3.5f

    const val PEAK_COLOR = 0xFFFF3B30.toInt()
    const val ZEBRA_DARK = 0xB0000000.toInt()
    const val ZEBRA_LIGHT = 0x50FFFFFF
    /** Zebrans ränder är så här breda (bildpunkter i analysbilden). */
    const val ZEBRA_STRIPE = 6

    class Stats {
        var peak = 0
        var zebra = 0
        var total = 0
        var threshold = 0
    }

    /** Bredd och höjd i skärmens riktning efter vridningen. */
    fun outputSize(w: Int, h: Int, rotation: Int): Pair<Int, Int> =
        if (rotation % 180 == 0) w to h else h to w

    /**
     * Var bildpunkten (sx, sy) i analysbilden hamnar i den vridna (och ev. spegelvända) bilden:
     * index = base + sx · ax + sy · ay. [rotation] = ImageInfo.rotationDegrees.
     */
    fun mapping(w: Int, h: Int, rotation: Int, mirror: Boolean): IntArray = when (((rotation % 360) + 360) % 360) {
        90 -> if (!mirror) intArrayOf(h - 1, h, -1) else intArrayOf(0, h, 1)
        180 -> if (!mirror) intArrayOf(w * h - 1, -1, -w) else intArrayOf((h - 1) * w, 1, -w)
        270 -> if (!mirror) intArrayOf((w - 1) * h, -h, 1) else intArrayOf(w * h - 1, -h, -1)
        else -> if (!mirror) intArrayOf(0, 1, w) else intArrayOf(w - 1, -1, w)
    }

    /** Histogram över ljusstyrkan (var annan bildpunkt i båda riktningarna). [out] får [BINS] fack. */
    fun histogram(y: ByteArray, stride: Int, w: Int, h: Int, out: IntArray) {
        out.fill(0)
        var sy = 0
        while (sy < h) {
            val row = sy * stride
            var sx = 0
            while (sx < w) {
                out[(y[row + sx].toInt() and 0xFF) shr 2]++
                sx += 2
            }
            sy += 2
        }
    }

    /**
     * Höjderna 0–1 för histogrammet. Skalan sätts efter det högsta facket utom de yttersta, så att en
     * stor svart eller utfrätt yta inte trycker ihop resten (de visas i stället som klippning).
     */
    fun normalize(counts: IntArray): FloatArray {
        var max = 1
        for (i in 1 until counts.size - 1) if (counts[i] > max) max = counts[i]
        return FloatArray(counts.size) { i -> (counts[i].toFloat() / max).coerceAtMost(1f) }
    }

    /** Andel (0–1) av bildpunkterna i det mörkaste eller ljusaste facket. */
    fun share(counts: IntArray, bin: Int): Float {
        var total = 0
        for (c in counts) total += c
        return if (total == 0) 0f else counts[bin].toFloat() / total
    }

    /** Kantstyrka i en bildpunkt: |andraderivatan| vågrätt + lodrätt. */
    private fun edge(y: ByteArray, i: Int, stride: Int): Int {
        val p = (y[i].toInt() and 0xFF) * 2
        val l = y[i - 1].toInt() and 0xFF
        val r = y[i + 1].toInt() and 0xFF
        val u = y[i - stride].toInt() and 0xFF
        val d = y[i + stride].toInt() and 0xFF
        return abs(p - l - r) + abs(p - u - d)
    }

    /** Tröskeln för fokusmarkeringen: bildens medelkantstyrka (var fjärde bildpunkt) gånger [PEAK_FACTOR]. */
    fun peakThreshold(y: ByteArray, stride: Int, w: Int, h: Int): Int {
        var sum = 0L
        var n = 0
        var sy = 2
        while (sy < h - 2) {
            val row = sy * stride
            var sx = 2
            while (sx < w - 2) {
                sum += edge(y, row + sx, stride)
                n++
                sx += 4
            }
            sy += 4
        }
        val mean = if (n == 0) 0f else sum.toFloat() / n
        return maxOf(PEAK_MIN, (mean * PEAK_FACTOR).toInt())
    }

    /**
     * Ritar fokusmarkering (röda bildpunkter på skarpa kanter) och zebra (sneda ränder där bilden är
     * nästan utfrätt) i [out], som har skärmens riktning (se [outputSize]). Övriga bildpunkter blir
     * genomskinliga. [phase] flyttar zebrans ränder så att de rör sig.
     */
    fun overlay(
        y: ByteArray,
        stride: Int,
        w: Int,
        h: Int,
        rotation: Int,
        mirror: Boolean,
        peaking: Boolean,
        zebra: Boolean,
        phase: Int,
        out: IntArray,
        stats: Stats
    ) {
        out.fill(0)
        val (ow, _) = outputSize(w, h, rotation)
        val m = mapping(w, h, rotation, mirror)
        val base = m[0]
        val ax = m[1]
        val ay = m[2]
        val threshold = if (peaking) peakThreshold(y, stride, w, h) else Int.MAX_VALUE
        var peak = 0
        var bright = 0
        for (sy in 1 until h - 1) {
            val row = sy * stride
            var idx = base + sy * ay + ax
            for (sx in 1 until w - 1) {
                val i = row + sx
                if (zebra && (y[i].toInt() and 0xFF) >= ZEBRA_LEVEL) {
                    val ox = idx % ow
                    val oy = idx / ow
                    out[idx] = if (((ox + oy + phase) / ZEBRA_STRIPE) and 1 == 0) ZEBRA_DARK else ZEBRA_LIGHT
                    bright++
                }
                if (peaking && edge(y, i, stride) > threshold) {
                    out[idx] = PEAK_COLOR
                    peak++
                }
                idx += ax
            }
        }
        stats.peak = peak
        stats.zebra = bright
        stats.total = (w - 2) * (h - 2)
        stats.threshold = if (peaking) threshold else 0
    }
}
