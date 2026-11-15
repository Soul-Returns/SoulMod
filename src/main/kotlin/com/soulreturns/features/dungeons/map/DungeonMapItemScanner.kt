package com.soulreturns.features.dungeons.map

import com.soulreturns.api.LocationApi
import com.soulreturns.features.dungeons.DungeonFloor
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import kotlin.math.PI

/**
 * Reads the vanilla dungeon map item Hypixel puts in hotbar slot 8 and projects its pixels onto
 * [DungeonGrid]: room types + explored state + checkmarks from pixel colors, room joins/doors
 * from the gap pixels, and live player markers from the map decorations.
 *
 * Mixin-free: instead of hooking the map packet we poll `MapItem.getSavedData` per tick — the
 * saved data is updated client-side whenever the packet arrives, so polling sees the same bytes.
 *
 * Pixel color constants are Hypixel's map palette bytes, proven in devonian/Skyblocker/IllegalMap.
 */
object DungeonMapItemScanner {
    private val logger = SoulLogger("Soul/DungeonMapScanner")

    private const val MAP_SIZE = 128
    private const val ROOM_SPACING = 4

    private const val COLOR_EMPTY: Byte = 0
    private const val COLOR_ENTRANCE: Byte = 30
    private const val COLOR_NORMAL: Byte = 63
    private const val COLOR_UNOPENED: Byte = 85
    private const val COLOR_TRAP: Byte = 62
    private const val COLOR_MINIBOSS: Byte = 74
    private const val COLOR_PUZZLE: Byte = 66
    private const val COLOR_FAIRY: Byte = 82
    private const val COLOR_BLOOD: Byte = 18
    private const val CHECK_WHITE: Byte = 34
    private const val CHECK_GREEN: Byte = 30
    private const val CHECK_FAIL: Byte = 18
    private const val CHECK_QUESTION: Byte = 119
    private const val DOOR_WITHER: Byte = 119

    // Derived per run from the entrance room's pixels.
    var roomSize = -1
        private set
    var roomGap = -1
        private set
    var mapOffsetX = -1
        private set
    var mapOffsetZ = -1
        private set

    private val unscannedDoors = mutableSetOf<ComponentPos>()
    private var lastFloor = DungeonFloor.NONE

    // The dungeon map's id, cached once we've seen it so we can keep reading its data even when the
    // item isn't in a slot (e.g. holding a bow puts an arrow-preview item in slot 8). 1024 is
    // Hypixel's usual dungeon map id — a last resort for a run where the item was never visible.
    private var cachedMapId: MapId? = null
    private val DEFAULT_MAP_ID = MapId(1024)

    fun register() {
        reset()
        ClientTickEvents.END_CLIENT_TICK.register { mc -> tick(mc) }
        // Leave-reset is driven by DungeonRunState (debounced + JOIN), which calls reset() here.
        // Don't reset on the raw (flickery) DungeonStateEvent — a spurious reset flips `lastFloor`,
        // which re-triggers a full grid clear and makes room names flash.
    }

    fun reset() {
        roomSize = -1
        roomGap = -1
        mapOffsetX = -1
        mapOffsetZ = -1
        lastFloor = DungeonFloor.NONE
        cachedMapId = null
        unscannedDoors.clear()
        for (x in 0..10) {
            for (z in ((x and 1) xor 1)..10 step 2) {
                unscannedDoors.add(ComponentPos(x, z))
            }
        }
    }

    private fun tick(mc: Minecraft) {
        if (!LocationApi.inDungeon) return
        val floor = DungeonRunState.floor
        // Re-dimension AND clear the grid when the floor changes (e.g. NONE → F5 at run start). The
        // clear is essential: a scan with the wrong dimensions mis-merges rooms into one giant blob,
        // and merges persist in the grid unless we reset it here.
        if (floor != lastFloor) {
            // Order matters: reset() sets lastFloor back to NONE, so lastFloor MUST be assigned
            // AFTER it — otherwise floor != lastFloor stays true and this fires (clearing the grid)
            // every tick, which made room names flash ~twice a second.
            reset()
            DungeonGrid.reset()
            lastFloor = floor
        }
        // Don't scan the map with unknown dimensions — the floor's roomsW/H are needed to place the
        // grid and detect joins/doors. Pre-start (no floor yet, and no map item anyway) the entrance
        // is shown by CeilingHashMatcher (the room the player stands in), not by this scanner.
        if (floor == DungeonFloor.NONE) return
        val player = mc.player ?: return
        val level = mc.level ?: return

        // Outside the -200..-10 room grid (boss arena etc.) the slot-8 map no longer shows the
        // clear layout — stop scanning rather than reading garbage.
        if (!ComponentPos.fromWorld(player.x, player.z).isValid) return

        val mapState = currentMap(player, level) ?: return
        val colors = mapState.colors ?: return
        if (colors.size < MAP_SIZE * MAP_SIZE) return
        if (colors[0] != COLOR_EMPTY) return

        if (roomSize == -1 && !scanDimensions(colors)) return

        updateRooms(colors)
        updateDoors(colors)
        updatePlayerMarkers(mapState)
    }

