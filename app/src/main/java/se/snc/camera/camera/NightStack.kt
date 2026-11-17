package se.snc.camera.camera

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Nattfoto och lång exponering: flera bilder från kameran läggs ihop till en.
 *
 * Allt här är ren Kotlin utan Android, så att det kan provköras på en dator.
 * Bilderna är YUV 4:2:0 (Y i full upplösning, U och V i halv upplösning åt båda hållen), i
 * kamerans egen riktning. Rotation, beskärning och sparande görs i NightCapture.kt.
 *
 * - [NightMerger]: 8–16 bilder. Den skarpaste av de första bilderna blir referens, de andra flyttas
 *   på plats (först hela bilden, sedan 8 × 6 rutor så att även lite vridning följer med) och läggs
 *   ihop. Det som har rört sig mellan bilderna (människor, bilar) tas bara från referensen, så
 *   inga spökbilder uppstår. Brus minskar ungefär med roten ur antalet bilder.
 * - [LongExposure]: alla bilder under 1–8 sekunder läggs ihop utan flytt (telefonen ska stå
 *   stilla). Silkesvatten = medelvärdet. Ljusspår = medelvärdet, men där något ljust har passerat
 *   används det ljusaste värdet, så att lampor som rör sig ritar streck.
 */

/** Hur flera bilder läggs ihop i nattläget. */
enum class StackStyle { NIGHT, TRAILS, SILK }

/** En YUV 4:2:0-bild med tätt packade plan: y är width × height, u och v är (width/2) × (height/2). */
class YuvFrame(val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
    val cw: Int get() = width / 2
    val ch: Int get() = height / 2

    fun view(): FrameView = FrameView(width, height, y, width, u, v, width / 2, 1)

    companion object {
        fun allocate(width: Int, height: Int): YuvFrame {
            val c = (width / 2) * (height / 2)
            return YuvFrame(width, height, ByteArray(width * height), ByteArray(c), ByteArray(c))
        }
    }
}

/**
 * En bild som den kommer från kameran: planen med radlängd (row stride) och, för U och V,
 * pixelavstånd (1 = separata plan, 2 = sammanflätade). width och height är jämna.
 */
class FrameView(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yRow: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvRow: Int,
    val uvPix: Int
) {
    val cw: Int get() = width / 2
    val ch: Int get() = height / 2

    /** Kopierar till en tätt packad bild (bilden från kameran återanvänds för nästa bildruta). */
    fun copy(): YuvFrame {
        val out = YuvFrame.allocate(width, height)
        for (r in 0 until height) System.arraycopy(y, r * yRow, out.y, r * width, width)
        val cw = cw
        for (r in 0 until ch) {
            val src = r * uvRow
            val dst = r * cw
            if (uvPix == 1) {
                System.arraycopy(u, src, out.u, dst, cw)
                System.arraycopy(v, src, out.v, dst, cw)
            } else {
                for (c in 0 until cw) {
                    out.u[dst + c] = u[src + c * uvPix]
                    out.v[dst + c] = v[src + c * uvPix]
                }
            }
        }
        return out
    }
}

/** Kör [body] på delar av 0 until [count] parallellt i [this] och väntar tills allt är klart. */
internal fun ExecutorService.parallelRanges(count: Int, parts: Int, body: (from: Int, to: Int) -> Unit) {
    if (count <= 0) return
    val n = max(1, min(parts, count))
    if (n == 1) {
        body(0, count)
        return
    }
    val step = (count + n - 1) / n
    val futures = (0 until n).mapNotNull { i ->
        val from = i * step
        val to = min(count, from + step)
        if (from >= to) null else submit(Callable { body(from, to) })
    }
    futures.forEach { it.get() }
}

/** Nedskalad ljushet (0–255 som osignerade byte). */
class Lum(val w: Int, val h: Int, val px: ByteArray) {
    fun at(x: Int, y: Int): Int = px[y * w + x].toInt() and 0xFF

    /** Halva storleken (medel av 2 × 2). */
    fun half(): Lum {
        val hw = max(1, w / 2)
        val hh = max(1, h / 2)
        val out = ByteArray(hw * hh)
        for (yy in 0 until hh) {
            val r0 = (2 * yy) * w
            val r1 = min(2 * yy + 1, h - 1) * w
            for (xx in 0 until hw) {
                val x0 = 2 * xx
                val x1 = min(x0 + 1, w - 1)
                val s = (px[r0 + x0].toInt() and 0xFF) + (px[r0 + x1].toInt() and 0xFF) +
                    (px[r1 + x0].toInt() and 0xFF) + (px[r1 + x1].toInt() and 0xFF)
                out[yy * hw + xx] = ((s + 2) shr 2).toByte()
            }
        }
        return Lum(hw, hh, out)
    }

