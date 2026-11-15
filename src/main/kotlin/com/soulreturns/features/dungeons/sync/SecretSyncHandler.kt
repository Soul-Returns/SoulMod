package com.soulreturns.features.dungeons.sync

import com.soulreturns.api.LocationApi
import com.soulreturns.core.events.Events
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.dungeons.map.ComponentPos
import com.soulreturns.features.dungeons.map.DungeonDataset
import com.soulreturns.features.dungeons.map.DungeonGeometry
import com.soulreturns.features.dungeons.map.DungeonGrid
import com.soulreturns.features.dungeons.map.DungeonRoom
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.SyncLog
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * The transport-agnostic core of dungeon secret sync: it builds the canonical message objects
 * (Skyblocker's `DUNGEON_SECRETS` schema) from local dungeon events, fans them out to every
 * registered [SecretSyncChannel], and applies inbound messages from teammates to [DungeonGrid].
 *
 * Outbound triggers (mirroring Skyblocker): `room_match` on local room identification,
 * `room_secret_count` on action-bar count change, `mimic_killed` on the chat trigger.
 *
 * `room_hide_waypoint` is ignored inbound — acting on it needs Skyblocker's `SecretWaypoint`
 * hashCode, only relevant once Soul has its own secret waypoints.
 */
object SecretSyncHandler {
    private val logger = SoulLogger("Soul/SecretSync")

    private val channels = mutableListOf<SecretSyncChannel>()

    fun addChannel(channel: SecretSyncChannel) {
        channels.add(channel)
    }

    fun register() {
        Events.subscribe<DungeonGrid.RoomMatchedEvent> { event ->
            if (!event.room.fromSync) publishRoomMatch(event.room)
        }
        Events.subscribe<DungeonRunState.SecretCountEvent> { event ->
            publishSecretCount(event.room, event.found)
        }
        Events.subscribe<DungeonRunState.MimicKilledEvent> {
            publishAll(simpleMessage("mimic_killed"))
        }
        // Reconcile channel lifecycle every second rather than only on the in/out-of-dungeon edge:
        // this also picks up the toggle being flipped mid-run or the mod loading while already in a
        // dungeon. onDungeonStart/onDungeonEnd are idempotent, so calling repeatedly is safe.
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (++tickCounter % 20 != 0) return@register
            reconcile()
        }
    }

    private var tickCounter = 0
    private val startedChannels = HashSet<String>()

    private fun reconcile() {
        val serverId = LocationApi.serverId
        val inRun = DungeonRunState.inRun && serverId != null
        for (channel in channels) {
            try {
                val shouldRun = inRun && channel.isEnabled()
                if (shouldRun) {
                    if (startedChannels.add(channel.id)) {
                        SyncLog.ws("handler", "start channel '${channel.id}' (instance $serverId)")
                    }
                    channel.onDungeonStart(serverId!!)
                } else {
                    if (startedChannels.remove(channel.id)) {
                        val why = if (!inRun) "not in a run" else "channel disabled"
                        SyncLog.ws("handler", "stop channel '${channel.id}' ($why)")
                    }
                    channel.onDungeonEnd()
                }
            } catch (t: Throwable) {
                logger.warn("Channel ${channel.id} lifecycle threw", t)
            }
        }
    }

    // ---------------------------------------------------------------- outbound

    private fun selfUuid(): UUID? = Minecraft.getInstance().player?.uuid

    /** Fan a built message out to every enabled channel. */
    private fun publishAll(message: JsonObject) {
        val type = message["type"]?.jsonPrimitive?.content
        SyncLog.ws("handler", "publish $type → ${channels.count { it.isEnabled() }} channel(s)")
        for (channel in channels) {
            if (!channel.isEnabled()) continue
            try {
                channel.publish(message)
            } catch (t: Throwable) {
                logger.warn("Channel ${channel.id} publish threw", t)
            }
        }
    }

    private fun publishRoomMatch(room: DungeonRoom) {
        val uuid = selfUuid() ?: return
        val roomId = room.roomId ?: return
        publishAll(
            buildJsonObject {
                put("type", "room_match")
                put("uuid", uuid.toString())
                put("roomId", roomId)
                // pos lets a receiver that couldn't hash the room (chunk unloaded) still place it.
                put(
                    "pos",
                    buildJsonArray {
                        for (comp in room.comps) {
                            add(
                                buildJsonObject {
                                    put("x", DungeonGeometry.CORNER_X + (comp.x / 2) * DungeonGeometry.COMBINED_SIZE)
                                    put("y", DungeonGeometry.CORNER_Z + (comp.z / 2) * DungeonGeometry.COMBINED_SIZE)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun publishSecretCount(room: DungeonRoom, found: Int) {
        val uuid = selfUuid() ?: return
        val roomId = room.roomId ?: return
        publishAll(
            buildJsonObject {
                put("type", "room_secret_count")
                put("uuid", uuid.toString())
                put("roomId", roomId)
                put("secretCount", found)
            },
        )
    }

    private fun simpleMessage(type: String): JsonObject = buildJsonObject {
        put("type", type)
        put("uuid", (selfUuid() ?: UUID(0, 0)).toString())
    }

    // ---------------------------------------------------------------- inbound

    /**
     * Apply an inbound message from a channel. Hops to the main thread itself, so channels can call
     * from their IO threads.
     *
     * @param validateSender when true, the message's `uuid` must be a player in the current run
     *   (defends the backend channel, where the server stamps an authentic uuid). Channels whose
     *   transport already scopes to the party (devonian's `Party[hash]`) pass false — their
     *   messages carry no uuid.
     */
    fun handleInbound(message: JsonObject, validateSender: Boolean = true) {
        Minecraft.getInstance().execute {
            try {
                applyMessage(message, validateSender)
            } catch (t: Throwable) {
                logger.warn("Failed to apply sync message: $message", t)
            }
        }
    }

    private fun applyMessage(message: JsonObject, validateSender: Boolean) {
        val type = message["type"]?.jsonPrimitive?.content
        if (type == null) {
            logger.debug("Sync message ignored (no type): $message")
            return
        }

        if (validateSender) {
            val sender = message["uuid"]?.jsonPrimitive?.content?.let(MojangUuid::parse)
            if (sender == null) {
                SyncLog.ws("handler", "drop — no/invalid uuid: $message")
                return
            }
            if (!checkSender(sender)) return
            if (sender == selfUuid()) {
                SyncLog.ws("handler", "drop — own echo ($type)")
                return
            }
        }

        SyncLog.ws("handler", "apply inbound $type")
        if (type == "room_match") {
            handleRoomMatch(message)
        } else if (type == "room_secret_count") {
            handleSecretCount(message)
        } else if (type == "mimic_killed") {
            DungeonRunState.applySyncedMimicKill()
            logger.debug("Synced: mimic killed")
        } else if (type == "prince_killed") {
            DungeonRunState.applySyncedPrinceKill()
            logger.debug("Synced: prince killed")
        }
    }

    /** Only accept messages from the (up to 5) players actually in this run. */
    private fun checkSender(sender: UUID): Boolean {
        for (player in DungeonRunState.players) {
            if (player?.uuid == sender) return true
        }
        logger.warn(
            "Dropped sync message from unknown sender $sender — roster: " +
                DungeonRunState.players.filterNotNull().joinToString { "${it.name}=${it.uuid}" },
        )
        return false
    }

    private fun handleRoomMatch(message: JsonObject) {
        val roomId = message["roomId"]?.jsonPrimitive?.int ?: return
        val info = DungeonDataset.roomForId(roomId)
        if (info == null) {
            SyncLog.ws("handler", "room_match id $roomId ignored — ${if (DungeonDataset.loaded) "unknown id" else "dataset not loaded"}")
            return
        }
        val pos = message["pos"]?.jsonArray ?: return
        if (pos.isEmpty() || pos.size > 4) {
            SyncLog.ws("handler", "room_match id $roomId ignored — bad pos size ${pos.size}")
            return
        }

        val cells = ArrayList<ComponentPos>(pos.size)
        for (entry in pos) {
            val obj = entry.jsonObject
            val wx = obj["x"]?.jsonPrimitive?.int ?: return
            val wz = obj["y"]?.jsonPrimitive?.int ?: return
            val cx = (wx - DungeonGeometry.CORNER_X) / DungeonGeometry.COMBINED_SIZE
            val cz = (wz - DungeonGeometry.CORNER_Z) / DungeonGeometry.COMBINED_SIZE
            val comp = ComponentPos(cx * 2, cz * 2)
            if (!comp.isValid || !comp.isRoomCell) return
            cells.add(comp)
        }

        if (cells.any { DungeonGrid.roomAt(it)?.identified == true }) {
            SyncLog.ws("handler", "room_match id $roomId ignored — cells already identified")
            return
        }

        val room = DungeonGrid.applyRoomComponents(cells)
        room.roomId = info.roomId
        room.roomName = info.name
        room.fromSync = true
        if (room.secretsTotal == 0) room.secretsTotal = info.secrets
        DungeonGrid.markDirty()
        SyncLog.ws("handler", "synced room match: ${info.name} (id $roomId)")
        // Fire so buffered devonian secret counts for this room can now attach. The publish handler
        // skips it (room.fromSync == true) so this doesn't re-broadcast.
        Events.publish(DungeonGrid.RoomMatchedEvent(room))
    }

    /** Public so [DevonianChannel] can apply a RoomSecrets message directly (devonian roomId). */
    fun applySecretCount(roomId: Int, count: Int) {
        val room = DungeonGrid.roomById(roomId)
        if (room == null) {
            SyncLog.ws(
                "handler",
                "apply id $roomId=$count IGNORED — room not identified locally. Known ids: " +
                    DungeonGrid.uniqueRooms().mapNotNull { it.roomId }.joinToString(),
            )
            return
        }
        if (room.secretsFound >= count) {
            SyncLog.ws("handler", "apply id $roomId=$count IGNORED — already at ${room.secretsFound}")
            return
        }
        if (room.secretsTotal in 1 until count) {
            SyncLog.ws("handler", "apply id $roomId=$count IGNORED — exceeds total ${room.secretsTotal}")
            return
        }

        room.secretsFound = count
        DungeonGrid.markDirty()
        SyncLog.ws("handler", "applied id $roomId secrets = $count/${room.secretsTotal} (${room.roomName})")
    }

    private fun handleSecretCount(message: JsonObject) {
        val roomId = message["roomId"]?.jsonPrimitive?.int ?: return
        val count = message["secretCount"]?.jsonPrimitive?.int ?: return
        applySecretCount(roomId, count)
    }
}
