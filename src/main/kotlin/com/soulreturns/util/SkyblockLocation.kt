package com.soulreturns.util

import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot

/**
 * Hypixel SkyBlock location detection.
 *
 * Two distinct concepts:
 *  - [area] = the **island** the player is currently on (`Hub`, `Garden`, `Dwarven Mines`, ...).
 *    Read from the tab list, where Hypixel adds a virtual player whose display name is `Area: <X>`.
 *  - [sublocation] = a more specific area *within* the island (`Ruins`, `The Garden`,
 *    `Dwarven Base Camp`, `Your Island`, ...). Read from the scoreboard sidebar's `⏣` line.
 *
 * Both return `null` when not connected, when the relevant signal isn't present, or when the
 * server isn't Hypixel SkyBlock. Polling these getters is cheap (≤80 string comparisons each).
 */
object SkyblockLocation {

    private val AREA_PATTERN = Regex("Area:\\s*(.+)")
    /**
     * ASCII letters / spaces / apostrophes only — restrictive on purpose so it stops at
     * non-Latin glyphs Hypixel appends as state indicators (e.g. `ൠ x8` on the Garden island,
     * which shows the current pest count). `\p{L}` would *match* `ൠ` since it's classified
     * as a letter, defeating the purpose.
     */
    private val LOCATION_NAME_PATTERN = Regex("[a-zA-Z\\s']+")

    /** Returns the current SkyBlock island name (e.g. "Garden", "Hub") or null if unknown. */
    val area: String? get() {
        val conn = Minecraft.getInstance().player?.connection ?: return null
        for (info in conn.listedOnlinePlayers) {
            val displayName = info.tabListDisplayName?.string ?: continue
            val match = AREA_PATTERN.matchEntire(displayName.trim()) ?: continue
            return match.groupValues[1].trim()
        }
        return null
    }

    /** Returns the current sublocation name (e.g. "The Garden", "Ruins") or null if unknown. */
    val sublocation: String? get() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
        for (entry in scoreboard.listPlayerScores(objective)) {
            val team = scoreboard.getPlayersTeam(entry.owner())
            val rendered = if (team == null) entry.ownerName().string
                else team.getPlayerPrefix().copy().append(entry.ownerName()).append(team.getPlayerSuffix()).string
            val stripped = MessageDetector.stripColorCodes(rendered)
            val idx = stripped.indexOf('⏣')
            if (idx < 0) continue
            val after = stripped.substring(idx + 1).trim()
            val match = LOCATION_NAME_PATTERN.find(after) ?: continue
            return match.value.trim()
        }
        return null
    }

    fun isInArea(name: String): Boolean = area == name
    fun isInSublocation(name: String): Boolean = sublocation == name
}