    /** Skärpa: summan av skillnaden mellan grannpunkter (högre = skarpare). */
    fun sharpness(): Long {
        var s = 0L
        for (yy in 0 until h - 1) {
            val r = yy * w
            for (xx in 0 until w - 1) {
                val c = px[r + xx].toInt() and 0xFF
                s += abs(c - (px[r + xx + 1].toInt() and 0xFF)) + abs(c - (px[r + w + xx].toInt() and 0xFF))
            }
        }
        return s
    }

    /** Medelskillnad mellan grannpunkter i ett område – låg i himmel och mörker (ingen struktur). */
    fun texture(x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var s = 0L
        var n = 0
        for (yy in max(0, y0) until min(h - 1, y1)) {
            val r = yy * w
            for (xx in max(0, x0) until min(w - 1, x1)) {
                val c = px[r + xx].toInt() and 0xFF
                s += abs(c - (px[r + xx + 1].toInt() and 0xFF)) + abs(c - (px[r + w + xx].toInt() and 0xFF))
                n++
            }
        }
        return if (n == 0) 0f else s.toFloat() / (2 * n)
    }

    companion object {
        /** Halv upplösning direkt från kamerans Y-plan (medel av 2 × 2). */
        fun halfOf(f: FrameView, pool: ExecutorService, parts: Int): Lum {
            val hw = f.width / 2
            val hh = f.height / 2
            val out = ByteArray(hw * hh)
            val y = f.y
            val row = f.yRow
            pool.parallelRanges(hh, parts) { from, to ->
                for (yy in from until to) {
                    val r0 = (2 * yy) * row
                    val r1 = r0 + row
                    val o = yy * hw
                    for (xx in 0 until hw) {
                        val x0 = 2 * xx
                        val s = (y[r0 + x0].toInt() and 0xFF) + (y[r0 + x0 + 1].toInt() and 0xFF) +
                            (y[r1 + x0].toInt() and 0xFF) + (y[r1 + x0 + 1].toInt() and 0xFF)
                        out[o + xx] = ((s + 2) shr 2).toByte()
                    }
                }
            }
            return Lum(hw, hh, out)
        }
    }
}

/** Ljushet i fyra nivåer: 1/2, 1/4, 1/8 och 1/16 av full upplösning. */
class Pyramid(val levels: List<Lum>) {
    val l1: Lum get() = levels[0]
    val l2: Lum get() = levels[1]
    val l3: Lum get() = levels[2]
    val l4: Lum get() = levels[3]

    companion object {
        fun of(f: FrameView, pool: ExecutorService, parts: Int): Pyramid {
            val l1 = Lum.halfOf(f, pool, parts)
            val l2 = l1.half()
            val l3 = l2.half()
            val l4 = l3.half()
            return Pyramid(listOf(l1, l2, l3, l4))
        }
    }
}

/** En förflyttning (dx, dy) och hur bra den passar (medelskillnad, lägre är bättre). */
data class Shift(val dx: Int, val dy: Int, val cost: Float)

object Align {
    private const val NONE = Float.MAX_VALUE

    /**
     * Medelskillnad mellan [a] (referensen) och [b] flyttad med (dx, dy): a(x, y) jämförs med
     * b(x + dx, y + dy) i området [x0, x1) × [y0, y1), var [step]:e punkt.
     */
    fun mad(a: Lum, b: Lum, x0: Int, y0: Int, x1: Int, y1: Int, dx: Int, dy: Int, step: Int): Float {
        val xs = max(max(x0, 0), -dx)
        val xe = min(min(x1, a.w), b.w - dx)
        val ys = max(max(y0, 0), -dy)
        val ye = min(min(y1, a.h), b.h - dy)
        if (xs >= xe || ys >= ye) return NONE
        val ap = a.px
        val bp = b.px
        val aw = a.w
        val bw = b.w
        var sum = 0L
        var n = 0
        var yy = ys
        while (yy < ye) {
            val ra = yy * aw
            val rb = (yy + dy) * bw + dx
            var xx = xs
            while (xx < xe) {
                sum += abs((ap[ra + xx].toInt() and 0xFF) - (bp[rb + xx].toInt() and 0xFF))
                n++
                xx += step
            }
            yy += step
        }
        // För liten överlappning räknas inte (annars kan en flytt utanför bilden "vinna")
        val full = ((x1 - x0 + step - 1) / step).toLong() * ((y1 - y0 + step - 1) / step)
        if (n == 0 || n * 3L < full) return NONE
        return sum.toFloat() / n
    }

