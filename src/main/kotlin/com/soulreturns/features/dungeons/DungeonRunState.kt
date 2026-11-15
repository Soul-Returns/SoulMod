package com.soulreturns.features.dungeons

import com.soulreturns.features.dev.DevState
import com.soulreturns.api.LocationApi
import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ActionBarMessage
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.dungeons.map.DungeonGrid
import com.soulreturns.features.dungeons.map.DungeonMapItemScanner
import com.soulreturns.features.dungeons.map.DungeonRoom
import com.soulreturns.features.dungeons.map.MapPlayerPos
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.PlayerTabOverlay
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.world.scores.DisplaySlot
import kotlin.math.PI

/**
 * Run-level dungeon state: floor, the 5-player roster (tab list), current-room secret counts
 * (action bar) and mimic/prince kills (chat). Everything resets when [LocationApi] reports the
 * dungeon instance was left.
 *
 * The roster lives in a fixed 5-slot array in **tab-list order** — the vanilla map item's player
 * decorations are matched to players positionally, so order is load-bearing (same trick as
 * Skyblocker/devonian). Hypixel puts dungeon player entries at tab indices 1, 5, 9, 13, 17.
 */
object DungeonRunState {
    private val logger = SoulLogger("Soul/Dungeon")

    // Group 1 name, group 2 class ("EMPTY"/"DEAD" possible), group 3 roman level.
    private val playerTabRegex =
        "\\[\\d+] (?:\\[[A-Za-z]+] )?([A-Za-z0-9_]+) (?:.+ )?\\((\\S+) ?([LXVI0]+)?\\)".toRegex()
    private val floorRegex = "The Catacombs \\((\\w+)\\)".toRegex()
    private val secretsRegex = "\\b(\\d+)/(\\d+) Secrets\\b".toRegex()
    private val ghostRegex = "☠ ([A-Za-z0-9_]+|You) .*became a ghost".toRegex()
    private val reconnectRegex = "☠ ([A-Za-z0-9_]+) reconnected".toRegex()

    val players = arrayOfNulls<DungeonPlayer>(5)
    var floor = DungeonFloor.NONE
        private set
    var mimicKilled = false
        private set
    var princeKilled = false
        private set

    /** True once we're in a catacombs instance and know which floor. */
    val inRun: Boolean get() = LocationApi.inDungeon && floor != DungeonFloor.NONE

    val self: DungeonPlayer?
        get() {
            val name = Minecraft.getInstance().player?.gameProfile?.name ?: return null
            return players.firstOrNull { it?.name == name }
        }

    data class SecretCountEvent(val found: Int, val total: Int, val room: DungeonRoom) : Event
    class MimicKilledEvent : Event
    class PrinceKilledEvent : Event

    // Vanilla's tab ordering comparator is private; unobfuscated 26.2 makes reflection stable.
    private val tabComparator: Comparator<PlayerInfo>? by lazy {
        try {
            val field = PlayerTabOverlay::class.java.getDeclaredField("PLAYER_COMPARATOR")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            field.get(null) as Comparator<PlayerInfo>
        } catch (t: Throwable) {
            logger.warn("Could not reflect tab-list comparator — player roster disabled", t)
            null
        }
    }

    private var tickCounter = 0
    private var lastSecretRoom: DungeonRoom? = null

    fun register() {
        DevState.register("dungeon") { devState() }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (!LocationApi.inDungeon) {
                // Debounced leave-reset: `inDungeon` can briefly flicker from Hypixel location
                // noise. Resetting the grid on every flip cleared rooms ~twice a second (boxes
                // recovered via the scanner but names flashed). Only reset after we've been out for
                // a sustained window; genuine leaves also fire JOIN below, which resets immediately.
                if (leftTicks < LEAVE_RESET_TICKS) {
                    leftTicks++
                    if (leftTicks == LEAVE_RESET_TICKS) reset()
                }
                return@register
            }
            leftTicks = 0
            tickCounter++
            if (tickCounter % 5 == 0) updateRoster(mc)
            if (tickCounter % 20 == 0) updateFloor(mc)
            tickSelfPosition(mc)
        }

