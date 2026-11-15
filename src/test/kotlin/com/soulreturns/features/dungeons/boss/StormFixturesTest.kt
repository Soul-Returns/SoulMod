package com.soulreturns.features.dungeons.boss

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The expected outputs of docs/research/storm-crush-2026-11-12.md §5.4 on the four F7 recordings
 * with `set_time` (R2–R5), replayed through the server clock and [StormModel]. Ticks are relative to
 * `Pathetic Maxor` (P). The spec reads ticks by interpolating between the `set_time` packets on both
 * sides; live (and here) the clock can only extrapolate from the last one, which differs by 0 to −1
 * tick (rarely more), so "late by" values allow ±0.10 s.
 */
class StormFixturesTest {
    private val runs = listOf("r2", "r3", "r4", "r5", "r6").associateWith { StormReplay(it) }

    private fun run(name: String) = runs.getValue(name)

    private fun result(name: String, end: Long): CrushResult {
        val r = run(name)
        return assertNotNull(r.resultFor(end), "$name: no window ending at P+$end\n${r.dump()}")
    }

    private fun assertCrushed(name: String, end: Long, crushAt: Long, check: Int? = null) {
        val r = result(name, end)
        assertEquals(CrushResult.CRUSHED, r.kind, "$name P+$end: ${r.text}")
        assertTrue(abs(r.tick - run(name).p - crushAt) <= 1, "$name crush at P+${r.tick - run(name).p}, expected P+$crushAt")
        if (check != null) assertEquals("Crushed (check $check)", r.text)
    }

    private fun assertLate(name: String, end: Long, seconds: Double) {
        val r = result(name, end)
        assertEquals(CrushResult.LATE, r.kind, "$name P+$end: ${r.text}")
        val late = Regex("Late by ([0-9.]+) s").find(r.text)!!.groupValues[1].toDouble()
        assertTrue(abs(late - seconds) <= 0.101, "$name P+$end: ${r.text}, expected about $seconds s")
    }

    private fun assertEdge(name: String, end: Long, depth: Double) {
        val r = result(name, end)
        assertEquals(CrushResult.EDGE, r.kind, "$name P+$end: ${r.text}")
        val d = Regex("Edge: ([0-9.]+) b").find(r.text)!!.groupValues[1].toDouble()
        assertTrue(abs(d - depth) <= 0.11, "$name P+$end: ${r.text}, expected $depth b")
    }

    @Test
    fun patheticAnchorsTheGrid() {
        for ((name, gt) in listOf("r2" to 3820L, "r3" to 4892L, "r4" to 4245L, "r5" to 3368L)) {
            val r = run(name)
            assertTrue(abs(r.p - gt) <= 1, "$name P=${r.p}, expected $gt")
            assertEquals(r.model.pathetic, r.model.padAnchor, "$name: the pad grid stays on P")
            assertTrue(abs(r.model.pathetic!! - r.p) <= 2, "$name: P read again between the set_time packets moves at most 2")
            assertTrue(r.model.padCalibrated, "$name: a pad step confirmed the grid")
        }
    }

    @Test
    fun r2() {
        assertCrushed("r2", 680, 659, check = 2)
        val ws = result("r2", 840)
        assertEquals(CrushResult.FAR, ws.kind, ws.text)
        assertEquals("Storm never closer than 9.8 b", ws.text)
        assertCrushed("r2", 1080, 1079, check = 3)
    }

    @Test
    fun r3() {
        assertCrushed("r3", 680, 659)
        assertLate("r3", 1180, 0.10)
        // Spec: entry P+1881, late by 0.05 s. The recorded position at P+1880 is already 0.65 inside
        // (no crush came), so it settles as an edge case; both name the same near miss.
        val ws = result("r3", 1880)
        assertTrue(ws.kind == CrushResult.EDGE || ws.kind == CrushResult.LATE, ws.text)
        assertLate("r3", 2960, 0.15)
        assertCrushed("r3", 3500, 3499, check = 3)
    }

