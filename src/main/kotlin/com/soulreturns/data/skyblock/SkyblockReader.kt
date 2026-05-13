package com.soulreturns.data.skyblock

import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot

/**
 * Polls Minecraft state every client tick to determine whether the player is on Hypixel
 * SkyBlock, and feeds [SkyblockApi].
 *
 * Detection rule: the **scoreboard sidebar's displayed title** equals `SKYBLOCK` (case
 * insensitive after color-code stripping). Hypixel sets this for every SkyBlock world but
 * not for lobbies, Bedwars, etc., so it's a reliable signal that doesn't depend on the tab
 * list or chat parsing.
 */
object SkyblockReader {
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                SkyblockApi.update(detect())
            }
        )
    }

    private fun detect(): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        val objective = level.scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return false
        val title = MessageDetector.stripColorCodes(objective.displayName.string).trim()
        return title.equals("SKYBLOCK", ignoreCase = true)
    }
}