    /** Samma som [mad] men i full upplösning: referensen är tätt packad, [cur] har radlängd. */
    fun madFull(ref: YuvFrame, cur: FrameView, x0: Int, y0: Int, x1: Int, y1: Int, dx: Int, dy: Int, step: Int): Float {
        val xs = max(max(x0, 0), -dx)
        val xe = min(min(x1, ref.width), cur.width - dx)
        val ys = max(max(y0, 0), -dy)
        val ye = min(min(y1, ref.height), cur.height - dy)
        if (xs >= xe || ys >= ye) return NONE
        val ap = ref.y
        val bp = cur.y
        val aw = ref.width
        val bw = cur.yRow
        var sum = 0L
        var n = 0
        var yy = ys
        while (yy < ye) {
            val ra = yy * aw
            val rb = (yy + dy) * bw + dx
            var xx = xs
            while (xx < xe) {
                sum += abs((ap[ra + xx].toInt() and 0xFF) - (bp[rb + xx].toInt() and 0xFF))
                n++
                xx += step
            }
            yy += step
        }
        val full = ((x1 - x0 + step - 1) / step).toLong() * ((y1 - y0 + step - 1) / step)
        if (n == 0 || n * 3L < full) return NONE
        return sum.toFloat() / n
    }

    /** Bästa flytt inom ±[radius] runt (cx, cy). Vid lika värde vinner den som ligger närmast mitten. */
    inline fun search(cx: Int, cy: Int, radius: Int, cost: (dx: Int, dy: Int) -> Float): Shift {
        var best = Shift(cx, cy, cost(cx, cy))
        for (dy in cy - radius..cy + radius) {
            for (dx in cx - radius..cx + radius) {
                if (dx == cx && dy == cy) continue
                val c = cost(dx, dy)
                if (c < best.cost) best = Shift(dx, dy, c)
            }
        }
        return best
    }
}

/**
 * Lägger ihop 8–16 bilder till ett nattfoto. Anropa [add] för varje bild från kameran tills den
 * returnerar true, sedan [finish]. Inte trådsäker – anropas från en tråd i taget.
 */