    @Test
    fun r4() {
        assertCrushed("r4", 660, 660, check = 3)
        assertLate("r4", 860, 2.35)
        assertLate("r4", 1560, 0.30)
        assertCrushed("r4", 1820, 1800, check = 2)
    }

    @Test
    fun r4StallMovesNothing() {
        val r = run("r4")
        assertTrue(r.staleTicks.isNotEmpty(), "the 3.15 s stall reads as a stale clock")
        // The P line is read again between the set_time packets around it (a tick or two); after that nothing moves.
        assertTrue(r.anchors.filter { it.first > r.p + 40 }.all { it.second == r.model.pathetic }, "no re-anchor during or after the stall")
    }

    @Test
    fun r5() {
        assertCrushed("r5", 680, 659)
        assertEdge("r5", 1140, 0.8)
        assertLate("r5", 1460, 0.40)
        assertEdge("r5", 2180, 0.45)
        assertCrushed("r5", 2820, 2819, check = 3)
    }

    @Test
    fun gigaOneTitlesAndStrike() {
        for (name in listOf("r2", "r3", "r4", "r5")) {
            val r = run(name)
            val firstFour = r.titles.filter { it.second.trim().toIntOrNull() != null }.take(4)
            assertEquals(listOf("4", "3", "2", "1"), firstFour.map { it.second.trim() }, name)
            for ((i, at) in listOf(424L, 449L, 474L, 499L).withIndex()) {
                assertTrue(abs(firstFour[i].first - r.p - at) <= 2, "$name title ${4 - i} at P+${firstFour[i].first - r.p}, expected P+$at")
            }
            val hit = r.chat.first { it.second.contains("Giga Lightning hit you") }.first - r.p
            assertTrue(hit in 518..521, "$name Giga 1 hit at P+$hit")
        }
    }

    @Test
    fun laterGigaFromItsTitles() {
        val r = run("r3")
        val six = r.titles.first { it.second.trim() == "6" && it.first - r.p > 3000 }.first
        assertTrue(abs(six - r.p - 3049) <= 1, "title 6 at P+${six - r.p}")
        val hit = r.chat.first { it.second.contains("Giga Lightning hit you") && it.first - r.p > 3100 }.first
        assertTrue(abs(hit - r.p - 3194) <= 1, "hit at P+${hit - r.p}")
        assertTrue(r.logs.any { it.contains("Giga title 6 P+") && Regex("strike P\\+319[3-5]").containsMatchIn(it) }, r.logs.joinToString("\n"))
    }

    @Test
    fun goldorDeathLineOnTheGrid() {
        val r = run("r5")
        val wd = assertNotNull(r.model.whoDares)
        assertTrue(abs(wd - 6316) <= 1, "Who dares at $wd")
        val line = r.chat.first { it.second == StormModel.DEATH_ZONE }.first
        assertTrue(abs(line - wd - 3179) <= 1, "death line at WD+${line - wd}")
        // F7: `Who dares` 46 after the floor collapse, so Goldor starts at the line (then `Little ants` − 3, WD+58);
        // the check sits on that grid, not on the collapse's (16.5 off).
        assertEquals(GoldorSource.ANTS, r.model.goldorSource)
        assertTrue(abs(r.model.goldorChecks.single()[1]) <= 2, "check ${r.model.goldorChecks.single()[1]} off the WD grid")
    }

    @Test
    fun withoutPatheticTheGridComesFromTheFirstStep() {
        // Joined late or on a floor whose anchor is unconfirmed: the first pad step sets the grid.
        for (name in listOf("r3", "r5")) {
            val f7 = run(name)
            val r = StormReplay(name, BossTimings.M7, dropPathetic = true)
            assertTrue(r.model.padCalibrated)
            val phase = Math.floorMod(r.model.padAnchor!! - f7.p, 20L)
            assertTrue(phase <= 1 || phase >= 19, "$name: grid phase $phase against P")
            val crushes = r.results.filter { it.kind == CrushResult.CRUSHED }
            val expected = f7.results.filter { it.kind == CrushResult.CRUSHED }
            assertEquals(expected.map { it.pillar to it.tick }, crushes.map { it.pillar to it.tick }, name)
            for ((a, b) in expected.zip(crushes)) assertTrue(abs(a.windowEnd!! - b.windowEnd!!) <= 1, "$name ${a.pillar}: window ${b.windowEnd} vs ${a.windowEnd}")
        }
    }

