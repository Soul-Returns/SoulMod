package com.soulreturns.features.sacks

import kotlin.test.Test
import kotlin.test.assertEquals

class PendingMenuDeltasTest {
    // `[Sacks] -64 items. (Last 30s.)` hover line `-64 Lily Pad (Fishing Sack)`.
    private val line = SackParsing.summaryLines(listOf("Removed:\n-64 Lily Pad (Fishing Sack)")).single()

    @Test
    fun manualRemovalIsConsumedByTheDelayedSummary() {
        val pending = PendingMenuDeltas()
        pending.record("WATER_LILY", -64, 1_000)
        assertEquals(-64, pending.consume("WATER_LILY", line.delta, 31_000, 30_000))
        assertEquals(0, pending.consume("WATER_LILY", line.delta, 32_000, 30_000))
    }

    @Test
    fun removalOnlyChatReportsStillCounts() {
        val pending = PendingMenuDeltas()
        assertEquals(0, pending.consume("WATER_LILY", -64, 31_000, 30_000))
    }

    @Test
    fun partialAndSignMismatch() {
        val pending = PendingMenuDeltas()
        pending.record("A", -10, 1_000)
        pending.record("A", 5, 2_000)
        assertEquals(-10, pending.consume("A", -64, 30_000, 30_000))
        assertEquals(3, pending.consume("A", 3, 30_000, 30_000))
        assertEquals(2, pending.consume("A", 9, 30_000, 30_000))
    }

    @Test
    fun entriesOutsideTheWindowAreIgnored() {
        val pending = PendingMenuDeltas()
        pending.record("A", -10, 1_000)
        assertEquals(0, pending.consume("A", -10, 60_000, 30_000))
    }
}