class NightMerger(
    private val pool: ExecutorService,
    private val parts: Int,
    val width: Int,
    val height: Int,
    val target: Int
) {
    companion object {
        /** De första bilderna som referensen väljs bland (den skarpaste). */
        const val REF_CANDIDATES = 3
        /** Vikt för en bild som passar helt (0–7). Summan ryms i Short för upp till 18 bilder. */
        const val FULL = 7
        const val MAX_FRAMES = 18
        private const val CELL_BLOCKS = 16 // en flyttruta är 16 × 16 block = 32 × 32 pixlar
        private const val TILES_LONG = 8
        private const val GLOBAL_RADIUS = 8 // ±8 i 1/16 = ±128 pixlar
        private const val TILE_LIMIT = 24 // rutor får avvika högst så här mycket från helbildsflytten
    }

    var accepted = 0
        private set

    private val pending = ArrayList<YuvFrame>()
    private val pendingPyr = ArrayList<Pyramid>()
    private var ref: YuvFrame? = null
    private var refPyr: Pyramid? = null

    private val cw = width / 2
    private val chh = height / 2
    private val ySum = ShortArray(width * height)
    private val uSum = ShortArray(cw * chh)
    private val vSum = ShortArray(cw * chh)
    private val wSum = ByteArray(cw * chh)

    private val longSide = max(width, height)
    private val tilesX = if (width >= height) TILES_LONG else max(2, (TILES_LONG * width + longSide / 2) / longSide)
    private val tilesY = if (height > width) TILES_LONG else max(2, (TILES_LONG * height + longSide / 2) / longSide)
    private val tileW = width / tilesX
    private val tileH = height / tilesY
    private var tileTexture: FloatArray? = null

    private val cellsX = (cw + CELL_BLOCKS - 1) / CELL_BLOCKS
    private val cellsY = (chh + CELL_BLOCKS - 1) / CELL_BLOCKS

    /** Senaste flyttar (för provkörning och logg). */
    var lastGlobal: Shift = Shift(0, 0, 0f)
        private set
    var lastTolerance: Float = 0f
        private set

    /** Lägger till en bild. Returnerar true när [target] bilder är med. */
    fun add(f: FrameView): Boolean {
        if (f.width != width || f.height != height) return accepted >= target
        if (ref == null) {
            val copy = f.copy()
            pending += copy
            pendingPyr += Pyramid.of(copy.view(), pool, parts)
            if (pending.size >= min(REF_CANDIDATES, target)) chooseReference()
        } else {
            merge(f, Pyramid.of(f, pool, parts))
        }
        return accepted >= target
    }

    private fun chooseReference() {
        if (pending.isEmpty()) return
        val best = pendingPyr.indices.maxBy { pendingPyr[it].l2.sharpness() }
        val r = pending[best]
        ref = r
        refPyr = pendingPyr[best]
        // Referensen med full vikt
        pool.parallelRanges(chh, parts) { from, to ->
            for (by in from until to) {
                for (bx in 0 until cw) {
                    val b = by * cw + bx
                    wSum[b] = FULL.toByte()
                    uSum[b] = ((r.u[b].toInt() and 0xFF) * FULL).toShort()
                    vSum[b] = ((r.v[b].toInt() and 0xFF) * FULL).toShort()
                }
                val y0 = 2 * by
                for (yy in y0..y0 + 1) {
                    val o = yy * width
                    for (x in 0 until width) ySum[o + x] = ((r.y[o + x].toInt() and 0xFF) * FULL).toShort()
                }
            }
        }
        val l2 = refPyr!!.l2
        tileTexture = FloatArray(tilesX * tilesY) { i ->
            val tx = i % tilesX
            val ty = i / tilesX
            l2.texture(tx * tileW / 4, ty * tileH / 4, (tx + 1) * tileW / 4, (ty + 1) * tileH / 4)
        }
        accepted = 1
        val others = pending.indices.filter { it != best }
        for (i in others) {
            if (accepted >= target) break
            merge(pending[i].view(), pendingPyr[i])
        }
        pending.clear()
        pendingPyr.clear()
    }

    /** Lägger ihop det som finns (om färre bilder kom än väntat). Null om ingen bild kom. */
    fun finish(tone: Boolean): YuvFrame? {
        if (ref == null) chooseReference()
        if (ref == null) return null
        val out = YuvFrame.allocate(width, height)
        val lut = if (tone) nightToneLut() else null
        pool.parallelRanges(chh, parts) { from, to ->
            for (by in from until to) {
                for (bx in 0 until cw) {
                    val b = by * cw + bx
                    val ws = wSum[b].toInt()
                    val half = ws / 2
                    out.u[b] = ((uSum[b].toInt() + half) / ws).toByte()
                    out.v[b] = ((vSum[b].toInt() + half) / ws).toByte()
                    val x = 2 * bx
                    for (yy in 2 * by..2 * by + 1) {
                        val o = yy * width + x
                        for (k in 0..1) {
                            val value = (ySum[o + k].toInt() + half) / ws
                            out.y[o + k] = (lut?.get(value) ?: value).toByte()
                        }
                    }
                }
            }
        }
        return out
    }

    private fun merge(cur: FrameView, curPyr: Pyramid) {
        val r = ref ?: return
        val rp = refPyr ?: return
        if (accepted >= target) return

        // 1. Hela bilden: grovt i 1/16, sedan finare nivå för nivå ned till full upplösning
        val l4a = rp.l4
        val l4b = curPyr.l4
        val mx4 = l4a.w / 10
        val my4 = l4a.h / 10
        var g = Align.search(0, 0, GLOBAL_RADIUS) { dx, dy -> Align.mad(l4a, l4b, mx4, my4, l4a.w - mx4, l4a.h - my4, dx, dy, 1) }
        g = Align.search(2 * g.dx, 2 * g.dy, 1) { dx, dy ->
            Align.mad(rp.l3, curPyr.l3, rp.l3.w / 10, rp.l3.h / 10, rp.l3.w * 9 / 10, rp.l3.h * 9 / 10, dx, dy, 1)
        }
        g = Align.search(2 * g.dx, 2 * g.dy, 1) { dx, dy ->
            Align.mad(rp.l2, curPyr.l2, rp.l2.w / 10, rp.l2.h / 10, rp.l2.w * 9 / 10, rp.l2.h * 9 / 10, dx, dy, 2)
        }
        g = Align.search(2 * g.dx, 2 * g.dy, 1) { dx, dy ->
            Align.mad(rp.l1, curPyr.l1, rp.l1.w / 10, rp.l1.h / 10, rp.l1.w * 9 / 10, rp.l1.h * 9 / 10, dx, dy, 4)
        }
        g = Align.search(2 * g.dx, 2 * g.dy, 1) { dx, dy ->
            Align.madFull(r, cur, width / 10, height / 10, width * 9 / 10, height * 9 / 10, dx, dy, 8)
        }
        lastGlobal = g

        // 2. Varje ruta för sig (följer lite vridning). Rutor utan struktur följer hela bilden.
        val tdx = IntArray(tilesX * tilesY)
        val tdy = IntArray(tilesX * tilesY)
        val texture = tileTexture
        pool.parallelRanges(tilesX * tilesY, parts) { from, to ->
            for (i in from until to) {
                val tx = i % tilesX
                val ty = i / tilesX
                val x0 = tx * tileW
                val y0 = ty * tileH
                val x1 = x0 + tileW
                val y1 = y0 + tileH
                if (texture != null && texture[i] < 1.5f) {
                    tdx[i] = g.dx
                    tdy[i] = g.dy
                    continue
                }
                var t = Align.search(Math.round(g.dx / 4f), Math.round(g.dy / 4f), 3) { dx, dy ->
                    Align.mad(rp.l2, curPyr.l2, x0 / 4, y0 / 4, x1 / 4, y1 / 4, dx, dy, 2)
                }
                t = Align.search(2 * t.dx, 2 * t.dy, 1) { dx, dy ->
                    Align.mad(rp.l1, curPyr.l1, x0 / 2, y0 / 2, x1 / 2, y1 / 2, dx, dy, 2)
                }
                t = Align.search(2 * t.dx, 2 * t.dy, 1) { dx, dy ->
                    Align.madFull(r, cur, x0, y0, x1, y1, dx, dy, 4)
                }
                val atGlobal = Align.madFull(r, cur, x0, y0, x1, y1, g.dx, g.dy, 4)
                val better = t.cost < atGlobal * 0.98f
                val close = abs(t.dx - g.dx) <= TILE_LIMIT && abs(t.dy - g.dy) <= TILE_LIMIT
                if (better && close) {
                    tdx[i] = t.dx
                    tdy[i] = t.dy
                } else {
                    tdx[i] = g.dx
                    tdy[i] = g.dy
                }
            }
        }
        val mdx = median3x3(tdx)
        val mdy = median3x3(tdy)

        // 3. Flytt per ruta om 32 × 32 pixlar, mjukt mellan rutornas mittpunkter
        val cellDx = IntArray(cellsX * cellsY)
        val cellDy = IntArray(cellsX * cellsY)
        for (cy in 0 until cellsY) {
            val py = (cy * CELL_BLOCKS * 2 + CELL_BLOCKS).toFloat()
            val fy = py / tileH - 0.5f
            val j0 = floor(fy).toInt().coerceIn(0, tilesY - 1)
            val j1 = min(j0 + 1, tilesY - 1)
            val ay = (fy - j0).coerceIn(0f, 1f)
            for (cx in 0 until cellsX) {
                val px = (cx * CELL_BLOCKS * 2 + CELL_BLOCKS).toFloat()
                val fx = px / tileW - 0.5f
                val i0 = floor(fx).toInt().coerceIn(0, tilesX - 1)
                val i1 = min(i0 + 1, tilesX - 1)
                val ax = (fx - i0).coerceIn(0f, 1f)
                fun lerp2(a: IntArray): Float {
                    val top = a[j0 * tilesX + i0] * (1 - ax) + a[j0 * tilesX + i1] * ax
                    val bottom = a[j1 * tilesX + i0] * (1 - ax) + a[j1 * tilesX + i1] * ax
                    return top * (1 - ay) + bottom * ay
                }
                cellDx[cy * cellsX + cx] = lerp2(mdx).roundToInt()
                cellDy[cy * cellsX + cx] = lerp2(mdy).roundToInt()
            }
        }

        // 4. Brusnivå: medianen av skillnaden i 2 × 2-block (efter flytten) avgör vad som räknas som rörelse
        val hist = IntArray(256)
        var by = 4
        while (by < chh - 4) {
            var bx = 4
            while (bx < cw - 4) {
                val c = (by / CELL_BLOCKS) * cellsX + bx / CELL_BLOCKS
                val d = blockDiff(r, cur, bx, by, cellDx[c], cellDy[c])
                if (d >= 0) hist[min(255, d / 4)]++
                bx += 8
            }
            by += 8
        }
        val total = hist.sum()
        var acc = 0
        var median = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc * 2 >= total) {
                median = i
                break
            }
        }
        val tol = max(4f, 4.5f * median)
        lastTolerance = tol
        val t4 = (tol * 4).roundToInt()

        // 5. Lägg ihop: vikt 7 där bilden stämmer med referensen, ned till 0 där något har rört sig
        val w = width
        val h = height
        val ry = r.y
        val cyp = cur.y
        val cu = cur.u
        val cv = cur.v
        val cRow = cur.yRow
        val uvRow = cur.uvRow
        val uvPix = cur.uvPix
        val ys = ySum
        val us = uSum
        val vs = vSum
        val wsum = wSum
        pool.parallelRanges(chh, parts) { from, to ->
            for (bY in from until to) {
                val cellRow = (bY / CELL_BLOCKS) * cellsX
                for (bX in 0 until cw) {
                    val c = cellRow + bX / CELL_BLOCKS
                    val dx = cellDx[c]
                    val dy = cellDy[c]
                    val x = 2 * bX
                    val yy = 2 * bY
                    val sx = x + dx
                    val sy = yy + dy
                    if (sx < 0 || sy < 0 || sx + 1 >= w || sy + 1 >= h) continue
                    val ri = yy * w + x
                    val fi = sy * cRow + sx
                    val f00 = cyp[fi].toInt() and 0xFF
                    val f01 = cyp[fi + 1].toInt() and 0xFF
                    val f10 = cyp[fi + cRow].toInt() and 0xFF
                    val f11 = cyp[fi + cRow + 1].toInt() and 0xFF
                    val rs = (ry[ri].toInt() and 0xFF) + (ry[ri + 1].toInt() and 0xFF) +
                        (ry[ri + w].toInt() and 0xFF) + (ry[ri + w + 1].toInt() and 0xFF)
                    val d = abs(f00 + f01 + f10 + f11 - rs)
                    val wt = when {
                        d <= t4 -> FULL
                        d >= 2 * t4 -> 0
                        else -> FULL * (2 * t4 - d) / t4
                    }
                    if (wt == 0) continue
                    ys[ri] = (ys[ri] + wt * f00).toShort()
                    ys[ri + 1] = (ys[ri + 1] + wt * f01).toShort()
                    ys[ri + w] = (ys[ri + w] + wt * f10).toShort()
                    ys[ri + w + 1] = (ys[ri + w + 1] + wt * f11).toShort()
                    val ux = (bX + Math.floorDiv(dx + 1, 2)).coerceIn(0, cw - 1)
                    val uy = (bY + Math.floorDiv(dy + 1, 2)).coerceIn(0, chh - 1)
                    val ui = uy * uvRow + ux * uvPix
                    val b = bY * cw + bX
                    us[b] = (us[b] + wt * (cu[ui].toInt() and 0xFF)).toShort()
                    vs[b] = (vs[b] + wt * (cv[ui].toInt() and 0xFF)).toShort()
                    wsum[b] = (wsum[b] + wt).toByte()
                }
            }
        }
        accepted++
    }

    /** Skillnaden (summa av 4 pixlar) mellan referensen och den flyttade bilden i ett 2 × 2-block, -1 utanför. */
    private fun blockDiff(r: YuvFrame, cur: FrameView, bx: Int, by: Int, dx: Int, dy: Int): Int {
        val x = 2 * bx
        val y = 2 * by
        val sx = x + dx
        val sy = y + dy
        if (sx < 0 || sy < 0 || sx + 1 >= width || sy + 1 >= height) return -1
        val ri = y * width + x
        val fi = sy * cur.yRow + sx
        val rs = (r.y[ri].toInt() and 0xFF) + (r.y[ri + 1].toInt() and 0xFF) +
            (r.y[ri + width].toInt() and 0xFF) + (r.y[ri + width + 1].toInt() and 0xFF)
        val fs = (cur.y[fi].toInt() and 0xFF) + (cur.y[fi + 1].toInt() and 0xFF) +
            (cur.y[fi + cur.yRow].toInt() and 0xFF) + (cur.y[fi + cur.yRow + 1].toInt() and 0xFF)
        return abs(fs - rs)
    }

    private fun median3x3(a: IntArray): IntArray {
        val out = IntArray(a.size)
        val tmp = IntArray(9)
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                var n = 0
                for (j in max(0, ty - 1)..min(tilesY - 1, ty + 1)) {
                    for (i in max(0, tx - 1)..min(tilesX - 1, tx + 1)) {
                        tmp[n++] = a[j * tilesX + i]
                    }
                }
                tmp.sort(0, n)
                out[ty * tilesX + tx] = tmp[n / 2]
            }
        }
        return out
    }
}

