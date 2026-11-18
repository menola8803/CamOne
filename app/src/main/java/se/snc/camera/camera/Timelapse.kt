package se.snc.camera.camera

/**
 * Tidsförlopp: kameran går med 30 bilder per sekund, men bara en bildruta per [intervalNs] släpps igenom
 * till videokodaren. Varje bildruta som släpps igenom får en tidsstämpel 1/30 s efter den förra, så att
 * filmen spelas upp i 30 bilder per sekund – 1 s mellan bilderna blir 30 gånger snabbare. Ren Kotlin
 * (provkörs lokalt); [decide] anropas på OpenGL-tråden för varje bildruta från kameran.
 */
class TimelapseGate(intervalNs: Long) {

    companion object {
        /** 1/30 s mellan bildrutorna i filmen. */
        const val FRAME_NS = 33_333_333L
        /**
         * Den första bildrutan får en tidsstämpel så här långt fram, så att den säkert ligger efter
         * kodarens start (kodaren kastar bildrutor från före starten).
         */
        const val LEAD_NS = 300_000_000L
    }

    @Volatile var intervalNs: Long = intervalNs.coerceAtLeast(FRAME_NS)
        set(value) {
            field = value.coerceAtLeast(FRAME_NS)
        }

    /** Ökar vid varje ny inspelning, så att grinden börjar om även utan bildrutor emellan. */
    @Volatile private var generation = 0
    @Volatile private var armed = false

    /** Antal bildrutor i den pågående filmen (för klipplängden i sökaren). */
    @Volatile var kept = 0
        private set

    private var seenGeneration = -1
    private var nextKeepNs = 0L
    private var nextOutNs = 0L

    /** Inspelningen börjar: nästa bildruta från kameran blir filmens första. */
    fun arm() {
        kept = 0
        generation++
        armed = true
    }

    /** Inspelningen slutar: inga fler bildrutor till kodaren. */
    fun disarm() {
        armed = false
    }

    /** Filmens längd hittills i millisekunder (30 bilder per sekund). */
    val outputMs: Long get() = kept * FRAME_NS / 1_000_000L

    /**
     * Ska bildrutan med kamerans tidsstämpel [tsNs] till filmen? Ger tidsstämpeln i filmen, eller null om
     * den hoppas över.
     */
    fun decide(tsNs: Long): Long? {
        if (!armed) return null
        val g = generation
        if (g != seenGeneration) {
            seenGeneration = g
            nextKeepNs = tsNs
            nextOutNs = tsNs + LEAD_NS
        }
        if (tsNs < nextKeepNs) return null
        val interval = intervalNs
        // Kom bildrutan sent (t.ex. efter en paus i kameran) räknas nästa intervall från den
        nextKeepNs = if (tsNs - nextKeepNs > interval) tsNs + interval else nextKeepNs + interval
        val out = nextOutNs
        nextOutNs += FRAME_NS
        kept++
        return out
    }
}