        Events.subscribe<ChatMessage> { onChat(it.raw) }
        Events.subscribe<ActionBarMessage> { onActionBar(it.text) }
        // Reset on EVERY world/server change + (re)connect. JOIN is reliable (unlike the flickery
        // DungeonStateEvent) and fires on dungeon→hub, dungeon→dungeon, and reconnects.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            leftTicks = 0
            reset()
        }
    }

    private fun devState(): Map<String, Any?> {
        val entity = Minecraft.getInstance().player
        val room = entity?.let { DungeonGrid.roomAtWorld(it.x, it.z) }
        val rooms = DungeonGrid.rooms.filterNotNull().distinct()
        return linkedMapOf(
            "in_dungeon" to LocationApi.inDungeon,
            "floor" to floor.shortName.ifEmpty { null },
            "in_run" to inRun,
            "mimic_killed" to mimicKilled,
            "prince_killed" to princeKilled,
            "room" to room?.let { linkedMapOf("name" to it.roomName, "id" to it.roomId, "type" to it.type.name, "secrets" to "${it.secretsFound}/${it.secretsTotal}") },
            "rooms_known" to rooms.size,
            "rooms_identified" to rooms.count { it.roomId != null },
            "players" to players.filterNotNull().map {
                linkedMapOf("name" to it.name, "class" to it.dungeonClass.displayName, "dead" to it.isDead, "entity_tracked" to (it.entity?.isRemoved == false))
            },
        )
    }

    private var leftTicks = 0

    // ~3s out of a dungeon before we clear state (absorbs brief inDungeon flicker).
    private const val LEAVE_RESET_TICKS = 60

    private fun updateFloor(mc: Minecraft) {
        if (floor != DungeonFloor.NONE) return
        val scoreboard = mc.player?.connection?.scoreboard() ?: return
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return
        for (entry in scoreboard.listPlayerScores(objective)) {
            val team = scoreboard.getPlayersTeam(entry.owner) ?: continue
            val line = MessageDetector.stripColorCodes(
                team.playerPrefix.string + team.playerSuffix.string,
            )
            val match = floorRegex.find(line) ?: continue
            floor = DungeonFloor.fromShortName(match.groupValues[1])
            if (floor != DungeonFloor.NONE) logger.info("Dungeon floor detected: ${floor.shortName}")
            return
        }
    }

    private fun updateRoster(mc: Minecraft) {
        val connection = mc.player?.connection ?: return
        val comparator = tabComparator ?: return
        val ordered = connection.listedOnlinePlayers.sortedWith(comparator)

        for (slot in 0 until 5) {
            val tabIndex = 1 + slot * 4
            val info = ordered.getOrNull(tabIndex)
            val line = info?.tabListDisplayName?.string?.let(MessageDetector::stripColorCodes)
            val match = line?.let { playerTabRegex.find(it) }
            if (match == null) {
                players[slot] = null
                continue
            }

            val name = match.groupValues[1]
            val className = match.groupValues[2]
            val player = players[slot]?.takeIf { it.name == name } ?: DungeonPlayer(name).also {
                players[slot] = it
            }

            if (className == "DEAD") {
                player.isDead = true
            } else if (className != "EMPTY") {
                player.isDead = false
                player.dungeonClass = DungeonClass.from(className)
            }
            if (player.uuid == null) {
                player.uuid = connection.getPlayerInfo(name)?.profile?.id
            }
            if (player.entity == null) {
                player.entity = mc.level?.players()?.firstOrNull { it.gameProfile.name == name }
            }
        }
    }

    private fun tickSelfPosition(mc: Minecraft) {
        val entity = mc.player ?: return
        val me = self ?: return
        // Screen coords are y-down, so the heading angle is (yaw + 90°) WITHOUT negation:
        // yaw 0 (south) must point down the map. Negating flips top/bottom (cos is even).
        me.pushPosition(
            MapPlayerPos.fromWorld(entity.x, entity.z, (entity.yRot.toDouble() + 90.0) * PI / 180.0),
        )
    }

    private fun onChat(message: String) {
        if (!LocationApi.inDungeon) return
        val stripped = MessageDetector.stripColorCodes(message)

        if (!mimicKilled &&
            (
                stripped.contains("Mimic Killed!", ignoreCase = true) ||
                    stripped.contains("Mimic Dead!", ignoreCase = true) ||
                    stripped.contains("\$SKYTILS-DUNGEON-SCORE-MIMIC\$")
                )
        ) {
            mimicKilled = true
            Events.publish(MimicKilledEvent())
            return
        }

        ghostRegex.find(stripped)?.let { match ->
            var name = match.groupValues[1]
            if (name == "You") name = Minecraft.getInstance().player?.gameProfile?.name ?: return
            players.firstOrNull { it?.name == name }?.isDead = true
            return
        }
        reconnectRegex.find(stripped)?.let { match ->
            players.firstOrNull { it?.name == match.groupValues[1] }?.isDead = false
        }
    }

    private fun onActionBar(text: String) {
        if (!inRun) return
        val entity = Minecraft.getInstance().player ?: return
        val room = DungeonGrid.roomAtWorld(entity.x, entity.z) ?: return

        // Skip the first update after a room change: the action bar can still show the previous
        // room's counts for a moment (same guard as devonian).
        if (room !== lastSecretRoom) {
            lastSecretRoom = room
            return
        }

        val match = secretsRegex.find(MessageDetector.stripColorCodes(text)) ?: return
        val found = match.groupValues[1].toIntOrNull() ?: return
        val total = match.groupValues[2].toIntOrNull() ?: return

        if (room.secretsTotal == 0) room.secretsTotal = total
        if (room.secretsFound != found) {
            room.secretsFound = found
            DungeonGrid.markDirty()
            Events.publish(SecretCountEvent(found, total, room))
        }
    }

    /** Apply a mimic/prince kill received over the websocket (no re-publish). */
    fun applySyncedMimicKill() {
        mimicKilled = true
    }

    fun applySyncedPrinceKill() {
        princeKilled = true
    }

    fun reset() {
        players.fill(null)
        floor = DungeonFloor.NONE
        mimicKilled = false
        princeKilled = false
        lastSecretRoom = null
        tickCounter = 0
        DungeonGrid.reset()
        DungeonMapItemScanner.reset()
        logger.debug("Dungeon run state reset")
    }
}