/** Ljusare skuggor i nattfotot: x + 0,5·x·(1 − x)² (svart förblir svart, högdagrar nästan oförändrade). */
fun nightToneLut(): IntArray = IntArray(256) { i ->
    val x = i / 255f
    val y = x + 0.5f * x * (1 - x) * (1 - x)
    (y * 255f).roundToInt().coerceIn(0, 255)
}

/**
 * Lång exponering: alla bilder läggs ihop utan flytt. [trails] = ljusspår (det ljusaste som passerat
 * syns), annars silkesvatten (medelvärdet). Inte trådsäker.
 */
class LongExposure(
    private val pool: ExecutorService,
    private val parts: Int,
    val width: Int,
    val height: Int,
    private val trails: Boolean
) {
    companion object {
        /** Summan ryms i 16 bitar utan tecken för upp till 257 bilder. */
        const val MAX_FRAMES = 256
        /**
         * Minsta skillnad mot medelvärdet för att räknas som ett ljusspår. Gränsen höjs efter bruset:
         * det ljusaste av många brusiga bilder ligger alltid lite över medelvärdet.
         */
        private const val TRAIL_MIN_THRESHOLD = 14
    }

    var frames = 0
        private set

    private val cw = width / 2
    private val chh = height / 2
    private val ySum = ShortArray(width * height)
    private val uSum = ShortArray(cw * chh)
    private val vSum = ShortArray(cw * chh)
    private val yMax = if (trails) ByteArray(width * height) else ByteArray(0)
    private val bMax = if (trails) ByteArray(cw * chh) else ByteArray(0)
    private val uMax = if (trails) ByteArray(cw * chh) else ByteArray(0)
    private val vMax = if (trails) ByteArray(cw * chh) else ByteArray(0)

    val isFull: Boolean get() = frames >= MAX_FRAMES

    fun add(f: FrameView) {
        if (f.width != width || f.height != height || isFull) return
        val w = width
        val fy = f.y
        val fu = f.u
        val fv = f.v
        val row = f.yRow
        val uvRow = f.uvRow
        val uvPix = f.uvPix
        val first = frames == 0
        pool.parallelRanges(chh, parts) { from, to ->
            for (by in from until to) {
                for (bx in 0 until cw) {
                    val x = 2 * bx
                    val yy = 2 * by
                    val fi = yy * row + x
                    val oi = yy * w + x
                    val p00 = fy[fi].toInt() and 0xFF
                    val p01 = fy[fi + 1].toInt() and 0xFF
                    val p10 = fy[fi + row].toInt() and 0xFF
                    val p11 = fy[fi + row + 1].toInt() and 0xFF
                    ySum[oi] = ((ySum[oi].toInt() and 0xFFFF) + p00).toShort()
                    ySum[oi + 1] = ((ySum[oi + 1].toInt() and 0xFFFF) + p01).toShort()
                    ySum[oi + w] = ((ySum[oi + w].toInt() and 0xFFFF) + p10).toShort()
                    ySum[oi + w + 1] = ((ySum[oi + w + 1].toInt() and 0xFFFF) + p11).toShort()
                    val ui = by * uvRow + bx * uvPix
                    val u = fu[ui].toInt() and 0xFF
                    val v = fv[ui].toInt() and 0xFF
                    val b = by * cw + bx
                    uSum[b] = ((uSum[b].toInt() and 0xFFFF) + u).toShort()
                    vSum[b] = ((vSum[b].toInt() and 0xFFFF) + v).toShort()
                    if (trails) {
                        if (p00 > (yMax[oi].toInt() and 0xFF)) yMax[oi] = p00.toByte()
                        if (p01 > (yMax[oi + 1].toInt() and 0xFF)) yMax[oi + 1] = p01.toByte()
                        if (p10 > (yMax[oi + w].toInt() and 0xFF)) yMax[oi + w] = p10.toByte()
                        if (p11 > (yMax[oi + w + 1].toInt() and 0xFF)) yMax[oi + w + 1] = p11.toByte()
                        val avg = (p00 + p01 + p10 + p11 + 2) shr 2
                        if (first || avg > (bMax[b].toInt() and 0xFF)) {
                            bMax[b] = avg.toByte()
                            uMax[b] = u.toByte()
                            vMax[b] = v.toByte()
                        }
                    }
                }
            }
        }
        frames++
    }

    /** Gränsen för ljusspår i den senaste [finish] (för provkörning och logg). */
    var lastTrailThreshold = 0
        private set

    fun finish(): YuvFrame? {
        if (frames == 0) return null
        val n = frames
        val half = n / 2
        val out = YuvFrame.allocate(width, height)
        val w = width
        // Brusets bidrag: medianen av (ljusaste − medel). Ljusspår är sällsynta och påverkar inte medianen.
        var threshold = TRAIL_MIN_THRESHOLD
        if (trails) {
            val hist = IntArray(256)
            var i = 0
            val total = width * height
            while (i < total) {
                val mean = ((ySum[i].toInt() and 0xFFFF) + half) / n
                val ex = ((yMax[i].toInt() and 0xFF) - mean).coerceIn(0, 255)
                hist[ex]++
                i += 7
            }
            val count = hist.sum()
            var acc = 0
            var median = 0
            for (k in 0 until 256) {
                acc += hist[k]
                if (acc * 2 >= count) {
                    median = k
                    break
                }
            }
            threshold = max(TRAIL_MIN_THRESHOLD, (median * 1.8f).roundToInt())
        }
        lastTrailThreshold = threshold
        pool.parallelRanges(chh, parts) { from, to ->
            for (by in from until to) {
                for (bx in 0 until cw) {
                    val b = by * cw + bx
                    val mu = ((uSum[b].toInt() and 0xFFFF) + half) / n
                    val mv = ((vSum[b].toInt() and 0xFFFF) + half) / n
                    val x = 2 * bx
                    var meanBlock = 0
                    for (yy in 2 * by..2 * by + 1) {
                        val o = yy * w + x
                        for (k in 0..1) {
                            val mean = ((ySum[o + k].toInt() and 0xFFFF) + half) / n
                            meanBlock += mean
                            val value = if (trails) max(mean, (yMax[o + k].toInt() and 0xFF) - threshold) else mean
                            out.y[o + k] = value.coerceIn(0, 255).toByte()
                        }
                    }
                    if (trails) {
                        val excess = (bMax[b].toInt() and 0xFF) - ((meanBlock + 2) shr 2) - threshold
                        val alpha = (excess / 24f).coerceIn(0f, 1f)
                        out.u[b] = (mu + ((uMax[b].toInt() and 0xFF) - mu) * alpha).roundToInt().coerceIn(0, 255).toByte()
                        out.v[b] = (mv + ((vMax[b].toInt() and 0xFF) - mv) * alpha).roundToInt().coerceIn(0, 255).toByte()
                    } else {
                        out.u[b] = mu.toByte()
                        out.v[b] = mv.toByte()
                    }
                }
            }
        }
        return out
    }
}

