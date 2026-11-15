package com.soulreturns.features.dungeons

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.util.soulChat
import java.util.Locale

/**
 * Stops commands fired by command keybinds and inventory buttons from taking the player out of a
 * dungeon run (`Dungeons -> Run -> Block leaving commands`). Typed chat never passes through here.
 *
 * Matches Hypixel's own commands, not client aliases another mod expands after us (Skyblocker's
 * `/d`). Party commands are never blocked: leaving or disbanding the party keeps you in the run.
 */
object DungeonCommandGuard {
    /** Leave regardless of arguments. */
    private val ALWAYS = setOf(
        "hub", "is", "island", "l", "lobby", "leave", "garden", "warpforge", "rejoin", "skyblock", "sb",
    )

    /** Leave only with an argument; bare they open a menu or do nothing. */
    private val WITH_ARGUMENT = setOf("warp", "play", "visit")

    private val DUNGEON_HUB = setOf("dh", "dungeon_hub")

    /**
     * True when [command] (as sent, leading `/` optional) must not be sent right now; the player is
     * told in chat. Callers skip the send and any click sound.
     */
    fun blocks(command: String): Boolean {
        val dungeons = cfg.dungeons
        if (!blocks(command, LocationApi.inDungeon, dungeons.blockLeavingCommands, dungeons.allowDungeonHubWarp)) return false
        val shown = "/" + command.trim().removePrefix("/").trim()
        soulChat("§cBlocked §f$shown§c during the dungeon run §8(Dungeons → Run → Block leaving commands)")
        return true
    }

    /** Pure matcher behind [blocks]. */
    fun blocks(command: String, inDungeon: Boolean, enabled: Boolean, allowDungeonHub: Boolean): Boolean {
        if (!enabled || !inDungeon) return false
        val parts = command.trim().removePrefix("/").lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }
        val name = parts.firstOrNull() ?: return false
        val argument = parts.getOrNull(1)
        return when {
            name in ALWAYS -> true
            name !in WITH_ARGUMENT || argument == null -> false
            name == "warp" && allowDungeonHub && argument in DUNGEON_HUB -> false
            else -> true
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
