package com.soulreturns.features.dungeons.boss

/**
 * Goldor's phase (F7/M7 P3) as sections: which one is open and where the player stands, for the "not open yet"
 * warning. Pure: fed with the stripped chat lines and the player's position (docs/research/m7-boss-2026-11-13.md §2.4, §3).
 *
 * Section n+1 begins when section n's counter reached N/N **and** its gate was destroyed (in either order; S1-S3);
 * the core (5) when `The Core entrance is opening!` (S4 has no gate). Every one of the 36 positioned death-zone hits
 * in the 8 M7 runs was on a player standing in a later section than the open one.
 */
class GoldorSections {
    /** The open section: 1..4, [CORE], or 0 outside Goldor's phase. */
    var open = 0
        private set

    /** The open section's last `(n/N)`, or null before its first. */
    var done: Int? = null
        private set
    var of: Int? = null
        private set

    /** The open section's gate is down already (it can go before the counter is full). */
    var gateDown = false
        private set

    private var full = false
    private var begun = false
    private val destroyed = BooleanArray(4)

    fun gateDestroyed(section: Int): Boolean = section in 1..3 && destroyed[section]

    /**
     * Goldor's phase started (T, [com.soulreturns.features.dungeons.devices.GoldorPhaseModel]): section 1 is open. Once
     * per fight; `Who dares` calls it too, so a late line never resets counters that came after T.
     */
    fun begin() {
        if (begun) return
        begun = true
        start(1)
    }

    fun chat(line: String) {
        if (line == StormModel.WHO_DARES) {
            begin()
            return
        }
        if (line == StormModel.CORE_OPEN) {
            begun = true
            start(CORE)
            return
        }
        if (open == 0) return
        if (line.startsWith("[BOSS] Necron:") || line.startsWith(GOLDOR_DONE)) {
            open = 0
            return
        }
        if (open == CORE) return
        if (line == GATE_DOWN) {
            if (open !in 1..3) return
            gateDown = true
            destroyed[open] = true
        } else {
            val m = COUNTER.find(line) ?: return
            val n = m.groupValues[2].toIntOrNull() ?: return
            val total = m.groupValues[3].toIntOrNull() ?: return
            if (n !in 1..total || total != if (open == 2) 8 else 7) return
            done = n
            of = total
            if (n >= total) full = true
        }
        if (open < 4 && full && gateDown) start(open + 1)
    }

    fun reset() {
        begun = false
        destroyed.fill(false)
        start(0)
    }

    private fun start(section: Int) {
        open = section
        done = null
        of = null
        gateDown = false
        full = false
    }

    /** The section the player at x/y/z stands in when it is not open yet, else null. */
    fun ahead(x: Double, y: Double, z: Double): Int? {
        if (open !in 1..4) return null
        val a = area(x, y, z)
        return if (a > open) a else null
    }

    companion object {
        const val CORE = 5
        const val GATE_DOWN = "The gate has been destroyed!"
        const val GOLDOR_DONE = "[BOSS] Goldor: You have done it"
        val COUNTER = Regex("^\\S+ (activated a terminal|activated a lever|completed a device)! \\((\\d+)/(\\d+)\\)$")

        /**
         * The section whose area holds x/y/z (0 = none): S1 x ≥ 89 & z 60–115, S2 z ≥ 115 & x > 19, S3 x ≤ 19, S4 z ≤ 60 & x < 89,
         * all at y 100–150 (the 2026-11-08 capture's areas). The core platform (x 50–60, z 44–72) is none: at 19 checks
         * with S4 open the user stood there unhurt, while the S4 hits were at its corridor (48–49, 115, 39–40).
         */
        fun area(x: Double, y: Double, z: Double): Int {
            if (y < 100.0 || y > 150.0) return 0
            if (x in 50.0..60.0 && z in 44.0..72.0) return 0
            if (x >= 89.0 && z in 60.0..115.0) return 1
            if (z >= 115.0 && x > 19.0) return 2
            if (x <= 19.0) return 3
            if (z <= 60.0 && x < 89.0) return 4
            return 0
        }
    }
}