/** Beskärning i bildens egen riktning: mitten, till förhållandet lång/kort sida (1 = kvadrat). */
fun centerCropRect(width: Int, height: Int, longOverShort: Float?): IntArray {
    if (longOverShort == null) return intArrayOf(0, 0, width, height)
    val landscape = width >= height
    val long = if (landscape) width else height
    val short = if (landscape) height else width
    val current = long.toFloat() / short
    var newLong = long
    var newShort = short
    if (abs(current - longOverShort) >= 0.01f) {
        if (current < longOverShort) {
            newShort = (long / longOverShort).toInt().coerceIn(2, short)
        } else {
            newLong = (short * longOverShort).toInt().coerceIn(2, long)
        }
    }
    newLong = newLong and 1.inv()
    newShort = newShort and 1.inv()
    val cw = if (landscape) newLong else newShort
    val chh = if (landscape) newShort else newLong
    val x0 = ((width - cw) / 2) and 1.inv()
    val y0 = ((height - chh) / 2) and 1.inv()
    return intArrayOf(x0, y0, cw, chh)
}

/**
 * YUV (full range, BT.601 – som kamerans JPEG) till ARGB för raderna [rowFrom, rowTo) i
 * beskärningen (x0, y0, w). Skriver till [out] med början på [outOffset].
 */
fun yuvToArgbRows(f: YuvFrame, x0: Int, y0: Int, w: Int, rowFrom: Int, rowTo: Int, out: IntArray, outOffset: Int) {
    val cw = f.cw
    var o = outOffset
    for (r in rowFrom until rowTo) {
        val yy = y0 + r
        val yRow = yy * f.width
        val cRow = (yy shr 1) * cw
        for (c in 0 until w) {
            val xx = x0 + c
            val yv = f.y[yRow + xx].toInt() and 0xFF
            val ci = cRow + (xx shr 1)
            val u = (f.u[ci].toInt() and 0xFF) - 128
            val v = (f.v[ci].toInt() and 0xFF) - 128
            var red = yv + ((1436 * v) shr 10)
            var green = yv - ((352 * u + 731 * v) shr 10)
            var blue = yv + ((1815 * u) shr 10)
            if (red < 0) red = 0 else if (red > 255) red = 255
            if (green < 0) green = 0 else if (green > 255) green = 255
            if (blue < 0) blue = 0 else if (blue > 255) blue = 255
            out[o++] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
}
