package com.soulreturns.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fight clock against a synthetic Hypixel-like packet stream: a `set_time` every 20 server ticks
 * and the per-tick transaction ping, plus the two surplus pings around each attribute update of the
 * player's own entity that made the ping count run ~12% fast (f7-fight-mechanics-2026-11-11.md, section 5).
 */
class ServerClockTest {
    private val ms = 1_000_000L

    /** One second of a 20 TPS server per 20 ticks; [attributes] = tick numbers that also carry an attribute update (2 extra pings). */
    private class Stream(val clock: ServerClock, val tpsNanos: Long, val attributes: Set<Long>) {
        var pings = 0L
        var wall = 0L

        /** Runs server ticks [from]..[to] (game time = tick); returns the pings counted. */
        fun run(from: Long, to: Long) {
            for (tick in from..to) {
                wall = (tick - 1) * tpsNanos + FIRST
                pings += 1 + if (tick in attributes) 2 else 0
                if (tick % 20 == 0L) clock.onSetTime(tick, wall)
            }
        }
    }

    @Test
    fun clockFollowsGameTimeWhilePingsOvercount() {
        val clock = ServerClock()
        val attributes = (1L..1200L).filter { it % 17 == 0L }.toSet()
        val s = Stream(clock, 50 * ms, attributes)
        s.run(1, 1200)
        // 1200 ticks, 70 attribute updates: 1200 + 2 * 70 pings against exactly 1200 ticks of game time.
        assertEquals(1200L + 2 * attributes.size, s.pings)
        assertEquals(1200L, clock.now(s.wall))
        assertTrue(s.pings > 1200 * 1.1, "the ping count is more than 10% fast: ${s.pings}")
    }

    @Test
    fun interpolatesBetweenPacketsAndCorrectsAtEachOne() {
        val clock = ServerClock()
        clock.onSetTime(1000, 0)
        assertEquals(1000L, clock.now(0))
        assertEquals(1000L, clock.now(49 * ms))
        assertEquals(1001L, clock.now(50 * ms))
        assertEquals(1010L, clock.now(500 * ms))
        // The next packet arrives 1.02 s later (19.6 TPS) and says 1020: the clock ran 0.4 ticks ahead, exactly corrected now.
        assertEquals(1020L, clock.now(1000 * ms))
        clock.onSetTime(1020, 1020 * ms)
        assertEquals(1020L, clock.now(1020 * ms))
        // The measured rate (19.6/s) is adopted halfway: in the next 0.5 s the clock gains fewer than 10 ticks.
        val later = clock.now(1520 * ms)!!
        assertTrue(later in 1029L..1030L, "$later")
    }

    @Test
    fun correctsBackwardsWhenTheServerLagged() {
        val clock = ServerClock()
        clock.onSetTime(1000, 0)
        assertEquals(1019L, clock.now(960 * ms))
        // The server stalled for 4 ticks: the packet carries 1016 where the clock said 1019.
        clock.onSetTime(1016, 1000 * ms)
        assertEquals(1016L, clock.now(1000 * ms))
    }

    @Test
    fun aFreezeStopsTheClockAtTheNextPacketsValue() {
        val clock = ServerClock()
        clock.onSetTime(2000, 0)
        clock.onSetTime(2020, 1000 * ms)
        // The server freezes for 3 s right after: no packet, no ticks. The clock holds at the value the next packet is due to carry.
        assertEquals(2040L, clock.now(2000 * ms))
        assertEquals(2040L, clock.now(4000 * ms))
        // The freeze ends; the next packet comes 3 s late with the value it was always going to carry: not a rate change.
        clock.onSetTime(2040, 4000 * ms)
        assertEquals(2040L, clock.now(4000 * ms))
        assertEquals(2050L, clock.now(4500 * ms))
    }

    @Test
    fun noValueBeforeTheFirstPacketAfterAResetOrWhenStale() {
        val clock = ServerClock()
        assertNull(clock.now(0))
        clock.onSetTime(500, 0)
        assertEquals(520L, clock.now(ServerClock.STALE_NANOS - 1), "a long wait holds at the next packet's value (a freeze)")
        assertNull(clock.now(ServerClock.STALE_NANOS + 1))
        clock.reset()
        assertNull(clock.now(1))
    }

    @Test
    fun aNewServersTimeReplacesTheOldOne() {
        val clock = ServerClock()
        clock.onSetTime(500_000, 0)
        clock.onSetTime(20, 1000 * ms)
        assertEquals(20L, clock.now(1000 * ms))
        assertEquals(30L, clock.now(1500 * ms), "the rate of the old server is not carried over a time jump")
    }

    @Test
    fun durationsAreRightWhereThePingCountIsNot() {
        // 10 s of fight: 200 ticks, an attribute update every 8 ticks. A phase "from tick 41 to tick 181" is 140 ticks.
        val clock = ServerClock()
        val attributes = (1L..200L).filter { it % 8 == 0L }.toSet()
        val s = Stream(clock, 50 * ms, attributes)
        var startTick = 0L
        var startPings = 0L
        var endTick = 0L
        var endPings = 0L
        for (tick in 1L..200L) {
            s.run(tick, tick)
            if (tick == 41L) { startTick = clock.now(s.wall)!!; startPings = s.pings }
            if (tick == 181L) { endTick = clock.now(s.wall)!!; endPings = s.pings }
        }
        assertEquals(140L, endTick - startTick, "set_time anchors give the phase length within the interpolation error")
        assertTrue(endPings - startPings > 150, "pings count ${endPings - startPings} for the same phase")
    }

    @Test
    fun freshUntilTheNextPacketIsOverdueAndExactCarriesTheFraction() {
        val clock = ServerClock()
        assertTrue(!clock.fresh(0))
        clock.onSetTime(3000, 0)
        assertEquals(3000.5, clock.exact(25 * ms)!!, 1e-9)
        assertTrue(clock.fresh(1000 * ms), "the next packet is due now")
        assertTrue(clock.fresh(1300 * ms), "a little late is still on time")
        // A server stall: the clock holds at +20 and reads as stale until the next packet.
        assertTrue(!clock.fresh(1500 * ms))
        assertEquals(3020L, clock.now(1500 * ms))
        clock.onSetTime(3020, 3000 * ms)
        assertTrue(clock.fresh(3000 * ms))
    }

    @Test
    fun betweenReadsAcrossAStallWhereExtrapolationRunsAhead() {
        val clock = ServerClock()
        clock.onSetTime(3000, 0)
        assertEquals(null, clock.between(500 * ms), "no packet after it yet")
        // The server stalls: 20 ticks arrive over 3 s instead of 1 s.
        clock.onSetTime(3020, 3000 * ms)
        assertEquals(3010.0, clock.between(1500 * ms)!!, 1e-9)
        assertEquals(null, clock.between(3500 * ms), "after the last packet")
        clock.reset()
        assertEquals(null, clock.between(1500 * ms))
    }

    private companion object {
        const val FIRST = 7_000_000_000L
    }
}
