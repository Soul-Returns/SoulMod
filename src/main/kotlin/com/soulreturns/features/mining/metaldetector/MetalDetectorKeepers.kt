package com.soulreturns.features.mining.metaldetector

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The Mines of Divan center from the four keeper armor stands. Pure (no Minecraft classes) so
 * `./gradlew test` covers it. Mechanics: rule://metaldetector.
 *
 * Hypixel names the stands (`Keeper of <X>`) only while the player is near one, but the stands
 * themselves are tracked from ~110-160 blocks. So the layout alone is matched: every stand votes for
 * the four centers it would imply as each keeper, and a center needs three keepers, or two opposite
 * ones (an adjacent pair also fits the layout rotated by 90°, i.e. a second center).
 */
object MetalDetectorKeepers {
    /** Keeper block position relative to the center (block x/z of the stand, y rounded). */
    val KEEPERS: Map<String, IntArray> =
        mapOf(
            "Diamond" to intArrayOf(-33, 0, -3),
            "Lapis" to intArrayOf(33, 0, 3),
            "Emerald" to intArrayOf(3, 0, -33),
            "Gold" to intArrayOf(-3, 0, 33),
        )
    private val OPPOSITE = setOf(setOf("Diamond", "Lapis"), setOf("Emerald", "Gold"))

    /** Center from a stand named `Keeper of <[keeper]>`, or null for an unknown keeper. */
    fun centerFromNamed(keeper: String, x: Double, y: Double, z: Double): IntArray? {
        val k = KEEPERS[keeper] ?: return null
        return intArrayOf(floor(x).toInt() - k[0], y.roundToInt() - k[1], floor(z).toInt() - k[2])
    }

    /** Center from unnamed armor stand positions (`[x, y, z]` each), or null when none/ambiguous. */
    fun centerFromLayout(stands: List<DoubleArray>): IntArray? {
        val votes = HashMap<List<Int>, MutableSet<String>>()
        for (s in stands) {
            val bx = floor(s[0]).toInt()
            val y = s[1].roundToInt()
            val bz = floor(s[2]).toInt()
            for ((name, k) in KEEPERS) {
                votes.getOrPut(listOf(bx - k[0], y - k[1], bz - k[2])) { HashSet() } += name
            }
        }
        val matched = votes.filter { (_, names) -> names.size >= 3 || (names.size == 2 && names in OPPOSITE) }
        return matched.keys.singleOrNull()?.toIntArray()
    }
}