    /** Locate the entrance room's pixel square to derive room size + the grid's pixel offsets. */
    private fun scanDimensions(colors: ByteArray): Boolean {
        val floor = DungeonRunState.floor
        // Effective grid dims — default to the largest (6×6) pre-start when the floor is unknown;
        // re-dimensioned via `lastFloor` once the real floor is detected.
        val roomsW = if (floor == DungeonFloor.NONE) 6 else floor.roomsW
        val roomsH = if (floor == DungeonFloor.NONE) 6 else floor.roomsH

        var idx = 0
        var i = 0
        while (idx < colors.size && colors[idx] != COLOR_ENTRANCE) {
            i++
            idx = ((i and 7) shl 4) + ((i shr 3) shl 11)
        }
        if (idx >= colors.size) return false

        var l = idx
        var r = idx
        while (l - 1 >= 0 && colors[l - 1] == COLOR_ENTRANCE) l--
        while (r + 1 < colors.size && colors[r + 1] == COLOR_ENTRANCE) r++
        var t = idx
        var b = idx
        while (t - MAP_SIZE >= 0 && colors[t - MAP_SIZE] == COLOR_ENTRANCE) t -= MAP_SIZE
        while (b + MAP_SIZE < colors.size && colors[b + MAP_SIZE] == COLOR_ENTRANCE) b += MAP_SIZE

        val left = l and 127
        val right = r and 127
        val top = t shr 7
        val bottom = b shr 7

        roomSize = right - left + 1
        roomGap = roomSize + ROOM_SPACING
        mapOffsetX = left % roomGap
        mapOffsetZ = top % roomGap

        // If the floor's grid leaves 2+ rooms of margin the map is centered one room further in.
        val mapWidth = roomGap * (roomsW - 1) + roomSize
        val mapHeight = roomGap * (roomsH - 1) + roomSize
        if (MAP_SIZE - mapWidth >= roomGap * 2) mapOffsetX += roomGap
        if (MAP_SIZE - mapHeight >= roomGap * 2) mapOffsetZ += roomGap

        if (bottom - top != right - left) logger.debug("Map scan found non-square entrance room")
        logger.debug("Map dimensions: roomSize=$roomSize gap=$roomGap offset=($mapOffsetX, $mapOffsetZ)")
        return true
    }

    private fun updateRooms(colors: ByteArray) {
        var dirty = false
        val visited = HashSet<DungeonRoom>()

        for (idx in 0 until 36) {
            val existing = DungeonGrid.rooms[idx]
            if (existing != null && !visited.add(existing)) continue

            val x = idx % 6
            val z = idx / 6
            val px = mapOffsetX + x * roomGap
            val pz = mapOffsetZ + z * roomGap
            val cx = px + roomSize / 2 - 1
            val cz = pz + roomSize / 2 + 1

            val roomCol = colors.getOrNull(px + pz * MAP_SIZE) ?: continue
            val centerCol = colors.getOrNull(cx + cz * MAP_SIZE) ?: continue
            if (roomCol == COLOR_EMPTY) continue

            val room = existing ?: DungeonGrid.ensureRoom(ComponentPos(x * 2, z * 2)).also {
                visited.add(it)
                dirty = true
            }

            val newType = typeFromColor(roomCol, room.type)
            if (newType != room.type) {
                room.type = newType
                dirty = true
            }

            val nowExplored = roomCol != COLOR_UNOPENED
            if (room.explored != nowExplored) {
                room.explored = nowExplored
                dirty = true
            }

            if (room.checkmark != Checkmark.GREEN) {
                val newCheck = if (roomCol == centerCol) {
                    if (room.checkmark == Checkmark.UNEXPLORED && !nowExplored) Checkmark.UNEXPLORED else Checkmark.NONE
                } else {
                    checkmarkFromColor(centerCol, room.checkmark)
                }
                if (newCheck != room.checkmark) {
                    room.checkmark = newCheck
                    // Green = cleared with ALL secrets — snap the counter even if sync/action bar
                    // never delivered the intermediate counts.
                    if (newCheck == Checkmark.GREEN && room.secretsTotal > 0) {
                        room.secretsFound = room.secretsTotal
                    }
                    dirty = true
                }
            }
        }

        if (dirty) DungeonGrid.markDirty()
    }

