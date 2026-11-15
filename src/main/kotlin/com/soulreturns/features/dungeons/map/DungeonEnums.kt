package com.soulreturns.features.dungeons.map

/** Room classification, set from the vanilla map item's pixel color. */
enum class RoomType {
    ENTRANCE,
    NORMAL,
    RARE,
    PUZZLE,
    TRAP,
    MINIBOSS,
    FAIRY,
    BLOOD,
    UNKNOWN,
}

/** Room footprint. `wire` is a short label (used in debug output). */
enum class RoomShape(val wire: String) {
    S1X1("1x1"),
    S1X2("1x2"),
    S1X3("1x3"),
    S1X4("1x4"),
    S2X2("2x2"),
    L("L-shape"),
    UNKNOWN("1x1");

    companion object {
        /** Shape from a room's component cells (the merged even-grid cells it spans). */
        fun fromComponents(comps: List<ComponentPos>): RoomShape {
            val xs = comps.map { it.x }.distinct().size
            val zs = comps.map { it.z }.distinct().size
            return when {
                comps.isEmpty() || comps.size > 4 -> UNKNOWN
                comps.size == 1 -> S1X1
                comps.size == 2 -> S1X2
                comps.size == 4 -> if (xs == 1 || zs == 1) S1X4 else S2X2
                xs == comps.size || zs == comps.size -> S1X3
                else -> L
            }
        }
    }
}

/** Hypixel's checkmark state, read off the vanilla map item's center pixel. */
enum class Checkmark {
    UNEXPLORED,
    NONE,
    WHITE,
    GREEN,
    FAILED,
}

enum class DoorType {
    NORMAL,
    WITHER,
    BLOOD,
    ENTRANCE,
}
