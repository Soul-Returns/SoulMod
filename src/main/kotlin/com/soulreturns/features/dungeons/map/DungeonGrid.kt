package com.soulreturns.features.dungeons.map

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events

/**
 * The shared dungeon layout state: 36 room slots (6×6 room grid) + 60 door slots. Written by the
 * map-item scanner and the room matcher; read by the HUD and the websocket sync.
 *
 * A multi-cell room occupies several slots pointing at the same [DungeonRoom] instance —
 * iterate with a `visited` set (see [uniqueRooms]).
 */
object DungeonGrid {
    val rooms = arrayOfNulls<DungeonRoom>(36)
    val doors = arrayOfNulls<DungeonDoor>(60)

    /** Bumped whenever layout/checkmark/secret state changes; the HUD redraws when it moves. */
    var revision = 0
        private set

    data class RoomMatchedEvent(val room: DungeonRoom) : Event

    fun markDirty() {
        revision++
    }

    fun roomAt(comp: ComponentPos): DungeonRoom? {
        if (!comp.isRoomCell || !comp.isValid) return null
        return rooms.getOrNull(comp.roomIndex())
    }

    fun roomAtWorld(wx: Double, wz: Double): DungeonRoom? {
        val comp = ComponentPos.fromWorld(wx, wz)
        if (!comp.isValid) return null
        // A position inside a door gap maps to the room before it by rounding down to the even grid.
        val roomComp = ComponentPos(comp.x and 1.inv(), comp.z and 1.inv())
        return roomAt(roomComp)
    }

    fun roomById(roomId: Int): DungeonRoom? {
        val visited = HashSet<DungeonRoom>()
        for (room in rooms) {
            if (room != null && visited.add(room) && room.roomId == roomId) return room
        }
        return null
    }

    fun uniqueRooms(): List<DungeonRoom> {
        val visited = LinkedHashSet<DungeonRoom>()
        for (room in rooms) if (room != null) visited.add(room)
        return visited.toList()
    }

    /** Create-or-get the room at a room cell. */
    fun ensureRoom(comp: ComponentPos): DungeonRoom {
        roomAt(comp)?.let { return it }
        val room = DungeonRoom(comp)
        rooms[comp.roomIndex()] = room
        wireDoors(comp, room)
        markDirty()
        return room
    }

    fun ensureDoor(comp: ComponentPos): DungeonDoor {
        val idx = comp.doorIndex()
        doors[idx]?.let { return it }
        val door = DungeonDoor(comp)
        doors[idx] = door
        for (roomComp in comp.adjacentRooms()) {
            roomAt(roomComp)?.doors?.add(door)
        }
        markDirty()
        return door
    }

    fun removeDoor(comp: ComponentPos) {
        val idx = comp.doorIndex()
        val door = doors[idx] ?: return
        doors[idx] = null
        val visited = HashSet<DungeonRoom>()
        for (room in rooms) if (room != null && visited.add(room)) room.doors.remove(door)
        markDirty()
    }

    /**
     * Merge the rooms at two adjacent room cells into one (the map showed a solid join between
     * them — same physical room). Keeps the instance that already has an identity/type.
     */
    fun mergeRooms(compA: ComponentPos, compB: ComponentPos) {
        val a = roomAt(compA)
        val b = roomAt(compB)
        when {
            a == null && b == null -> {
                val room = ensureRoom(compA)
                absorb(room, compB)
            }
            a == null -> absorb(b!!, compA)
            b == null -> absorb(a, compB)
            a === b -> return
            else -> {
                // Entrance never merges (its neighbors are separate rooms that share no join).
                if (a.type == RoomType.ENTRANCE || b.type == RoomType.ENTRANCE) return
                val keep = if (a.roomId != null || b.roomId == null) a else b
                val gone = if (keep === a) b else a
                for (comp in gone.comps) absorb(keep, comp)
                keep.explored = keep.explored || gone.explored
                if (keep.secretsTotal == 0) keep.secretsTotal = gone.secretsTotal
                if (keep.secretsFound < gone.secretsFound) keep.secretsFound = gone.secretsFound
                for (door in gone.doors) keep.doors.add(door)
            }
        }
        markDirty()
    }

    /**
     * Make one room span all [cells] (used when applying a teammate's `room_match`) and return
     * it. Merges/creates as needed; cells must be valid room cells.
     */
    fun applyRoomComponents(cells: List<ComponentPos>): DungeonRoom {
        val room = ensureRoom(cells[0])
        for (i in 1 until cells.size) {
            val other = roomAt(cells[i])
            if (other != null && other !== room) {
                mergeRooms(cells[0], cells[i])
            } else {
                absorb(room, cells[i])
            }
        }
        markDirty()
        // mergeRooms may have kept the other instance — return whatever now owns cell 0.
        return roomAt(cells[0]) ?: room
    }

    private fun absorb(room: DungeonRoom, comp: ComponentPos) {
        room.addComponent(comp)
        rooms[comp.roomIndex()] = room
        wireDoors(comp, room)
    }

    private fun wireDoors(comp: ComponentPos, room: DungeonRoom) {
        for (doorComp in comp.adjacentDoors()) {
            doors.getOrNull(doorComp.doorIndex())?.let { room.doors.add(it) }
        }
    }

    fun reset() {
        rooms.fill(null)
        doors.fill(null)
        markDirty()
    }
}
