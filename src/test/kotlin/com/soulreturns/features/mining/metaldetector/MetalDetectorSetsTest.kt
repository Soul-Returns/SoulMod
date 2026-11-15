package com.soulreturns.features.mining.metaldetector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MetalDetectorSetsTest {
    private val all = MetalDetectorLoot.TOOLS.keys.toList()

    @Test
    fun freshSessionWithThreeHeldCountsTotalOnly() {
        var s = DetectorLootStats(sessionMs = 5_000L)
        s = s.afterInventory(all.take(3).toSet())
        assertEquals(true, s.setPartial)
        s = s.afterFind(all[3], 1, all.toSet())
        assertEquals(1, s.total.sets)
        assertEquals(0, s.session.sets)
        assertEquals(0L, s.total.setTimeMs)
        assertEquals(0L, s.session.setTimeMs)
        assertEquals(1, s.session.tools)
        // tools handed in: the next full set counts with time
        s = s.afterInventory(emptySet())
        assertFalse(s.setPartial)
        s = s.copy(sessionMs = 10_000L)
        all.forEachIndexed { i, t -> s = s.afterFind(t, 1, all.take(i + 1).toSet()) }
        assertEquals(2, s.total.sets)
        assertEquals(1, s.session.sets)
        assertEquals(5_000L, s.session.lastSetMs)
        assertEquals(5_000L, s.total.setTimeMs)
    }

    @Test
    fun normalSetCountsWhenStartedEmpty() {
        var s = DetectorLootStats().afterInventory(emptySet())
        all.forEach { s = s.afterFind(it, 1, emptySet()) }
        assertEquals(1, s.session.sets)
        assertEquals(1, s.total.sets)
        assertEquals(true, s.setPartial)
    }
}