    private fun typeFromColor(color: Byte, current: RoomType): RoomType = when (color.toInt()) {
        COLOR_ENTRANCE.toInt() -> RoomType.ENTRANCE
        COLOR_BLOOD.toInt() -> RoomType.BLOOD
        COLOR_MINIBOSS.toInt() -> RoomType.MINIBOSS
        COLOR_FAIRY.toInt() -> RoomType.FAIRY
        COLOR_PUZZLE.toInt() -> RoomType.PUZZLE
        COLOR_TRAP.toInt() -> RoomType.TRAP
        COLOR_NORMAL.toInt() -> if (current == RoomType.RARE) RoomType.RARE else RoomType.NORMAL
        COLOR_UNOPENED.toInt() -> current
        else -> current
    }

    private fun checkmarkFromColor(color: Byte, current: Checkmark): Checkmark = when (color.toInt()) {
        CHECK_WHITE.toInt() -> Checkmark.WHITE
        CHECK_GREEN.toInt() -> Checkmark.GREEN
        CHECK_FAIL.toInt() -> Checkmark.FAILED
        CHECK_QUESTION.toInt() -> if (current == Checkmark.NONE) Checkmark.NONE else Checkmark.UNEXPLORED
        else -> Checkmark.NONE
    }

    /**
     * Door cells: compare the pixel where the two rooms would join against the pixel of the door
     * gap itself. Same color ⇒ a solid join (one multi-cell room) ⇒ merge. Different ⇒ a real door,
     * typed by its color.
     *
     * Re-evaluated EVERY tick for all door cells (not resolved-once): rooms appear on the Hypixel
     * map progressively, so a join must be re-checked until both its rooms exist and merge. Merges
     * are idempotent (mergeRooms returns immediately when the two cells are already the same room),
     * so re-running is cheap and churn-free.
     */
    private fun updateDoors(colors: ByteArray) {
        var dirty = false
        for (comp in unscannedDoors) {
            val jx = mapOffsetX + (comp.x / 2) * roomGap + (comp.x and 1) * roomSize
            val jz = mapOffsetZ + (comp.z / 2) * roomGap + (comp.z and 1) * roomSize
            val dx = jx + (comp.z and 1) * roomSize / 2
            val dz = jz + (comp.x and 1) * roomSize / 2

            val joinCol = colors.getOrNull(jx + jz * MAP_SIZE) ?: continue
            val doorCol = colors.getOrNull(dx + dz * MAP_SIZE) ?: continue
            if (doorCol == COLOR_EMPTY) continue

            dirty = true

            if (joinCol == doorCol) {
                DungeonGrid.removeDoor(comp)
                val rooms = comp.adjacentRooms()
                if (rooms.size == 2) DungeonGrid.mergeRooms(rooms[0], rooms[1])
                continue
            }

            val door = DungeonGrid.ensureDoor(comp)
            when (doorCol.toInt()) {
                DOOR_WITHER.toInt() -> {
                    door.type = DoorType.WITHER
                    door.opened = false
                }
                COLOR_BLOOD.toInt() -> {
                    door.type = DoorType.BLOOD
                }
                else -> {
                    door.type = DoorType.NORMAL
                    door.opened = true
                }
            }
        }
        if (dirty) DungeonGrid.markDirty()
    }

