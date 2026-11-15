package com.soulreturns.features.dungeons

/**
 * Catacombs floor, parsed from the scoreboard's `The Catacombs (F5)` line. `roomsW`/`roomsH` are
 * the floor's room-grid dimensions — the map-item scanner needs them to place the 128px map's
 * offsets, and the HUD uses them to size the drawn grid.
 */
enum class DungeonFloor(val shortName: String, val roomsW: Int, val roomsH: Int) {
    NONE("", 0, 0),
    E("E", 4, 4),
    F1("F1", 4, 5),
    F2("F2", 5, 5),
    F3("F3", 5, 5),
    F4("F4", 6, 5),
    F5("F5", 6, 6),
    F6("F6", 6, 6),
    F7("F7", 6, 6),
    M1("M1", 4, 5),
    M2("M2", 5, 5),
    M3("M3", 5, 5),
    M4("M4", 6, 5),
    M5("M5", 6, 6),
    M6("M6", 6, 6),
    M7("M7", 6, 6);

    companion object {
        fun fromShortName(name: String): DungeonFloor {
            for (floor in entries) if (floor.shortName == name) return floor
            return NONE
        }
    }
}
