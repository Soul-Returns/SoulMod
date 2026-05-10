package com.soulreturns.data.location

import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot

/**
 * Polls Minecraft state every client tick, parses the player's current Hypixel SkyBlock
 * location, and feeds [LocationApi]. Publishes [com.soulreturns.data.model.AreaChanged] /
 * [com.soulreturns.data.model.SublocationChanged] only on transitions.
 *
 * This is the only file in the project that actually parses the tab list / scoreboard for
 * location info — every other consumer goes through [LocationApi] or events.
 */
object LocationReader {
    private val AREA_PATTERN = Regex("Area:\\s*(.+)")

    /**
     * ASCII letters / spaces / apostrophes only — restrictive on purpose so it stops at
     * non-Latin glyphs Hypixel appends as state indicators (e.g. `ൠ x8` on the Garden island,
     * which shows the current pest count). `\p{L}` would *match* `ൠ` since it's classified
     * as a letter, defeating the purpose.
     */
    private val LOCATION_NAME_PATTERN = Regex("[a-zA-Z\\s']+")

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ ->
            LocationApi.updateArea(readArea())
            LocationApi.updateSublocation(readSublocation())
        })
    }

    private fun readArea(): String? {
        val conn = Minecraft.getInstance().player?.connection ?: return null
        for (info in conn.listedOnlinePlayers) {
            val displayName = info.tabListDisplayName?.string ?: continue
            val match = AREA_PATTERN.matchEntire(displayName.trim()) ?: continue
            return match.groupValues[1].trim()
        }
        return null
    }

    private fun readSublocation(): String? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
        for (entry in scoreboard.listPlayerScores(objective)) {
            val team = scoreboard.getPlayersTeam(entry.owner())
            val rendered = if (team == null) {
                entry.ownerName().string
            } else {
                team.getPlayerPrefix().copy().append(entry.ownerName()).append(team.getPlayerSuffix()).string
            }
            val stripped = MessageDetector.stripColorCodes(rendered)
            val idx = stripped.indexOf('⏣')
            if (idx < 0) continue
            val after = stripped.substring(idx + 1).trim()
            val match = LOCATION_NAME_PATTERN.find(after) ?: continue
            return match.value.trim()
        }
        return null
    }
}