    /**
     * Position OTHER players from the vanilla map decorations. On the Hypixel dungeon map the local
     * player is the FRAME (white pointer) decoration; the remaining non-frame decorations are the
     * other party members, in the same order as the tab roster. So we pair non-frame decorations to
     * non-self alive players by index (self is positioned from its own entity every tick, elsewhere).
     */
    private fun updatePlayerMarkers(mapState: MapItemSavedData) {
        val floor = DungeonRunState.floor
        if (floor == DungeonFloor.NONE) return // need real dims to rescale marker coords
        val selfName = Minecraft.getInstance().player?.gameProfile?.name
        val others = DungeonRunState.players.filterNotNull().filter { !it.isDead && it.name != selfName }
        if (others.isEmpty()) return

        val decorations = mapState.decorations.filter { it.type() !== MapDecorationTypes.FRAME }
        if (decorations.size != others.size) return // can't align confidently

        val now = System.currentTimeMillis()
        for (i in others.indices) {
            val dec = decorations[i]
            val x = componentX(dec.x())
            val z = componentZ(dec.y())
            // y-down screen coords: (yaw + 90°), NOT negated — see DungeonRunState.tickSelfPosition.
            val rot = (dec.rot() / 16.0 * 360.0 + 90.0) / 180.0 * PI

            others[i].pushPosition(MapPlayerPos(x, z, rot))
            others[i].mapWorld = doubleArrayOf(toWorld(x, DungeonGeometry.CORNER_X), toWorld(z, DungeonGeometry.CORNER_Z), yaw(dec.rot()))
            others[i].mapAt = now
        }
    }

    /** The dungeon map's saved data: slot 8, else any filled map, else the cached/default id. */
    private fun currentMap(player: LocalPlayer, level: ClientLevel): MapItemSavedData? {
        // Find the dungeon map's id and CACHE it. Hypixel normally puts the map in hotbar slot 8,
        // but when you hold a bow an arrow-preview item takes that slot, so we can't rely on slot 8.
        // Once we know the id we read the map data by id (works even when the item isn't in any
        // slot); the cached id survives the whole run. Default (1024) is Hypixel's usual dungeon map
        // id, a last resort for a fresh join where the map was never visible (e.g. joined w/ bow).
        val items = player.inventory.getNonEquipmentItems()
        var mapId = items.getOrNull(8)?.takeIf { it.`is`(Items.FILLED_MAP) }?.get(DataComponents.MAP_ID)
        if (mapId == null) {
            for (st in items) {
                if (st.`is`(Items.FILLED_MAP)) {
                    val id = st.get(DataComponents.MAP_ID)
                    if (id != null) { mapId = id; break }
                }
            }
        }
        if (mapId != null) cachedMapId = mapId else mapId = cachedMapId ?: DEFAULT_MAP_ID
        return MapItem.getSavedData(mapId, level)
    }

    private fun componentX(mapX: Byte): Double = MapPlayerPos.fromMarker(mapX.toInt(), mapOffsetX, roomGap)

    private fun componentZ(mapY: Byte): Double = MapPlayerPos.fromMarker(mapY.toInt(), mapOffsetZ, roomGap)

    private fun toWorld(component: Double, corner: Int): Double = corner + component * DungeonGeometry.HALF_COMBINED

    /** Decoration rotation (16 steps of 22.5°, vanilla's yaw encoding) as entity yaw in -180..180. */
    private fun yaw(rot: Byte): Double = ((rot * 22.5 + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /**
     * Every decoration on the dungeon map as the map gives it, for the dev bridge: type, map
     * coordinates, yaw, and world x/z once the grid dimensions are known. Null without a map.
     * The map shows no players inside the boss room.
     */
    fun markerSnapshot(): Map<String, Any?>? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val map = currentMap(player, level) ?: return null
        val floor = DungeonRunState.floor
        val dims = floor != DungeonFloor.NONE && roomSize != -1
        val markers =
            map.decorations.map { dec ->
                val out = linkedMapOf<String, Any?>(
                    "type" to dec.type().unwrapKey().map { it.identifier().toString() }.orElse("?"),
                    "self" to (dec.type() === MapDecorationTypes.FRAME),
                    "map_x" to dec.x().toInt(),
                    "map_y" to dec.y().toInt(),
                    "yaw" to yaw(dec.rot()),
                )
                if (dims) {
                    out["x"] = toWorld(componentX(dec.x()), DungeonGeometry.CORNER_X)
                    out["z"] = toWorld(componentZ(dec.y()), DungeonGeometry.CORNER_Z)
                }
                dec.name().ifPresent { out["name"] = it.string }
                out
            }
        return linkedMapOf("map_id" to cachedMapId?.id(), "floor" to floor.shortName, "dims_known" to dims, "markers" to markers)
    }
}
