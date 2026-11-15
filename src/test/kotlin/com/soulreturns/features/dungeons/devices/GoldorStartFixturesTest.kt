package com.soulreturns.features.dungeons.devices

import com.soulreturns.features.dungeons.boss.GoldorSections
import com.soulreturns.features.dungeons.boss.StormModel
import com.soulreturns.features.dungeons.boss.lateFixture
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Goldor's phase start T ([GoldorPhaseModel]) on 25 real fights (`tools/devbridge/phase3-fixtures`, `boss/p3/`): the
 * 8 M7 party runs of 2026-11-12 and 11 F7 solo runs of 2026-11-12/13 on the `set_time` clock, and the 6 M7 party runs of
 * 2026-11-13 (`m7r2-*`) on the lone-ping clock, one tick per server tick (docs/research/m7-boss-2026-11-13.md,
 * "Terminal phase start" and "Round 2"). Fed as the glue does; `set_time` checks are read between the packets around them.
 */
class GoldorStartFixturesTest {
    private val m7 = (1..8).map { "m7-$it" }
    private val f7 = listOf("f7-0027", "f7-0144", "f7-0219", "f7-0249", "f7-0404", "f7-0620-1", "f7-0620-2", "f7-1726", "f7-1918", "f7-2242", "f7-0432")
    private val m7r2 = (1..6).map { "m7r2-$it" }

    private class Run(val name: String) {
        val model = GoldorPhaseModel()
        /** What the countdown aimed at right after Storm's death strike. */
        var aimed: Long? = null
        var endLine: Long? = null
        /** Death-zone lines (the server's checks) and everything the server accepted from anyone: own terminal menus, n/N lines. */
        val checks = ArrayList<Double>()
        /** The same checks on the live clock (whole ticks). */
        val checkTicks = ArrayList<Long>()
        val accepted = ArrayList<Pair<Long, String>>()

        init {
            var y: Double? = null
            var over = false
            for (e in lateFixture("p3/$name")) {
                val tick = e.tick ?: continue
                if (e.k == "self") y = e.y
                if (e.k == "strike") {
                    model.strike(tick)
                    if (aimed == null && model.stormDeath != null) aimed = model.expected()
                }
                if (e.k == "falling") model.collapse(tick, y?.let { GoldorPhaseModel.inArea(it) } == true)
                if (over) continue
                if (e.k == "open") accepted += tick to "menu ${e.s}"
                if (e.k != "chat") continue
                model.chat(e.s, tick)
                if (e.s == GoldorPhaseModel.STORM_END && endLine == null) endLine = tick
                if (e.s == StormModel.CORE_OPEN) over = true
                if (model.whoDares != null && e.s == StormModel.DEATH_ZONE) {
                    checks += e.exact ?: tick.toDouble()
                    checkTicks += tick
                }
                if (GoldorSections.COUNTER.matches(e.s)) accepted += tick to e.s
            }
        }

        val start: Long get() = assertNotNull(model.start, "$name: no start")
        val collapse: Long get() = assertNotNull(model.collapse, "$name: no collapse")
    }

    private val runs = (m7 + f7 + m7r2).associateWith { Run(it) }

    @Test
    fun theCountdownFromStormsDeathAimsAtItPlus61() {
        // The strike is on time (F7: the end line in the same tick 11/11). The collapse follows it by 59…60 on the
        // lone-ping clock; the set_time clock, interpolated across the collapse's burst, reads 57.6…60.5.
        for ((n, r) in runs) {
            val d = assertNotNull(r.model.stormDeath, "$n: Storm's death strike")
            assertEquals(d + GoldorPhaseModel.STRIKE_TO_START, r.aimed, n)
            assertTrue(r.collapse - d in 57..61, "$n: collapse D+${r.collapse - d}")
        }
        for (n in f7) assertTrue(abs(runs.getValue(n).endLine!! - runs.getValue(n).model.stormDeath!!) <= 1, "$n: end line on time")
    }

    @Test
    fun onTheTickClockTIsStormsDeathPlus61() {
        // M7 2026-11-13, lone pings: `Who dares` at D + 62 in 6/6, T = D + 61 = `Who dares` − 1; the collapse + 1 would
        // have been D + 60 in 4 of them.
        val collapses = ArrayList<Long>()
        for (n in m7r2) {
            val r = runs.getValue(n)
            val d = r.model.stormDeath!!
            assertEquals(62L, r.model.whoDares!! - d, "$n: `Who dares` after the strike")
            assertEquals(d + 61, r.start, n)
            assertTrue(r.model.fromCollapse, n)
            collapses += r.collapse - d
        }
        assertEquals(listOf(60L, 60L, 59L, 59L, 59L, 59L), collapses)
    }

    @Test
    fun onTheTickClockDeathZoneLinesSitOnTPlus60k() {
        // 8 death-zone lines (client-thread arrival, as the chat event sees them): 7 on T + 60k, one a tick early (M7 run 4,
        // the user's own death in the same tick).
        val offs = m7r2.flatMap { n -> runs.getValue(n).let { r -> r.checkTicks.map { Math.floorMod(it - r.start + 30, 60L) - 30 } } }
        assertEquals(8, offs.size)
        assertTrue(offs.all { it in -1L..0L }, "offsets $offs")
        assertEquals(7, offs.count { it == 0L }, "offsets $offs")
    }

    @Test
    fun m7StartsAtStormsDeathPlus61WhileYouStandInSectionOne() {
        for (n in m7 + m7r2) {
            val r = runs.getValue(n)
            // Never before the collapse + 1: the live set_time clock read m7-3's collapse at D + 60.
            assertEquals(maxOf(r.model.stormDeath!! + GoldorPhaseModel.STRIKE_TO_START, r.collapse + 1), r.start, n)
            assertTrue(r.model.fromCollapse, n)
            assertTrue(r.model.whoDares!! - r.start in 1..16, "$n: `Who dares` came ${r.model.whoDares!! - r.start} after T")
        }
    }

    @Test
    fun f7SoloStartsWhenYouDropIn() {
        // Alone on the Storm floor at the collapse: Goldor's phase waits for the player (the line as the fall passes y 134.7–138.2).
        for (n in f7) {
            val r = runs.getValue(n)
            assertEquals(r.model.whoDares!! + GoldorPhaseModel.WD_TO_START, r.start, n)
            assertTrue(r.start - r.collapse in GoldorPhaseModel.COLLAPSE_LOOK..60, "$n: T ${r.start - r.collapse} after the collapse")
        }
    }

    @Test
    fun everyDeathZoneCheckSitsOnTPlus60k() {
        var total = 0
        for ((n, r) in runs) {
            for (c in r.checks) {
                val off = (c - r.start).let { it - 60.0 * (it / 60.0).roundToLong() }
                assertTrue(abs(off) <= 4.0, "$n: check T+${c - r.start} is ${"%.1f".format(off)} off T + 60k")
                total++
            }
        }
        assertEquals(26, total, "M7 12 + F7 6 + M7 (tick clock) 8 death-zone lines")
    }

    @Test
    fun nothingWasAcceptedBeforeT() {
        // Own terminal menus and anyone's terminal / lever / device line: never before T (25 fights).
        for ((n, r) in runs) {
            assertTrue(r.accepted.isNotEmpty(), n)
            for ((t, what) in r.accepted) assertTrue(t >= r.start, "$n: $what at T${t - r.start}")
        }
    }
}
