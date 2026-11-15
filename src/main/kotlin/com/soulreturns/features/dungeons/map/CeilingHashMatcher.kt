package com.soulreturns.features.dungeons.map

import com.soulreturns.api.LocationApi
import com.soulreturns.core.events.Events
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState

/**
 * Identifies dungeon rooms by hashing the ceiling block column at each room cell and looking the
 * fingerprint up in [DungeonDataset] (the bundled devonian core table) — devonian's technique. A
 * hit gives the room's `roomId`, name and secret total without needing to scan blocks up close.
 *
 * **Two stages so entering a room has no scan delay:**
 * - **pre-scan** ([prescan], cadenced): hashes rooms whose chunk is loaded but that aren't opened
 *   yet, stashing the result in [DungeonRoom.pendingInfo] — cached, NOT applied.
 * - **reveal** ([reveal], every tick): the instant a room flips `explored`, stamps its cached
 *   identity on (or hashes on the spot as a fallback). So the name/type/color appear the moment you
 *   cross in, with no wait for the next sweep or for the hash to run.
 *
 * **Anti-cheat gate:** identities are only ever *applied* to OPENED rooms (reveal gates on
 * [DungeonRoom.explored]). Pre-scan caches undiscovered rooms internally but never reveals them —
 * reading your own loaded chunks isn't observable to the server; only showing unopened layout would
 * be ghosting.
 *
 * The hash algorithm mirrors devonian's exactly (legacy block ids down y=140..12, iron-bars/chest
 * forced to "0", `String.hashCode()`), so our live hashes match the bundled `cores`.
 */
object CeilingHashMatcher {
    private val logger = SoulLogger("Soul/CeilingMatcher")

    private const val CEIL_TOP = 140
    private const val CEIL_BOTTOM = 12

    private var tickCounter = 0

    fun register() {
        Events.subscribe<LocationApi.DungeonStateEvent> { if (it.inDungeon) DungeonDataset.ensureLoaded() }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (!LocationApi.inDungeon || !DungeonDataset.loaded) return@register
            val level = mc.level ?: return@register
            // Every tick (cheap): flag the room you're standing in as opened, then reveal any opened
            // room whose identity is ready — so crossing into a room shows it with no perceptible lag.
            markStandingRoomExplored(mc)
            reveal(level)
            // Twice a second (the hashing cost): pre-scan not-yet-opened rooms so their identity is
            // cached and ready before anyone enters them.
            if (++tickCounter % 10 == 0) prescan(level)
        }
    }

    /**
     * The room you're physically standing in is, by definition, opened. Create + mark it so the
     * entrance is identified BEFORE the run starts — Hypixel doesn't put the map item in the hotbar
     * until the start countdown ends, so the map-item scanner can't reveal it yet.
     */
    private fun markStandingRoomExplored(mc: Minecraft) {
        val p = mc.player ?: return
        val comp = ComponentPos.fromWorld(p.x, p.z)
        if (!comp.isValid) return
        val roomComp = ComponentPos(comp.x and 1.inv(), comp.z and 1.inv())
        val room = DungeonGrid.roomAt(roomComp) ?: DungeonGrid.ensureRoom(roomComp)
        if (!room.explored) {
            room.explored = true
            DungeonGrid.markDirty()
        }
    }

    /**
     * Apply the identity of every OPENED, still-unidentified room. Uses the pre-scan cache when
     * present (instant, no hashing on the enter tick); otherwise hashes on the spot as a fallback
     * (the room was opened before pre-scan reached it — chunk just loaded, or a teammate opened it).
     *
     * **Anti-cheat gate:** the `explored` check here is what keeps unopened layout hidden — pending
     * identities are cached but never revealed until the room is opened.
     */
    private fun reveal(level: ClientLevel) {
        val visited = HashSet<DungeonRoom>()
        for (room in DungeonGrid.rooms) {
            if (room == null || room.identified || !visited.add(room)) continue
            if (!room.explored) continue
            val info = room.pendingInfo ?: probe(level, room) ?: continue
            identify(room, info)
        }
    }

    /**
     * Pre-hash not-yet-opened rooms whose chunk is loaded, caching the hit in [DungeonRoom.pendingInfo]
     * so [reveal] can stamp it instantly the moment the room opens. Nothing is displayed here — a
     * pending identity is invisible until the room is `explored`.
     */
    private fun prescan(level: ClientLevel) {
        val visited = HashSet<DungeonRoom>()
        for (room in DungeonGrid.rooms) {
            if (room == null || room.identified || room.explored) continue
            if (room.pendingInfo != null || !visited.add(room)) continue
            room.pendingInfo = probe(level, room)
        }
    }

    /** Hash the room's ceiling at each loaded component cell; return the first dataset hit, or null. */
    private fun probe(level: ClientLevel, room: DungeonRoom): DungeonDataset.RoomInfo? {
        for (comp in room.comps) {
            val cx = comp.worldCenterX()
            val cz = comp.worldCenterZ()
            if (!level.hasChunk(cx shr 4, cz shr 4)) continue
            DungeonDataset.roomForCore(hashCeil(level, cx, cz))?.let { return it }
        }
        return null
    }

    private fun identify(room: DungeonRoom, info: DungeonDataset.RoomInfo) {
        room.roomId = info.roomId
        room.roomName = info.name
        if (room.secretsTotal == 0) room.secretsTotal = info.secrets
        // Set type from the dataset when the map item hasn't (e.g. pre-start, before the map exists),
        // so the room colors correctly (entrance = green) instead of staying unknown/gray.
        if (room.type == RoomType.UNKNOWN || room.type == RoomType.NORMAL) room.type = info.type
        DungeonGrid.markDirty()
        logger.info("Room identified: ${info.name} (id ${info.roomId}, ${info.secrets} secrets)")
        // Fires the pipeline: HUD redraw, devonian pending-count flush, and the sync publisher
        // (which broadcasts a room_match for teammates who can't hash it — chunk unloaded).
        Events.publish(DungeonGrid.RoomMatchedEvent(room))
    }

    // ---------------------------------------------------------------- hashing

    /** devonian's ceiling core-hash: concatenated legacy ids down the column at (x, z). */
    private fun hashCeil(level: ClientLevel, x: Int, z: Int): Int {
        val sb = StringBuilder(160)
        val pos = BlockPos.MutableBlockPos()
        for (y in CEIL_TOP downTo CEIL_BOTTOM) {
            val state = level.getBlockState(pos.set(x, y, z))
            val id = legacyId(state) ?: continue
            val block = state.block
            if (block === Blocks.IRON_BARS || block === Blocks.CHEST) {
                sb.append('0')
            } else {
                sb.append(id)
            }
        }
        return sb.toString().hashCode()
    }

    /** Legacy numeric id for a block state, or null if it's not in the table (skipped in the hash). */
    private fun legacyId(state: BlockState): Int? {
        val block = state.block
        val key = BuiltInRegistries.BLOCK.getKey(block)
        var name = "${key.namespace}:${key.path}"

        val fluid = state.fluidState
        if (!fluid.isEmpty) {
            if (fluid.`is`(FluidTags.WATER)) return if (fluid.isSource) 9 else 8
            if (fluid.`is`(FluidTags.LAVA)) return if (fluid.isSource) 11 else 10
        }
        if (block is SlabBlock) {
            name += "[type=${state.getValue(SlabBlock.TYPE).name.lowercase()}]"
        }
        return DungeonLegacyBlocks.BLOCKS[name]
    }
}
