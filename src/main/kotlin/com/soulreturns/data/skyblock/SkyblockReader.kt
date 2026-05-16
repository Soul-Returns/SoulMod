package com.soulreturns.data.skyblock

import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot

/**
 * Polls Minecraft state every client tick to determine whether the player is on Hypixel
 * SkyBlock, and feeds [SkyblockApi].
 *
 * Detection rule: the **scoreboard sidebar's displayed title** (after color-code stripping)
 * matches the [TITLE_PATTERN] regex. Hypixel uses several variants of the SKYBLOCK title:
 *  - `SKYBLOCK`                — baseline
 *  - `SKYBLOCK CO-OP`          — co-op profile
 *  - `SKYBLOCK GUEST`          — visiting another player's island
 *  - `SKYBLOCK ♲` / `SKYBLOCK ☀` / `SKYBLOCK Ⓑ`
 *                              — Stranded / Ironman-like / Bingo suffixes (one-char marker)
 *
 * Pattern lifted from SkyHanni's `SkyBlockLocationData.scoreboardTitlePattern`
 * (LGPL-2.1; attribution surfaced in `/soul config` → About → Used Software). They've
 * encountered every variant in production. Also tolerant of the `SKIBLOCK` typo seen on
 * Hypixel Alpha. An exact-equality check was too strict and missed Co-op / Bingo / Stranded
 * profiles entirely.
 */
object SkyblockReader {
    private val TITLE_PATTERN = Regex("SK[YI]BLOCK(?: CO-OP| GUEST)?(?: [♲☀Ⓑ])?")

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
        return TITLE_PATTERN.matches(title)
    }
}
