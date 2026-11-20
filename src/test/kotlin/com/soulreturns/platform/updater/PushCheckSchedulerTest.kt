package com.soulreturns.platform.updater

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushCheckSchedulerTest {
    /** A Random whose nextLong(bound) returns a fixed fraction of the bound. */
    private fun fixed(pick: Long) = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextLong(until: Long) = minOf(pick, until - 1)
    }

    @Test
    fun delayStaysWithinFiveToSixtySeconds() {
        assertEquals(5_000L, PushCheckScheduler(fixed(0)).onEvent(0))
        assertEquals(60_000L, PushCheckScheduler(fixed(Long.MAX_VALUE)).onEvent(0))
        repeat(200) {
            val d = PushCheckScheduler().onEvent(1_000)!!
            assertTrue(d in 5_000..60_000)
        }
    }

    @Test
    fun onlyOnePendingTrigger() {
        val s = PushCheckScheduler(fixed(10_000))
        assertEquals(15_000L, s.onEvent(0))
        assertNull(s.onEvent(1_000))
        assertEquals(15_000L, s.scheduledAt)
    }

    @Test
    fun fiveMinuteFloorCoalescesLaterEvents() {
        val s = PushCheckScheduler(fixed(0))
        s.onEvent(0)
        s.started(5_000)
        // event 1 minute after the run: runs at the end of the window, not 5 s later
        assertEquals(5_000 + 300_000 - 65_000, s.onEvent(65_000))
        assertNull(s.onEvent(70_000))
        s.started(305_000)
        // outside the window the plain jitter applies
        assertEquals(5_000L, s.onEvent(700_000))
    }

    @Test
    fun malformedIgnored() {
        assertTrue(PushCheckScheduler.wellFormed("0.2.2", "stable"))
        assertFalse(PushCheckScheduler.wellFormed(null, "stable"))
        assertFalse(PushCheckScheduler.wellFormed("0.2.2", ""))
    }
}
