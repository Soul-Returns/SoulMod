package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg

/**
 * Tells the party a mineshaft is worth warping to, on the strength of its Lapis corpse count.
 *
 * Armed by `Sending to Mineshaft...` (recorded in [MineshaftState] — the same signal that classifies
 * the visit as self-discovered, which is not a coincidence: only a mineshaft you found yourself is
 * yours to advertise). Within [WINDOW_MS] of that line, the first tick where the Lapis count reaches
 * `mining.mineshaft.lapisThreshold` sends `/pc !ptme Found N Lapis Corpses in Mineshaft`, once per arm.
 *
 * The window exists because the tab list needs a moment to populate after the transfer, and because a
 * count that only reaches the threshold minutes later is not news any more.
 */
object LapisCorpseAlert {
    private const val WINDOW_MS = 10_000L
    private const val CORPSE_TYPE = "Lapis"

    /** Driven by [MineshaftTick], so only inside the mineshaft area. */
    fun tick(nowMs: Long) {
        if (MineshaftState.lapisFired) return
        if (!cfg.mining.mineshaft.lapisPtme) return
        if (!MineshaftState.alertWindowOpen(nowMs, WINDOW_MS)) return

        val threshold = cfg.mining.mineshaft.lapisThreshold
        val count = MineshaftCorpses.totalOf(CORPSE_TYPE)
        if (count < threshold) return

        // One shot per arm whatever the gate decides — see MineshaftAlertGate.send.
        MineshaftState.lapisFired = true
        val noun = if (count == 1) "Lapis Corpse" else "Lapis Corpses"
        MineshaftAlertGate.send("!ptme Found $count $noun in Mineshaft", "$count Lapis corpses (threshold $threshold)")
    }
}
