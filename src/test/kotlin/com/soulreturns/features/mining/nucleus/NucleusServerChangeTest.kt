package com.soulreturns.features.mining.nucleus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NucleusServerChangeTest {
    private val inProgress = NucleusRun(
        startClock = 1_000L,
        foundCount = 2,
        crystals = mapOf(
            "Amber" to CrystalProgress(CrystalState.PLACED, splitMs = 5_000L),
            "Topaz" to CrystalProgress(CrystalState.FOUND, splitMs = 9_000L),
        ),
        lastFoundClock = 10_000L,
    )

    @Test
    fun serverChangeMidRunCountsUntimedAndKeepsLoot() {
        assertEquals(60_000L, inProgress.runTimeAt(61_000L))
        val after = inProgress.afterServerChange()
        assertEquals(inProgress.crystals, after.crystals)
        assertEquals(2, after.foundCount)
        assertNull(after.lastFoundClock)
        assertNull(after.runTimeAt(61_000L))

        val counts = NucleusCounts().withLoot(listOf(NucleusLoot("Gemstone Powder", 3)))
        assertEquals(1L, counts.runs)
        assertEquals(3L, counts.loot["Gemstone Powder"])
        assertTrue(counts.runTimes.isEmpty())
    }

    @Test
    fun serverChangeBeforeAnyCrystalForgetsTheStart() {
        val after = NucleusRun(startClock = 500L).afterServerChange()
        assertNull(after.startClock)
        assertNull(after.runTimeAt(9_000L))
    }

    @Test
    fun topazFoundAfterAnotherPlayersKillMeansBalDown() {
        val found = NucleusEvent.CrystalFound("Topaz", 1, 5)
        assertEquals(BalState.Phase.DOWN to true, BalState.next(BalState.Phase.UP, 0L, found, 100_000L, false))
        assertEquals(BalState.Phase.DOWN to true, BalState.next(BalState.Phase.UNKNOWN, 0L, found, 100_000L, false))
        assertNull(BalState.next(BalState.Phase.DOWN, 0L, found, 100_000L, false))
    }
}
