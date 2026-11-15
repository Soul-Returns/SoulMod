package com.soulreturns.features.dungeons.boss

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One of Storm's crushers: its 7x7 footprint (half-open block bounds, x/z only), the pad that drives
 * it and the corner its piston sounds play at. All from docs/research/storm-crush-2026-11-12.md
 * §1.1 and §5.0.
 */
class Pillar(
    val id: String,
    val padName: String,
    val x0: Int,
    val z0: Int,
    val padX0: Int,
    val padZ0: Int,
    val cornerX: Int,
    val cornerZ: Int,
) {
    /** What players call it: the pad's color (Purple, Yellow, Green, Red). Compass ids stay in logs. */
    val name: String get() = padName.replaceFirstChar { it.uppercaseChar() }

    val x1 get() = x0 + SIZE
    val z1 get() = z0 + SIZE

    /** How far inside the footprint (x/z) a point is: > 0 inside (distance to the nearest edge), else -(distance to the footprint). */
    fun depth(x: Double, z: Double): Double {
        val inside = min(min(x - x0, x1 - x), min(z - z0, z1 - z))
        if (inside > 0) return inside
        val dx = max(max(x0 - x, x - x1), 0.0)
        val dz = max(max(z0 - z, z - z1), 0.0)
        return -hypot(dx, dz)
    }

    fun onPad(x: Double, y: Double, z: Double): Boolean =
        x >= padX0 && x < padX0 + SIZE && z >= padZ0 && z < padZ0 + SIZE && y >= PAD_Y_MIN && y <= PAD_Y_MAX

    companion object {
        const val SIZE = 7
        const val PAD_Y_MIN = 169.5
        const val PAD_Y_MAX = 171.5
    }
}

object StormGeometry {
    val E = Pillar("E", "purple", 97, 62, 111, 91, 97, 64)
    val WS = Pillar("WS", "yellow", 43, 62, 29, 91, 43, 64)
    val WN = Pillar("WN", "green", 43, 38, 29, 9, 43, 40)
    val EN = Pillar("EN", "red", 97, 38, 111, 9, 97, 40)
    val ALL = listOf(E, WS, WN, EN)

    /** Pillar bottoms: start, floor (the lowest bottom), top. */
    const val BOTTOM_START = 175
    const val BOTTOM_FLOOR = 169
    const val BOTTOM_TOP = 189

    /** Where Storm is added at the P2 start, his crush-1 park spot and his Giga Lightning spot. */
    val SPAWN = doubleArrayOf(103.0, 188.0, 53.0)
    val PARK = doubleArrayOf(102.4, 183.0, 52.4)
    val GIGA = doubleArrayOf(72.3, 179.9, 53.3)

    /** The pillar whose piston corner is at block (x, z), or null. */
    fun pillarAtCorner(x: Int, z: Int): Pillar? = ALL.firstOrNull { it.cornerX == x && it.cornerZ == z }

    fun pillar(id: String): Pillar? = ALL.firstOrNull { it.id == id }
}
