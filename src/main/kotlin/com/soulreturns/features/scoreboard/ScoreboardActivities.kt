package com.soulreturns.features.scoreboard

import com.soulreturns.config.ScoreboardConfig
import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.config.ScoreboardSetup

/**
 * An activity that can have its own setup ("Different setups per activity"). [matches] reads the
 * snapshot; [layout] is the ready-made start for "Customize for X" (null = a copy of Everywhere).
 */
class ScoreboardActivity(
    val id: String,
    val name: String,
    /** Where the setup screen's preview pretends to be ("The Catacombs (F7)"). */
    val previewPlace: String,
    val layout: (() -> List<ScoreboardEntry>)?,
    val matches: (ScoreboardSnapshot) -> Boolean,
)

/**
 * The activities, most specific first: the first one that matches and has a stored setup wins, so a
 * Garden setup still applies during a contest without a Jacob's contest setup. Area names are matched
 * loosely and never validated: an unknown island simply uses the main setup.
 */
object ScoreboardActivities {
    const val DUNGEONS = "dungeons"
    const val KUUDRA = "kuudra"

    private fun area(s: ScoreboardSnapshot, vararg names: String): Boolean {
        val a = s.area ?: return false
        return names.any { a.contains(it, ignoreCase = true) }
    }

    private fun sidebarHas(s: ScoreboardSnapshot, prefix: String): Boolean = s.sidebar.any { it.text.trim().startsWith(prefix, ignoreCase = true) }

    val all: List<ScoreboardActivity> =
        listOf(
            // Not "Dungeon Hub": the run itself (mode `dungeon`, area falls back to the packet's `Dungeon`).
            ScoreboardActivity(DUNGEONS, "Dungeons", "The Catacombs (F7)", ScoreboardDefaults::dungeonEntries) { s ->
                s.mode == "dungeon" || s.area.equals("Dungeon", ignoreCase = true) || area(s, "Catacombs")
            },
            ScoreboardActivity(KUUDRA, "Kuudra", "Kuudra's Hollow (T5)", ScoreboardDefaults::kuudraEntries) { s ->
                s.mode == "kuudra" || area(s, "Kuudra")
            },
            ScoreboardActivity("slayer", "Slayer quest", "a slayer quest", null) { s -> sidebarHas(s, "Slayer Quest") },
            ScoreboardActivity("jacob", "Jacob's contest", "a Jacob's contest", null) { s -> sidebarHas(s, "Jacob's Contest") },
            ScoreboardActivity("spooky", "Spooky Festival", "the Spooky Festival", null) { s -> sidebarHas(s, "Spooky Festival") },
            ScoreboardActivity("mineshaft", "Glacite Mineshaft", "a Glacite Mineshaft", null) { s -> s.mode == "mineshaft" || area(s, "Mineshaft") },
            ScoreboardActivity("crystal_hollows", "Crystal Hollows", "the Crystal Hollows", null) { s -> s.mode == "crystal_hollows" || area(s, "Crystal Hollows") },
            ScoreboardActivity("rift", "The Rift", "the Rift", null) { s -> s.mode == "rift" || area(s, "Rift") },
            ScoreboardActivity("garden", "Garden", "the Garden", null) { s -> s.mode == "garden" || area(s, "Garden") },
            ScoreboardActivity("galatea", "Galatea", "Galatea", null) { s -> area(s, "Galatea") },
        )

    fun of(id: String): ScoreboardActivity? = all.firstOrNull { it.id == id }

    /** The first activity going on now, setup or not (the setup screen's default tab). */
    fun current(s: ScoreboardSnapshot): ScoreboardActivity? = all.firstOrNull { it.matches(s) }

    /**
     * The activity whose setup draws now: with the switch on, the first matching activity that has a
     * stored setup; else null (= the main setup). Stored setups for ids this version does not know
     * are kept but never chosen.
     */
    fun active(config: ScoreboardConfig, s: ScoreboardSnapshot): ScoreboardActivity? {
        if (!config.perActivity || config.activities.isEmpty()) return null
        return all.firstOrNull { it.id in config.activities && it.matches(s) }
    }

    /** The setup to draw now. */
    fun setup(config: ScoreboardConfig, s: ScoreboardSnapshot): ScoreboardSetup =
        active(config, s)?.let { config.activities[it.id] } ?: config.main

    /** The first activity going on now that has its own position (only with the switch on). */
    fun positioned(config: ScoreboardConfig, s: ScoreboardSnapshot): ScoreboardActivity? {
        if (!config.perActivity || config.ownPositionIn.isEmpty()) return null
        return all.firstOrNull { it.id in config.ownPositionIn && it.matches(s) }
    }

    /** "Customize for X": X's ready-made layout, or a copy of the main setup. */
    fun startingSetup(config: ScoreboardConfig, id: String): ScoreboardSetup =
        of(id)?.layout?.let { ScoreboardSetup(it()) } ?: config.main
}
