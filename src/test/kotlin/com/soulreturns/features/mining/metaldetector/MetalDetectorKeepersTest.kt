package com.soulreturns.features.mining.metaldetector

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * Armor stands the client had in the Crystal Hollows when the last keeper loaded (capture
 * `2026-11-07_15-33-19`, player ~103 blocks away, no names yet; center (630, 90, 416)). Each keeper is
 * two stands (y 90 and 89.75); the other 28 are decoys (holograms, NPC parts elsewhere).
 */
class MetalDetectorKeepersTest {
    private fun s(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

    private val center = intArrayOf(630, 90, 416)
    private val diamond = listOf(s(597.5, 90.0, 413.5), s(597.5, 89.75, 413.5))
    private val lapis = listOf(s(663.5, 90.0, 419.5), s(663.5, 89.75, 419.5))
    private val emerald = listOf(s(633.5, 90.0, 383.5), s(633.5, 89.75, 383.5))
    private val gold = listOf(s(627.5, 90.0, 449.5), s(627.5, 89.75, 449.5))
    private val keepers = diamond + lapis + emerald + gold

    private val live =
        listOf(
            s(547.5, 110.875, 497.5),
            s(547.5, 110.5, 497.5),
            s(547.5, 110.125, 497.5),
            s(547.5, 110.875, 529.5),
            s(547.5, 110.5, 529.5),
            s(547.5, 110.125, 529.5),
            s(530.5, 106.25, 556.5),
            s(530.5, 106.0, 556.5),
            s(530.5, 105.75, 556.5),
            s(513.5, 108.0, 557.5),
            s(513.5, 107.625, 557.5),
            s(495.5, 106.0, 556.5),
            s(495.5, 105.75, 556.5),
            s(479.5, 110.875, 529.5),
            s(479.5, 110.5, 529.5),
            s(479.5, 110.125, 529.5),
            s(475.5, 106.25, 513.5),
            s(475.5, 106.0, 513.5),
            s(475.5, 105.75, 513.5),
            s(475.5, 106.25, 513.5),
            s(475.5, 106.0, 513.5),
            s(475.5, 105.75, 513.5),
            s(479.5, 110.875, 497.5),
            s(479.5, 110.5, 497.5),
            s(479.5, 110.125, 497.5),
            s(513.5, 110.875, 478.5),
            s(513.5, 110.5, 478.5),
            s(513.5, 110.125, 478.5),
            s(627.5, 90.0, 449.5),
            s(627.5, 89.75, 449.5),
            s(597.5, 90.0, 413.5),
            s(597.5, 89.75, 413.5),
            s(633.5, 90.0, 383.5),
            s(633.5, 89.75, 383.5),
            s(663.5, 90.0, 419.5),
            s(663.5, 89.75, 419.5)
        )
    private val decoys = live.filter { l -> keepers.none { it.contentEquals(l) } }

    @Test
    fun liveStandsGiveTheCenter() {
        assertContentEquals(center, MetalDetectorKeepers.centerFromLayout(live))
    }

    @Test
    fun threeKeepersAmongDecoys() {
        assertContentEquals(center, MetalDetectorKeepers.centerFromLayout(decoys + diamond + emerald + gold))
        assertContentEquals(center, MetalDetectorKeepers.centerFromLayout(decoys + lapis.take(1) + emerald.take(1) + gold.take(1)))
    }

    @Test
    fun oppositePairIsEnough() {
        assertContentEquals(center, MetalDetectorKeepers.centerFromLayout(decoys + diamond + lapis))
        assertContentEquals(center, MetalDetectorKeepers.centerFromLayout(decoys + emerald + gold))
    }

    @Test
    fun adjacentPairIsAmbiguous() {
        // Diamond + Gold also fit Emerald + Lapis around (594, 90, 446).
        assertNull(MetalDetectorKeepers.centerFromLayout(decoys + diamond + gold))
    }

    @Test
    fun decoysAloneGiveNothing() {
        assertNull(MetalDetectorKeepers.centerFromLayout(decoys))
        assertNull(MetalDetectorKeepers.centerFromLayout(emptyList()))
    }

    @Test
    fun namedStand() {
        assertContentEquals(center, MetalDetectorKeepers.centerFromNamed("Diamond", 597.5, 90.0, 413.5))
        assertContentEquals(center, MetalDetectorKeepers.centerFromNamed("Gold", 627.5, 89.75, 449.5))
        assertNull(MetalDetectorKeepers.centerFromNamed("Ruby", 0.0, 0.0, 0.0))
    }
}