    /** "high phase A=P+…" and "left the top P+…" log ticks. */
    private fun highPhases(name: String): List<Long> = run(name).logs.mapNotNull { Regex("high phase A=P\\+(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }

    private fun leftTop(name: String): List<Long> = run(name).logs.mapNotNull { Regex("left the top P\\+(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }

    @Test
    fun highPhasesFromTheAscentOncePerPhase() {
        // §2.3 / §9 on the doc clock; this clock reads 0-3 lower in R6 (the fireballs show the same offset).
        val expected = mapOf("r3" to listOf(1634L, 2630L, 3222L), "r4" to listOf(1605L), "r5" to listOf(1651L), "r6" to listOf(2630L, 3757L, 5644L))
        for ((name, a) in expected) {
            val got = highPhases(name)
            assertEquals(a.size, got.size, "$name high phases $got, expected $a\n${run(name).dump()}")
            for ((e, g) in a.zip(got)) assertTrue(g - e in -3..1, "$name A=P+$g, expected P+$e")
            // Leaving the top, on the same clock: L = A + 162 within the track's 2-tick steps.
            val left = leftTop(name)
            assertEquals(a.size, left.size, "$name left the top $left")
            for ((g, l) in got.zip(left)) assertTrue(l - g in 160..164, "$name left the top at A+${l - g}")
        }
    }

    @Test
    fun highPhaseFireballsOnlyAndOnTheirGrid() {
        // §2.3's table (doc clock); this clock reads 0-3 lower in R6. The opening flight's are not logged.
        val doc = mapOf(
            "r3" to listOf(1675L, 1715L, 1755L, 2675L, 2715L, 2755L, 3275L, 3315L, 3355L),
            "r6" to listOf(2671L, 2712L, 2751L, 3791L, 3829L, 3871L, 3911L, 5671L, 5711L, 5751L, 5791L),
        )
        for ((name, expected) in doc) {
            val shots = run(name).logs.mapNotNull { Regex("fireball P\\+(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
            assertEquals(expected.size, shots.size, "$name $shots")
            for ((e, g) in expected.zip(shots)) assertTrue(g - e in -3..1, "$name fireball P+$g, expected P+$e: $shots")
        }
    }

    @Test
    fun r6() {
        val r = run("r6")
        val retired = r.logs.mapNotNull { Regex("pillar (\\w+) retired P\\+(\\d+)").find(it)?.groupValues?.let { g -> g[1] to g[2].toLong() } }
        assertEquals(listOf("E", "WS"), retired.map { it.first }, "only the loud door sounds retire (§8.1)\n${r.dump()}")
        assertTrue(abs(retired[1].second - 5898) <= 2, "WS retired at P+${retired[1].second}")
        val e = result("r6", 678)
        assertEquals(CrushResult.CRUSHED, e.kind, e.text)
        assertEquals("E", e.pillar)
        // P read between the set_time packets around it is a tick earlier than the line's live tick: the grid reads P+20k-1 → P+20k.
        assertTrue(r.logs.any { it.contains("window E: Crushed (check 2) (last down poll P+619, end P+679)") }, r.dump())
        val crushes = r.results.filter { it.kind == CrushResult.CRUSHED }
        assertEquals(listOf("E", "WS"), crushes.map { it.pillar }, "crush 2 belongs to yellow, where Storm stood (§8.2)\n${r.dump()}")
        // Doc clock P+5878; this clock reads up to 2 lower here (the live log said P+5876).
        assertTrue(abs(crushes[1].tick - r.p - 5877) <= 1, "crush 2 at P+${crushes[1].tick - r.p}")
    }
}
