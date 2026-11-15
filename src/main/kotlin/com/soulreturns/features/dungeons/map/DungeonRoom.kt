package com.soulreturns.features.dungeons.map

/**
 * One logical dungeon room — possibly spanning several component cells (1x2/L/2x2 merge as the
 * map reveals connecting "joins" between cells).
 *
 * Identity is devonian's integer `roomId` (+ display `roomName`), filled by [CeilingHashMatcher]
 * (ceiling core-hash, works without entering) or by an inbound `room_match` from a teammate. The
 * `roomId` is also the sync wire key. `secretsFound = -1` means unknown (shown as 0 until known).
 */
class DungeonRoom(firstComp: ComponentPos) {
    val comps = mutableListOf(firstComp)

    var type = RoomType.UNKNOWN
    var explored = false
    var checkmark = Checkmark.UNEXPLORED
    var shape = RoomShape.S1X1
        private set

    /** devonian room id — canonical identity + sync wire key. Null until identified. */
    var roomId: Int? = null
    var roomName: String? = null

    /** True when the identity came from a teammate's `room_match` — don't re-publish it. */
    var fromSync = false

    /**
     * Identity computed by [CeilingHashMatcher] pre-scan while this room's chunk is loaded but the
     * room isn't opened yet. Cached, NOT applied — it's only stamped onto the room (revealing name/
     * type/color) once [explored] flips, so entering a room shows its identity with no scan delay.
     */
    var pendingInfo: DungeonDataset.RoomInfo? = null

    var secretsTotal = 0
    var secretsFound = -1

    val identified: Boolean get() = roomId != null

    val doors = mutableSetOf<DungeonDoor>()

    fun addComponent(comp: ComponentPos) {
        if (comps.any { it == comp }) return
        comps.add(comp)
        comps.sortWith(compareBy({ it.x }, { it.z }))
        shape = RoomShape.fromComponents(comps)
    }

    /** North-west-most component cell — the reference corner for `room_match.pos`. */
    fun nwComponent(): ComponentPos = comps.minWith(compareBy({ it.x }, { it.z }))

    override fun toString(): String =
        "DungeonRoom[id=$roomId, name=$roomName, type=$type, shape=$shape, check=$checkmark, " +
            "secrets=$secretsFound/$secretsTotal, comps=$comps]"
}

/** A door cell between two rooms. */
class DungeonDoor(val comp: ComponentPos) {
    var type = DoorType.NORMAL
    var opened = false

    override fun toString(): String = "DungeonDoor[$comp, type=$type, opened=$opened]"
}
