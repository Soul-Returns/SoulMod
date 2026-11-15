package com.soulreturns.features.dev

import com.soulreturns.api.HypixelModApi
import com.soulreturns.api.LocationApi
import com.soulreturns.core.events.Events
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.data.skyblock.SubLocationChanged
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.dungeons.map.DungeonGrid
import com.soulreturns.features.dungeons.map.DungeonMapItemScanner
import com.soulreturns.features.party.PartyManager
import com.soulreturns.platform.devaccess.DevAccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.world.item.ItemStack

/**
 * The entire surface the dev-only `soul-devbridge` mod uses from Soul. That jar is installed once
 * while Soul updates itself, so this stays small and stable: any removed or changed member or
 * meaning bumps [VERSION], and a bridge built for another version disables itself instead of
 * crashing the game. Adding a member keeps the version. Nothing here reads state until called.
 */
object DevBridgeApi {
    /** Read reflectively by the bridge at startup (a `const` would be inlined into its own jar). */
    const val VERSION = 1

    fun devUnlocked(): Boolean = DevAccess.unlocked

    /** One dev output window (`/soul dev …` style). */
    fun output(title: String, lines: List<String>) {
        DevOutput.begin(title)
        lines.forEach(DevOutput::line)
    }

    private var sink: PacketTap.Sink? = null

    /** The bridge's single packet observer (`inbound`, packet); null unregisters it. */
    @Synchronized
    fun setPacketSink(observer: ((Boolean, Packet<*>) -> Unit)?) {
        sink?.let(PacketTap::unregister)
        sink = observer?.let { o -> PacketTap.Sink { inbound, packet -> o(inbound, packet) }.also(PacketTap::register) }
    }

    /** The packet logger's line `{t, dir, type, channel?, data}`. Network thread. */
    fun packetJson(inbound: Boolean, packet: Packet<*>): JsonObject = PacketJson.packet(if (inbound) "in" else "out", packet)

    fun packetChannel(packet: Packet<*>): String? = PacketJson.customPayload(packet)?.type()?.id()?.toString()

    /** Inside a packet observer call: the bundle the packet came in (1, 2, …), 0 when it came alone. */
    fun packetBundle(): Long = PacketTap.bundle

    /** Packet type paths skipped unless asked for (movement, chunks, particles, …). */
    fun noisyPackets(): Set<String> = PacketJson.NOISY

    fun legacy(component: Component): String = PacketJson.legacy(component)

    /** `{id, count, name, lore?, custom_data?, sb_id?}` or `"empty"`. */
    fun item(stack: ItemStack): JsonElement {
        val base = PacketJson.item(stack)
        if (base !is JsonObject) return base
        val sbId = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getString("id")?.orElse(null) ?: return base
        return JsonObject(base + ("sb_id" to JsonPrimitive(sbId)))
    }

    /** Reflective, depth-capped JSON of any value. */
    fun dump(value: Any?): JsonElement = PacketJson.dump(value)

    /** Soul's location readers. Client thread. */
    fun location(): JsonElement =
        dump(
            linkedMapOf(
                "on_skyblock" to SkyblockState.onSkyblock,
                "profile_type" to SkyblockState.profileType.name,
                "area" to SkyblockLocation.area,
                "sub_location" to SkyblockLocation.subLocation,
                "profile" to SkyblockProfileState.current,
                "server_id" to LocationApi.serverId,
                "server_type" to LocationApi.serverType,
                "mode" to LocationApi.mode,
                "map" to LocationApi.map,
                "in_dungeon" to LocationApi.inDungeon,
            ),
        )

    /**
     * Party as Soul knows it: the dungeon roster (tab order, class, dead, last map-marker position
     * in world coordinates and when it was read) and the Hypixel party. Client thread.
     */
    fun party(): JsonElement {
        val now = System.currentTimeMillis()
        val roster =
            DungeonRunState.players.filterNotNull().map { p ->
                linkedMapOf(
                    "name" to p.name,
                    "uuid" to p.uuid?.toString(),
                    "class" to p.dungeonClass.displayName,
                    "dead" to p.isDead,
                    "map" to p.mapWorld?.let { w -> linkedMapOf("x" to w[0], "z" to w[1], "yaw" to w[2], "age_ms" to now - p.mapAt) },
                )
            }
        val party = PartyManager.getPartyState()
        return dump(
            linkedMapOf(
                "in_dungeon" to LocationApi.inDungeon,
                "floor" to DungeonRunState.floor.shortName.ifEmpty { null },
                "in_run" to DungeonRunState.inRun,
                "dungeon_roster" to roster,
                "in_party" to (party != null),
                "leader" to party?.leader?.name,
                "members" to PartyManager.getMemberNames(),
            ),
        )
    }

    /** The identified dungeon room at a world position, or null. Client thread. */
    fun dungeonRoom(x: Double, z: Double): JsonElement {
        if (!LocationApi.inDungeon) return JsonNull
        val room = DungeonGrid.roomAtWorld(x, z) ?: return JsonNull
        return dump(linkedMapOf("name" to room.roomName, "id" to room.roomId, "type" to room.type.name, "explored" to room.explored))
    }

    /** The dungeon map item's decorations, or null without a map. Client thread. */
    fun mapMarkers(): JsonElement = dump(DungeonMapItemScanner.markerSnapshot())

    /** [listener] runs on the publishing (client) thread after an area, sublocation, profile or server change. */
    fun onPlaceChanged(listener: () -> Unit) {
        Events.subscribe<AreaChanged> { listener() }
        Events.subscribe<SubLocationChanged> { listener() }
        Events.subscribe<ProfileChanged> { listener() }
        Events.subscribe<HypixelModApi.LocationChangeEvent> { listener() }
    }

    fun stateNames(): List<String> = DevState.names()

    /** One [DevState] provider as JSON. Client thread. */
    fun state(name: String): JsonElement = dump(DevState.read(name))
}
