package com.soulreturns.features.scoreboard

import com.soulreturns.config.ScoreboardEntry

/**
 * Ready-made setups from the mock (`mocks/scoreboard/mock.js`: `FULL` and `ACTS`). Ids of types not
 * built yet are listed anyway: they are skipped until their type lands, then appear in place.
 */
object ScoreboardDefaults {
    private fun of(vararg ids: String): List<ScoreboardEntry> = (listOf("header") + ids + "footer").map { ScoreboardEntry(it) }

    /** Everywhere: the owner's Custom Scoreboard order (the mock's demo custom line left out). */
    fun mainEntries(): List<ScoreboardEntry> =
        of(
            "server", "date", "time", "players", "area", "profile", "visiting", "sep",
            "island", "purse", "motes", "sowdust", "bank", "gems", "heat", "bits",
            "copper", "kernels", "northStars", "soulflow", "cold", "sep",
            "events", "sep", "power", "tunings", "objective", "sep",
            "slayer", "powder", "party", "sep", "extra",
        )

    fun dungeonEntries(): List<ScoreboardEntry> = of("area", "time", "sep", "events", "sep", "party", "sep", "purse", "bits", "extra")

    fun kuudraEntries(): List<ScoreboardEntry> = of("area", "sep", "events", "sep", "party", "sep", "purse", "extra")
}
