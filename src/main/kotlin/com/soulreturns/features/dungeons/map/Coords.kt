package com.soulreturns.features.dungeons.map

import kotlin.math.floor

/**
 * Catacombs geometry. The dungeon is laid out on an 11×11 **component grid**: rooms occupy the
 * even (x, z) cells, doors the odd cells between them. Every room is a 31×31 block box whose
 * north-west corner starts at world (-200, -200); adjacent rooms are 1 block of door gap apart,
 * so component cells are half a room+door (16 blocks) apart in the world.
 *
 * Two index spaces derive from the component grid:
 *  - room index `0..35`  — the 6×6 room grid (`roomIndex`)
 *  - door index `0..59`  — the interleaved door cells (`doorIndex`)
 */
object DungeonGeometry {
    const val CORNER_X = -200
    const val CORNER_Z = -200
    const val ROOM_SIZE = 31
    const val COMBINED_SIZE = ROOM_SIZE + 1
    const val HALF_ROOM = ROOM_SIZE / 2
    const val HALF_COMBINED = COMBINED_SIZE / 2
    const val ROOM_GRID = 6
    const val COMPONENT_GRID = 11
}

/** A cell on the 11×11 component grid. */
data class ComponentPos(val x: Int, val z: Int) {
    val isValid: Boolean get() = x in 0..10 && z in 0..10
    val isRoomCell: Boolean get() = (x and 1) == 0 && (z and 1) == 0
    val isDoorCell: Boolean get() = ((x and 1) xor (z and 1)) == 1

    /** World position of this component's center block. */
    fun worldCenterX(): Int = DungeonGeometry.CORNER_X + DungeonGeometry.HALF_ROOM + DungeonGeometry.HALF_COMBINED * x
    fun worldCenterZ(): Int = DungeonGeometry.CORNER_Z + DungeonGeometry.HALF_ROOM + DungeonGeometry.HALF_COMBINED * z

    /** Index into the 36-slot room array (only meaningful for room cells). */
    fun roomIndex(): Int = (z / 2) * DungeonGeometry.ROOM_GRID + x / 2

    /** Index into the 60-slot door array (only meaningful for door cells). */
    fun doorIndex(): Int = (((x - 1) shr 1) + DungeonGeometry.ROOM_GRID * z).let { it - it / 12 }

    /** The two room cells a door cell connects (empty list for non-door cells). */
    fun adjacentRooms(): List<ComponentPos> {
        if (!isDoorCell) return emptyList()
        return if ((x and 1) == 1) {
            listOf(ComponentPos(x - 1, z), ComponentPos(x + 1, z))
        } else {
            listOf(ComponentPos(x, z - 1), ComponentPos(x, z + 1))
        }.filter { it.isValid }
    }

    /** The up-to-4 door cells around a room cell (empty list for non-room cells). */
    fun adjacentDoors(): List<ComponentPos> {
        if (!isRoomCell) return emptyList()
        return listOf(
            ComponentPos(x, z - 1),
            ComponentPos(x, z + 1),
            ComponentPos(x - 1, z),
            ComponentPos(x + 1, z),
        ).filter { it.isValid }
    }

    companion object {
        /**
         * Component cell containing a world block position: every block of room k maps to room
         * cell 2k, the one-block gap after it to door cell 2k+1. Positions west/north of the grid
         * come out negative, past the last room 11+, so [isValid] is false outside the rooms.
         */
        fun fromWorld(wx: Int, wz: Int): ComponentPos =
            ComponentPos(axis(wx - DungeonGeometry.CORNER_X), axis(wz - DungeonGeometry.CORNER_Z))

        fun fromWorld(wx: Double, wz: Double): ComponentPos = fromWorld(floor(wx).toInt(), floor(wz).toInt())

        private fun axis(offset: Int): Int {
            val room = Math.floorDiv(offset, DungeonGeometry.COMBINED_SIZE)
            val gap = Math.floorMod(offset, DungeonGeometry.COMBINED_SIZE) == DungeonGeometry.ROOM_SIZE
            return 2 * room + if (gap) 1 else 0
        }
    }
}

/**
 * A player position in **component-grid units** (2.0 per room+door span) plus facing in radians
 * clockwise of +x — the space the map HUD draws in. Sourced from either the live entity or the
 * vanilla map item's decorations.
 */
data class MapPlayerPos(val x: Double, val z: Double, val rot: Double) {
    companion object {
        /**
         * One axis of a map-item decoration coordinate (-128..127, 2 units per map pixel) in
         * component-grid units (2.0 per room+gap), given the grid's pixel [offsetPx] and the room
         * pitch [gapPx] (room size + 4) derived from the entrance room. Pixel = (marker + 128) / 2,
         * as in Odin/Skyblocker. Hypixel sends whole-pixel markers (all 1,757 recorded values were
         * even, i.e. the pixel is truncated), so +1 unit centers the pixel; without it positions sit
         * ~2 blocks north-west (recording 2026-11-11: -2.2/-2.2 with the old +126, ~0.2 with +129).
         */
        fun fromMarker(marker: Int, offsetPx: Int, gapPx: Int): Double =
            ((marker + 129) / 2.0 - offsetPx) * 2.0 / gapPx

        fun fromWorld(wx: Double, wz: Double, rot: Double) = MapPlayerPos(
            (wx - DungeonGeometry.CORNER_X) / DungeonGeometry.HALF_COMBINED,
            (wz - DungeonGeometry.CORNER_Z) / DungeonGeometry.HALF_COMBINED,
            rot,
        )
    }
}
