package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg

/**
 * Sibling of [LapisCorpseAlert] for Vanguard corpses.
 *
 * Hypixel spawns **at most one Vanguard corpse per mineshaft**, so there is nothing to threshold: its
 * mere presence is the whole message. Same arming as the Lapis alert — within [WINDOW_MS] of
 * `Sending to Mineshaft...`, once per arm.
 */
object VanguardCorpseAlert {
    private const val WINDOW_MS = 10_000L
    private const val CORPSE_TYPE = "Vanguard"

    /** Driven by [MineshaftTick], so only inside the mineshaft area. */
    fun tick(nowMs: Long) {
        if (MineshaftState.vanguardFired) return
        if (!cfg.mining.mineshaft.vanguardPtme) return
        if (!MineshaftState.alertWindowOpen(nowMs, WINDOW_MS)) return
        if (MineshaftCorpses.totalOf(CORPSE_TYPE) <= 0) return

        MineshaftState.vanguardFired = true
        MineshaftAlertGate.send("!ptme Found Vanguard Corpse in Mineshaft", "Vanguard corpse detected")
    }
}
