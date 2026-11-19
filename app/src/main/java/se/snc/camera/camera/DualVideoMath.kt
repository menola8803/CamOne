package se.snc.camera.camera

import kotlin.math.abs

/*
 * Ren matematik för dubbelvideon (ingen Android-kod, provkörs lokalt): var i kamerans bild en halva av
 * filmen hämtas, hur bytet av plats glider och hur kamerans tidsstämplar görs om till samma klocka som
 * ljudet.
 */
object DualVideoMath {

    /** Filmens storlek (bredd × höjd, stående 3:4 som sökaren) – den första som kodaren klarar används. */
    val VIDEO_SIZES = listOf(1080 to 1440, 960 to 1280, 720 to 960)

    /** Hur länge bytet av plats glider i filmen. */
    const val SWAP_NS = 300_000_000L

    /**
     * 4 × 4-matris (kolumnvis, som i OpenGL) som tar en punkt (u, v) i en halva av filmen (0–1, v uppåt)
     * till en punkt (s, t) i kamerans buffert (0–1, t uppåt – det som SurfaceTexture-matrisen tar emot).
     * Kamerabilden vrids [rotation] grader medurs till stående, spegelvänds om [mirror] och beskärs på
     * mitten till halvans format [halfAspect] (bredd/höjd) – samma som sökaren och fotot.
     */
    fun halfMatrix(rotation: Int, mirror: Boolean, bufW: Int, bufH: Int, halfAspect: Float): FloatArray {
        val r = ((rotation % 360) + 360) % 360
        val uw = if (r == 90 || r == 270) bufH else bufW
        val uh = if (r == 90 || r == 270) bufW else bufH
        val img = if (uw > 0 && uh > 0) uw.toFloat() / uh else halfAspect
        // Beskärning i den stående bilden: x = cx0 + cxs·u, y = cy0 + cys·v
        var cxs = 1f
        var cx0 = 0f
        var cys = 1f
        var cy0 = 0f
        if (img > halfAspect) {
            cxs = halfAspect / img
            cx0 = (1f - cxs) / 2f
        } else {
            cys = img / halfAspect
            cy0 = (1f - cys) / 2f
        }
        // Spegling av den stående bilden: x → 1 − x
        if (mirror) {
            cx0 = 1f - cx0
            cxs = -cxs
        }
        // Vridning tillbaka till bufferten: s = a·u + b·v + c, t = d·u + e·v + f
        val a: Float
        val b: Float
        val c: Float
        val d: Float
        val e: Float
        val f: Float
        when (r) {
            90 -> { // s = 1 − y, t = x
                a = 0f; b = -cys; c = 1f - cy0
                d = cxs; e = 0f; f = cx0
            }
            180 -> { // s = 1 − x, t = 1 − y
                a = -cxs; b = 0f; c = 1f - cx0
                d = 0f; e = -cys; f = 1f - cy0
            }
            270 -> { // s = y, t = 1 − x
                a = 0f; b = cys; c = cy0
                d = -cxs; e = 0f; f = 1f - cx0
            }
            else -> {
                a = cxs; b = 0f; c = cx0
                d = 0f; e = cys; f = cy0
            }
        }
        return floatArrayOf(
            a, d, 0f, 0f,
            b, e, 0f, 0f,
            0f, 0f, 1f, 0f,
            c, f, 0f, 1f
        )
    }

    /** Tillämpar en kolumnvis 4 × 4-matris på punkten (x, y, 0, 1). */
    fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[4] * y + m[12]) to (m[1] * x + m[5] * y + m[13])

    /** Mjuk start och mjukt slut (0–1 → 0–1). */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Flyttar bytets förlopp [current] (0 = bakkameran överst, 1 = frontkameran överst) mot målet under
     * [dtNs] nanosekunder. Byts målet mitt i glider bilden tillbaka därifrån den är.
     */
    fun stepSwap(current: Float, frontOnTop: Boolean, dtNs: Long): Float {
        val goal = if (frontOnTop) 1f else 0f
        val step = (dtNs.coerceAtLeast(0L).toFloat() / SWAP_NS).coerceAtMost(1f)
        return if (current < goal) minOf(goal, current + step) else maxOf(goal, current - step)
    }

    /**
     * Skillnaden att dra av från kamerans tidsstämpel [ts] för att få System.nanoTime-tid (samma klocka
     * som ljudet). Kameror räknar antingen från uppstarten med djupsömn (elapsedRealtimeNanos) eller
     * utan (nanoTime) – den klocka som ligger närmast tidsstämpeln är rätt.
     */
    fun clockOffset(ts: Long, nowMono: Long, nowReal: Long): Long =
        if (abs(ts - nowReal) < abs(ts - nowMono)) nowReal - nowMono else 0L

    /** Guldlinjens tjocklek (bildpunkter) mellan halvorna, som i fotot. */
    fun dividerPx(halfHeight: Int): Int = (halfHeight / 160).coerceAtLeast(2)
}
