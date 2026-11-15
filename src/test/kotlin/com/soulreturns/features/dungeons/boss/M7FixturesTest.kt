package com.soulreturns.features.dungeons.boss

import com.soulreturns.features.dungeons.devices.GoldorPhaseModel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The eight M7 party runs of 2026-11-12 (recording `2026-11-12_23-48-02_m7-runs`, fixtures `m7-1`…`m7-8` cut
 * by `tools/devbridge/storm-fixtures`) against docs/research/m7-boss-2026-11-13.md: the 16 crushes of §1.3
 * (two had no chat line, three a late one), the Goldor death-zone checks of §2.3 and the opening noise windows.
 */
class M7FixturesTest {
    private val runs = (1..8).associateWith { StormReplay("m7-$it", BossTimings.M7) }

    private class Crush(val pillar: String, val at: Long, val check: Int)

    /** §1.3: crush tick (the check the pillar destruction points at, P-relative) and its check. */
    private val crushes = mapOf(
        1 to listOf(Crush("E", 660, 2), Crush("WS", 820, 1)),
        2 to listOf(Crush("E", 660, 2), Crush("WS", 800, 1)),
        3 to listOf(Crush("E", 660, 1), Crush("WS", 960, 1)),
        4 to listOf(Crush("E", 660, 2), Crush("WS", 860, 2)),
        5 to listOf(Crush("E", 720, 1), Crush("WS", 980, 1)),
        6 to listOf(Crush("WS", 840, 1), Crush("WN", 1020, 2)),
        7 to listOf(Crush("WS", 860, 1), Crush("WN", 1560, 1)),
        8 to listOf(Crush("E", 740, 1), Crush("WS", 900, 2)),
    )

    @Test
    fun everyCrushOnItsPillarAndCheckWithOrWithoutTheLine() {
        for ((n, want) in crushes) {
            val r = runs.getValue(n)
            val m = r.model
            val p = m.pathetic!!
            val got = m.pillars.filter { it.crushTick != null }.sortedBy { it.crushTick }
            assertEquals(want.map { it.pillar }, got.map { it.pillar.id }, "M$n crushed pillars\n${r.dump()}")
            for ((w, g) in want.zip(got)) {
                val result = g.result!!
                assertEquals(CrushResult.CRUSHED, result.kind, "M$n ${w.pillar}: ${result.text}")
                assertEquals(w.check, result.check, "M$n ${w.pillar} check\n${r.dump()}")
                assertTrue(abs(g.crushTick!! - p - w.at) <= 3, "M$n ${w.pillar} crush at P+${g.crushTick!! - p}, expected P+${w.at}")
                // The result shows from its first signal: a line on time, the bar step (3-18 after) or the pillar (+19…27).
                assertTrue(result.tick - p - w.at <= 28, "M$n ${w.pillar} result at P+${result.tick - p}")
            }
            assertEquals(2, m.crushes, "M$n crushes counted once each\n${r.dump()}")
        }
    }

    @Test
    fun theTwoCrushesWithoutALineShowWithinTheBarStep() {
        // M4 and M8 crush 2: no chat line at all; the old model said "Inside … at check 3, no crush".
        for ((n, at) in listOf(4 to 860L, 8 to 900L)) {
            val r = runs.getValue(n)
            val ws = r.model.stateAt("WS")!!.result!!
            assertEquals(CrushResult.CRUSHED, ws.kind, ws.text)
            assertTrue(ws.tick - r.model.pathetic!! - at in 3..18, "M$n result at P+${ws.tick - r.model.pathetic!!}")
            assertTrue(r.results.none { it.pillar == "WS" && it.kind == CrushResult.EDGE }, "M$n never an edge verdict\n${r.dump()}")
        }
    }

    @Test
    fun noWindowResultBeforeStormLeavesThePark() {
        for ((n, r) in runs) {
            val p = r.model.pathetic!!
            val early = r.results.filter { (it.windowEnd ?: Long.MAX_VALUE) < p + BossTimings.M7.parkLeave }
            assertTrue(early.isEmpty(), "M$n: ${early.joinToString { it.text }}")
            assertTrue(r.logs.none { it.contains("never closer than") && it.contains("window E") && Regex("end P\\+[0-5]\\d\\d\\)").containsMatchIn(it) }, "M$n")
        }
    }

    @Test
    fun stormBelowTheFloorIsNoCrush() {
        // M7: Storm 2.0–2.5 b inside WN at y168.0–168.8 for seven checks (P+1000–1380), no crush.
        val r = runs.getValue(7)
        val p = r.model.pathetic!!
        val wn = r.results.filter { it.pillar == "WN" && it.kind != CrushResult.LATE && it.windowEnd!! - p in 1000L..1400L }
        assertEquals(listOf(CrushResult.BELOW, CrushResult.BELOW), wn.map { it.kind }, r.dump())
        assertTrue(wn.all { it.text.startsWith("Storm below the floor") }, r.dump())
    }

    @Test
    fun goldorChecksOnTheCollapseGrid() {
        // §2.3: 14 direct checks, all on the grid from the floor collapse (+61.4): research clock −2.2…+3.4, so ±4 here.
        // No check moves it ([StormModel.GOLDOR_RESYNC]).
        val counts = mapOf(1 to 0, 2 to 1, 3 to 3, 4 to 1, 5 to 2, 6 to 1, 7 to 3, 8 to 3)
        var total = 0
        for ((n, r) in runs) {
            val m = r.model
            assertTrue(m.collapseAt != null, "M$n floor collapse")
            val checks = m.goldorChecks
            assertEquals(counts.getValue(n), checks.size, "M$n direct checks\n${r.dump()}")
            assertEquals(GoldorSource.COLLAPSE, m.goldorSource, "M$n\n${r.dump()}")
            assertEquals(m.phase3.start, m.goldorAnchor)
            for (c in checks) {
                assertEquals(GoldorSource.COLLAPSE.toLong(), c[2])
                assertTrue(abs(c[1]) <= 4, "M$n check WD+${c[0] - m.whoDares!!} off the collapse grid by ${c[1]}\n${r.dump()}")
            }
            total += checks.size
        }
        assertEquals(14, total)
    }

    @Test
    fun theLateAnchorNoLongerMovesThePadGrid() {
        // M4: the live P was 4 ticks late, so two on-grid steps looked 17 off and "moved" the grid.
        for ((n, r) in runs) {
            assertTrue(r.logs.none { it.contains("pad grid moved") }, "M$n\n${r.dump()}")
            assertEquals(r.model.pathetic, r.model.padAnchor, "M$n")
        }
    }
}
