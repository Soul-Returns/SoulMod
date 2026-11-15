package com.soulreturns.features.dungeons.boss

import com.soulreturns.features.dungeons.devices.GoldorPhaseModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The crosshair timers' fixed places against `mocks/boss-timers/` (layout A). [MockRun] replays the
 * mock's scripted Storm phase (model.js `STORM`) into a [StormModel]; the real-run tests replay
 * recording `2026-11-12_06-20-48_f7-ring-timers` (fixture r7, the mock's `REAL`). Every expectation
 * is what the mock rendered at that tick, read from its DOM: the text of each place and its color.
 */
class CrosshairTimersTest {
    /** The mock's run on the P clock (P = [P]): stands, piston polls, Storm's distance tracks, Giga 2, the high phase, crushes. */
    private class MockRun(val missPurple: Boolean = false) {
        val m = StormModel(BossTimings.F7)
        private val stands = listOf(
            Triple(StormGeometry.E, 604, 627), Triple(StormGeometry.WS, 788, 814), Triple(StormGeometry.WS, 1062, 1078),
            Triple(StormGeometry.WS, 1228, 1246), Triple(StormGeometry.WN, 1488, 1514), Triple(StormGeometry.WS, 1763, 1790),
        )

        /** Storm's distance to a footprint, linear between points (model.js stormTrack). */
        private class Track(val pillar: Pillar, val points: List<Pair<Int, Double>>)

        private val tracks = listOf(
            Track(StormGeometry.E, listOf(600 to 9.6, 648 to 9.6, 657 to 0.0, 700 to 0.0)),
            Track(StormGeometry.WS, listOf(780 to 27.1, 801 to 27.1, 907 to 0.0, 1200 to 0.0)),
            Track(StormGeometry.WS, listOf(1200 to 0.0, 1237 to 0.0, 1300 to 24.0)),
            Track(StormGeometry.WN, listOf(1480 to 17.2, 1501 to 17.2, 1566 to 0.0, 1590 to 0.0)),
            Track(StormGeometry.WS, listOf(1767 to 23.1, 1775 to 18.1, 1795 to 18.1, 1821 to 0.0, 1850 to 0.0)),
        )

        private fun dist(tr: Track, t: Int): Double {
            val p = tr.points
            for (i in 1 until p.size) {
                val (t0, d0) = p[i - 1]
                val (t1, d1) = p[i]
                if (t <= t1) return d0 + (d1 - d0) * (t - t0) / (t1 - t0)
            }
            return p.last().second
        }

        /** Storm on a line from the footprint's west (E) or east side at the track's distance; inside by 0.01 at 0. */
        private fun storm(t: Int): DoubleArray {
            if (t in 1601..1766) {
                // The ascent (§2.3): still until 1606, then up 0.8 b/tick to the top, hovering at (66.1, ·, 57).
                if (t < 1603) return doubleArrayOf(66.0, 175.0, 57.0)
                if (t < 1607) return doubleArrayOf(66.1, 175.0, 57.0)
                return doubleArrayOf(66.1, 175.0 + 0.8 * minOf(t - 1606, 18), 57.0)
            }
            val tr = tracks.lastOrNull { t >= it.points.first().first && t <= it.points.last().first }
            if (tr == null) return doubleArrayOf(75.0, 176.0, 55.0)
            val d = dist(tr, t)
            val p = tr.pillar
            val z = p.z0 + 3.5
            return if (p === StormGeometry.E) doubleArrayOf(p.x0 - d + 0.01, 176.0, z) else doubleArrayOf(p.x1 + d - 0.01, 176.0, z)
        }

        /** On the pad while standing; 3 blocks off its edge (near it) 50 ticks before and 20 after (model.js NEAR_BEFORE / NEAR_AFTER). */
        private fun player(t: Int): DoubleArray {
            for ((pad, from, to) in stands) {
                if (t in from until to) return doubleArrayOf(pad.padX0 + 3.5, 170.0, pad.padZ0 + 3.5)
            }
            for ((pad, from, to) in stands) {
                if (t >= from - 50 && t < to + 20) return doubleArrayOf(pad.padX0 - 3.0, 170.0, pad.padZ0 + 3.5)
            }
            return doubleArrayOf(75.0, 170.0, 55.0)
        }

        /** Downward pad polls: the first layer one tick after the poll, 4 ticks apart. */
        private val steps = mapOf(
            621 to (StormGeometry.E to 4), 801 to (StormGeometry.WS to 3), 1241 to (StormGeometry.WS to 1),
            1501 to (StormGeometry.WN to 4), 1781 to (StormGeometry.WS to 1),
        )
        private val bottoms = HashMap<Int, Int>()
        private val moved = HashMap<Pillar, Int>()

        private fun bottomOf(p: Pillar) = moved[p] ?: StormGeometry.BOTTOM_START
        private var fed = -1

        init {
            m.chat(StormModel.PATHETIC, P)
        }

        fun to(t: Int): StormModel {
            while (fed < t) {
                fed++
                val tick = P + fed
                if (fed == 0) continue
                if (missPurple && fed in 621..700) {
                    // no purple step, no crush 1
                } else {
                    for ((start, step) in steps) {
                        val (pillar, layers) = step
                        val i = fed - start
                        if (i < 0 || i % 4 != 0 || i / 4 >= layers) continue
                        val from = bottoms.getOrPut(start) { bottomOf(pillar) }
                        m.sound(StormModel.PISTON_DOWN, pillar.cornerX + 0.5, from - i / 4 + 0.5, pillar.cornerZ + 0.5, 10f, tick)
                        if (i / 4 == layers - 1) moved[pillar] = from - layers
                    }
                }
                val s = storm(fed)
                m.wither(1, s[0], s[1], s[2], tick)
                val p = player(fed)
                m.player(p[0], p[1], p[2], tick)
                if (fed == 659 && !missPurple) m.chat(StormModel.CRUSH_1, tick)
                if (fed in listOf(1662, 1702, 1742)) m.fireball(66.1, 189.4, 57.0, tick)
                if (fed >= 1290 && (fed - 1290) % 25 == 0 && fed <= 1415) m.title((6 - (fed - 1290) / 25).toString(), tick)
                if (fed == 1839) m.chat(StormModel.CRUSH_2, tick)
                m.advance(tick)
            }
            return m
        }

        fun view(t: Int, o: CrosshairOptions = CrosshairOptions(), stale: Boolean = false): PlacesView {
            val m = to(t)
            return CrosshairTimers.view(CrosshairTimers.places(m, (P + t).toDouble(), o), o, stale)
        }

        companion object {
            const val P = 10_000L
        }
    }

    /** One place as the mock's DOM reads it: its text and its color (`#rrggbb`), null = not shown. */
    private fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)

    private fun pad(v: PlacesView) = v.pad?.let { "${it.text} ${hex(it.color)}" + if (it.ring) " ring ${toFixed2(it.fraction)}" else "" }

    private fun act(v: PlacesView) = v.action?.let { "${it.text}${it.time ?: ""} ${hex(it.color)}" + if (it.alpha < 1.0) " ${toFixed2(it.alpha)}" else "" }

    private fun ph(v: PlacesView) = v.phase?.let { "${it.word} ${it.digits}${it.fireball ?: ""} ${hex(it.color)}" }

    private fun win(v: PlacesView) = v.window?.let { w ->
        val body = if (w.body == CrosshairTimers.BODY_CHECK) "check" else if (w.body == CrosshairTimers.BODY_CROSS) "cross" else w.bodyText
        "${w.label}|$body|${w.hint ?: ""}|${hex(w.color)}" + (if (w.hint != null) "|${hex(w.hintColor)}" else "") + (if (w.hot) "|hot" else "") +
            if (w.alpha < 1.0) "|${toFixed2(w.alpha)}" else ""
    }

    private fun toFixed2(v: Double) = CrosshairTimers.toFixed(v, 2)

    private class Frame(val t: Int, val pad: String?, val act: String?, val ph: String?, val win: String?)

    private fun assertFrame(want: Frame, v: PlacesView, run: String) {
        val at = "$run P+${want.t}"
        assertEquals(want.pad, pad(v), "$at pad")
        assertEquals(want.act, act(v), "$at action")
        assertEquals(want.ph, ph(v), "$at phase")
        assertEquals(want.win, win(v), "$at window")
    }

    private val scriptFrames = listOf(
        Frame(100, null, null, "LIGHTNING 21.0 #6cb8ff", null),
        Frame(300, null, null, "LIGHTNING 11.0 #6cb8ff", null),
        Frame(549, null, "STEP ON PURPLE in 2.7 #b45cff", null, null),
        Frame(560, "0.0 #55ffff ring 0.00", "STEP ON PURPLE in 2.1 #b45cff", null, null),
        Frame(602, "0.9 #55ffff ring 0.90", "STEP ON PURPLE in 0.1 #b45cff", null, null),
        Frame(603, "0.8 #55ffff ring 0.85", "STEP ON PURPLE NOW #b45cff", null, null),
        Frame(604, "0.8 #55ffff ring 0.80", "STAY 0.8 #ffffff", null, null),
        Frame(610, "0.5 #55ffff ring 0.50", "STAY 0.5 #ffffff", null, null),
        Frame(620, "0.0 #55ffff ring 0.00", "STAY 0.0 #ffffff", null, null),
        Frame(621, "0.9 #55ffff ring 0.95", "STEP OFF #55ff55", null, "PURPLE|3.0|Storm 10 b · ETA —|#b45cff|#ffffff"),
        Frame(625, "0.8 #55ffff ring 0.75", "STEP OFF #55ff55", null, "PURPLE|2.8|Storm 10 b · ETA —|#b45cff|#ffffff"),
        Frame(627, "0.7 #55ffff ring 0.65", null, null, "PURPLE|2.6|Storm 10 b · ETA —|#b45cff|#ffffff"),
        Frame(640, "0.0 #55ffff ring 0.00", null, null, "PURPLE|2.0|Storm 10 b · ETA —|#b45cff|#ffffff"),
        Frame(657, null, null, null, "PURPLE|1.1|Storm inside|#b45cff|#7dff9a"),
        Frame(659, null, null, null, "CRUSHED · CHECK 2|check||#55ff55"),
        Frame(665, null, null, null, "CRUSHED · CHECK 2|check||#55ff55"),
        Frame(715, null, null, null, "CRUSHED · CHECK 2|check||#55ff55|0.40"),
        Frame(790, "0.5 #55ffff ring 0.50", null, null, null),
        Frame(801, "0.9 #55ffff ring 0.95", null, null, "YELLOW|3.0|Storm 27 b · ETA —|#ffe14d|#ffffff"),
        Frame(808, "0.6 #55ffff ring 0.60", null, null, "YELLOW|2.6|Storm 25 b · ETA 5.0 s|#ffe14d|#ffffff"),
        Frame(812, "0.4 #55ffff ring 0.40", null, null, "YELLOW|2.4|Storm 24 b · ETA 4.8 s|#ffe14d|#ffffff"),
        Frame(813, "0.3 #55ffff ring 0.35", null, null, "YELLOW|2.4|Storm 24 b · ETA 4.7 s|#ffe14d|#ffffff"),
        Frame(820, "0.0 #55ffff ring 0.00", null, null, "YELLOW|2.0|Storm 22 b · ETA 4.3 s|#ffe14d|#ffffff"),
        Frame(1062, "0.9 #55ffff ring 0.90", null, null, null),
        Frame(1070, "0.5 #55ffff ring 0.50", null, null, null),
        Frame(1077, "0.1 #55ffff ring 0.15", null, null, null),
        Frame(1078, "0.1 #55ffff ring 0.10", null, null, null),
        Frame(1100, null, null, null, null),
        Frame(1135, null, null, null, null),
        Frame(1137, null, null, null, null),
        Frame(1138, null, null, null, null),
        Frame(1230, "0.5 #55ffff ring 0.50", null, null, null),
        Frame(1241, "0.9 #55ffff ring 0.95", null, null, "YELLOW|3.0|Storm passed 0.2 s before the step|#ffe14d|#ffb36b"),
        Frame(1250, "0.5 #55ffff ring 0.50", null, null, "YELLOW|2.5|Storm passed 0.2 s before the step|#ffe14d|#ffb36b"),
        Frame(1290, null, null, "GIGA 7.3 #6cb8ff", "YELLOW|0.5|Storm passed 0.2 s before the step|#ffe14d|#ffb36b|hot"),
        Frame(1320, null, null, "GIGA 5.8 #6cb8ff", "MISSED · YELLOW|cross|Storm passed 0.2 s before the step|#ff9d6b|#ffb36b"),
        Frame(1488, "0.6 #55ffff ring 0.60", null, null, null),
        Frame(1501, "0.9 #55ffff ring 0.95", null, null, "GREEN|3.0|Storm 17 b · ETA —|#55ff55|#ffffff"),
        Frame(1513, "0.3 #55ffff ring 0.35", null, null, "GREEN|2.4|Storm 14 b · ETA 2.6 s|#55ff55|#ffffff"),
        Frame(1520, "0.0 #55ffff ring 0.00", null, null, "GREEN|2.0|Storm 12 b · ETA 2.3 s|#55ff55|#ffffff"),
        Frame(1650, null, null, "HIGH 5.8 #ffa040", null),
        Frame(1705, null, "STEP ON YELLOW in 2.9 #ffe14d", "HIGH 3.1 #ffa040", null),
        Frame(1720, "0.0 #55ffff ring 0.00", "STEP ON YELLOW in 2.1 #ffe14d", "HIGH 2.4 #ffa040", null),
        Frame(1762, "0.9 #55ffff ring 0.90", "STEP ON YELLOW in 0.1 #ffe14d", "HIGH 0.3 #ffa040", null),
        Frame(1763, "0.8 #55ffff ring 0.85", null, "HIGH 0.2 #ffa040", null),
        Frame(1770, "0.5 #55ffff ring 0.50", null, "STORM RETURNS 1.3 #ffa040", null),
        Frame(1781, "0.9 #55ffff ring 0.95", null, "STORM RETURNS 0.7 #ffa040", "YELLOW|3.0|Bounce back · stand by the pillar|#ffe14d|#ffffff"),
        Frame(1785, "0.8 #55ffff ring 0.75", null, "STORM RETURNS 0.5 #ffa040", "YELLOW|2.8|Bounce back · stand by the pillar|#ffe14d|#ffffff"),
        Frame(1790, "0.5 #55ffff ring 0.50", null, "STORM RETURNS 0.3 #ffa040", "YELLOW|2.5|Storm 18 b · ETA —|#ffe14d|#ffffff"),
        Frame(1800, "0.0 #55ffff ring 0.00", null, null, "YELLOW|2.0|Storm 15 b · ETA 1.1 s|#ffe14d|#7dff9a"),
        Frame(1830, null, null, null, "YELLOW|0.5|Storm inside|#ffe14d|#7dff9a|hot"),
        Frame(1845, null, null, null, "CRUSHED · CHECK 3|check||#55ff55"),
        Frame(1895, null, null, null, "CRUSHED · CHECK 3|check||#55ff55|0.40"),
        Frame(1960, null, null, null, null),
    )

    private val realFrames = listOf(
        Frame(1, null, null, "LIGHTNING 25.9 #6cb8ff", null),
        Frame(4, null, null, "LIGHTNING 25.8 #6cb8ff", null),
        Frame(560, "0.0 #55ffff ring 0.00", "STEP ON PURPLE in 2.1 #b45cff", null, null),
        Frame(590, "0.5 #55ffff ring 0.50", "STEP ON PURPLE in 0.7 #b45cff", null, null),
        Frame(601, "0.9 #55ffff ring 0.95", "STEP ON PURPLE in 0.1 #b45cff", null, null),
        Frame(602, "0.9 #55ffff ring 0.90", "STAY 0.9 #ffffff", null, null),
        Frame(610, "0.5 #55ffff ring 0.50", "STAY 0.5 #ffffff", null, null),
        Frame(1000, null, null, null, null),
        Frame(1263, "0.8 #55ffff ring 0.85", "STEP ON YELLOW NOW #ffe14d", "HIGH 0.6 #ffa040", null),
        Frame(1280, "0.0 #55ffff ring 0.00", "STEP ON YELLOW NOW #ffe14d", "STORM RETURNS 1.1 #ffa040", null),
        Frame(1630, "0.5 #55ffff ring 0.50", null, null, null),
        Frame(1635, "0.3 #55ffff ring 0.25", null, null, null),
        Frame(1750, null, null, null, "LATE · GREEN|+2.15||#ff5555"),
        Frame(1800, null, null, null, "LATE · GREEN|+2.15||#ff5555|0.20"),
    )

    /** The action line's states over the whole run (digits masked as `#`): text and color from first to last tick. */
    private class Seg(val from: Int, val to: Int, val text: String)

    /** The mock's action line, without STAY / STEP OFF / TOO EARLY after the opening Purple stand (SPEC §8, 2026-11-12). */
    private val scriptActions = listOf(
        Seg(549, 602, "STEP ON PURPLE in # #b45cff"),
        Seg(603, 603, "STEP ON PURPLE NOW #b45cff"),
        Seg(604, 620, "STAY # #ffffff"),
        Seg(621, 626, "STEP OFF #55ff55"),
        Seg(1705, 1762, "STEP ON YELLOW in # #ffe14d"),
    )

    /**
     * SPEC §4 "As it happened in your run": the mock's action line on the recording, without the later stands
     * (Green P+1283, P+1630): STAY / STEP OFF only for the opening Purple stand (SPEC §8, 2026-11-12).
     */
    private val realActions = listOf(
        Seg(549, 601, "STEP ON PURPLE in # #b45cff"),
        Seg(602, 620, "STAY # #ffffff"),
        Seg(621, 631, "STEP OFF #55ff55"),
        Seg(1205, 1262, "STEP ON YELLOW in # #ffe14d"),
        Seg(1263, 1280, "STEP ON YELLOW NOW #ffe14d"),
    )

    /** The action line with its digits masked as `#`, then its color. */
    private fun masked(v: PlacesView) = v.action?.let { "${(it.text + (it.time ?: "")).replace(Regex("[\\d.]+"), "#")} ${hex(it.color)}" }

    private fun assertActions(want: List<Seg>, last: Int, run: String, viewAt: (Int) -> PlacesView) {
        for (t in 0..last) {
            val seg = want.firstOrNull { t in it.from..it.to }
            assertEquals(seg?.text, masked(viewAt(t)), "$run P+$t action line")
        }
    }

    @Test
    fun scriptedRunMatchesTheMock() {
        val r = MockRun()
        for (f in scriptFrames) assertFrame(f, r.view(f.t), "script")
    }

    @Test
    fun scriptedRunActionLineMatchesTheMockEveryTick() {
        val r = MockRun()
        assertActions(scriptActions, 1960, "script") { t -> r.view(t) }
    }

    /** Recording r7 (P+0..1830), sampled every tick as the HUD would see it. */
    private fun realRun(o: CrosshairOptions = CrosshairOptions()): Map<Int, PlacesView> {
        val out = HashMap<Int, PlacesView>()
        var p: Long? = null
        StormReplay("r7", probe = { m, tick ->
            if (p == null) p = m.pathetic
            val pp = p
            if (pp != null && tick - pp in 0..1830) {
                m.advance(tick)
                out[(tick - pp).toInt()] = CrosshairTimers.view(CrosshairTimers.places(m, tick.toDouble(), o), o, stale = false)
            }
        })
        return out
    }

    @Test
    fun realRunMatchesTheMock() {
        val v = realRun()
        for (f in realFrames) assertFrame(f, v.getValue(f.t), "real")
        assertActions(realActions, 1830, "real") { t -> v.getValue(t) }
    }

    @Test
    fun realRunWindowsAndResults() {
        // Where the model reads the recording itself (ETA from Storm's speed, results settled 4 ticks after the window): the same place, the same meaning.
        val v = realRun()
        fun label(t: Int) = v.getValue(t).window?.label
        assertEquals("PURPLE", label(621))
        assertEquals("MISSED · PURPLE", label(700))
        // Storm went through the E footprint between checks 1 and 2 (inside P+658): never "closer than 0.0 b".
        assertEquals("Storm passed between checks, 0.1 s before check 2", v.getValue(700).window?.hint)
        assertEquals("GREEN", label(1301))
        assertEquals("LATE · GREEN", label(1400))
        assertEquals("GREEN", label(1640))
        assertEquals("Storm passed 1.1 s before the step", v.getValue(1640).window?.hint)
        assertEquals("MISSED · GREEN", label(1720))
        assertEquals("LATE · GREEN", label(1750))
        assertEquals("HIGH", v.getValue(1200).phase?.word)
        // A later stand (Green, P+1283..1311): pad tick and window, no action line.
        assertNull(v.getValue(1290).action)
        assertEquals("0.5", v.getValue(1290).pad?.text)
        assertEquals("STORM RETURNS", v.getValue(1290).phase?.word)
    }

    @Test
    fun aMissInsideBetweenChecksNeverReadsZeroBlocks() {
        val results = StormReplay("r7").results
        val e = results.first { it.pillar == "E" }
        assertEquals(CrushResult.BETWEEN, e.kind)
        assertEquals("Storm passed between checks, 0.10 s before check 2", e.text)
        for (r in results) assertTrue(r.closest.let { it == null || it > 0.0 }, r.text)
    }

    @Test
    fun settingsMatchTheMock() {
        val variants = listOf(
            "ticks" to CrosshairOptions(ticks = true),
            "stayOff" to CrosshairOptions(stayTime = false),
            "always" to CrosshairOptions(padWhen = CrosshairTimers.PAD_ALWAYS),
            "onpad" to CrosshairOptions(padWhen = CrosshairTimers.PAD_ON),
            "fireball" to CrosshairOptions(fireball = true),
            "noArm" to CrosshairOptions(arm = false),
            "noPurple" to CrosshairOptions(purple = false),
            "noAction" to CrosshairOptions(action = false),
            "lateWord" to CrosshairOptions(lateAmount = false),
            "noEta" to CrosshairOptions(eta = false),
        )
        for ((name, o) in variants) {
            val r = MockRun()
            for (f in variantFrames.getValue(name)) assertFrame(f, r.view(f.t, o), name)
        }
        val miss = MockRun(missPurple = true)
        for (f in variantFrames.getValue("miss")) assertFrame(f, miss.view(f.t), "miss")
        val stale = MockRun()
        for (f in variantFrames.getValue("stale")) assertFrame(f, stale.view(f.t, stale = true), "stale")
    }

    @Test
    fun goldorDeathTick() {
        for ((name, o) in listOf("goldor" to CrosshairOptions(), "goldorRing" to CrosshairOptions(goldorRing = true))) {
            for (f in variantFrames.getValue(name)) {
                val m = StormModel(BossTimings.F7)
                // The grid sits one tick before `Who dares` (WD_TO_START); the mock's t counts from the grid.
                m.chat(StormModel.WHO_DARES, 5000 - GoldorPhaseModel.WD_TO_START)
                m.advance(5000L + f.t)
                assertFrame(f, CrosshairTimers.view(CrosshairTimers.places(m, (5000 + f.t).toDouble(), o), o, stale = false), name)
            }
        }
    }

    private val variantFrames = mapOf(
        "ticks" to listOf(
            Frame(560, "0 #55ffff ring 0.00", "STEP ON PURPLE in 43 #b45cff", null, null),
            Frame(610, "10 #55ffff ring 0.50", "STAY 10 #ffffff", null, null),
            Frame(808, "12 #55ffff ring 0.60", null, null, "YELLOW|52|Storm 25 b · ETA 99 t|#ffe14d|#ffffff"),
            Frame(910, null, null, null, "LATE · YELLOW|+47||#ff5555"),
            Frame(1650, null, null, "HIGH 117 #ffa040", null),
            Frame(1720, "0 #55ffff ring 0.00", "STEP ON YELLOW in 43 #ffe14d", "HIGH 47 #ffa040", null),
            Frame(1780, "0 #55ffff ring 0.00", null, "STORM RETURNS 15 #ffa040", null),
        ),
        "stayOff" to listOf(
            Frame(610, "0.5 #55ffff ring 0.50", "STAY #ffffff", null, null),
            Frame(1770, "0.5 #55ffff ring 0.50", null, "STORM RETURNS 1.3 #ffa040", null),
        ),
        "always" to listOf(
            Frame(100, "0.0 #55ffff ring 0.00", null, "LIGHTNING 21.0 #6cb8ff", null),
            Frame(300, "0.0 #55ffff ring 0.00", null, "LIGHTNING 11.0 #6cb8ff", null),
            Frame(700, "0.0 #55ffff ring 0.00", null, null, "CRUSHED · CHECK 2|check||#55ff55"),
        ),
        "onpad" to listOf(
            Frame(560, null, "STEP ON PURPLE in 2.1 #b45cff", null, null),
            Frame(610, "0.5 #55ffff ring 0.50", "STAY 0.5 #ffffff", null, null),
            Frame(640, null, null, null, "PURPLE|2.0|Storm 10 b · ETA —|#b45cff|#ffffff"),
        ),
        "miss" to listOf(
            Frame(600, "0.0 #55ffff ring 0.00", "STEP ON PURPLE in 0.1 #b45cff", null, null),
            Frame(603, "0.8 #55ffff ring 0.85", "STEP ON PURPLE NOW #b45cff", null, null),
            Frame(610, "0.5 #55ffff ring 0.50", "STAY 0.5 #ffffff", null, null),
            Frame(639, "0.1 #55ffff ring 0.05", "STEP ON PURPLE NOW #b45cff", null, null),
            Frame(640, "0.0 #55ffff ring 0.00", "TOO LATE #ff5555", null, null),
            Frame(650, null, "TOO LATE #ff5555", null, null),
            Frame(679, null, "TOO LATE #ff5555", null, null),
            Frame(680, null, null, null, null),
        ),
        "stale" to listOf(
            Frame(610, "0.5 #9aa0ab ring 0.50", "STAY 0.5 #9aa0ab", null, null),
            Frame(845, null, null, null, "YELLOW|0.8|Storm 16 b · ETA 3.1 s|#9aa0ab|#ffffff"),
            Frame(1310, null, null, "GIGA 6.3 #9aa0ab", "MISSED · YELLOW|cross|Storm passed 0.2 s before the step|#9aa0ab|#9aa0ab"),
        ),
        "fireball" to listOf(
            Frame(1650, null, null, "HIGH 5.8 #ffa040", null),
            Frame(1700, null, null, "HIGH 3.4 · next fireball 0.1 #ffa040", null),
            Frame(1740, "0.0 #55ffff ring 0.00", "STEP ON YELLOW in 1.1 #ffe14d", "HIGH 1.4 · next fireball 0.1 #ffa040", null),
            Frame(1770, "0.5 #55ffff ring 0.50", null, "STORM RETURNS 1.3 #ffa040", null),
        ),
        "noArm" to listOf(
            Frame(1720, "0.0 #55ffff ring 0.00", null, "HIGH 2.4 #ffa040", null),
            Frame(1785, "0.8 #55ffff ring 0.75", null, "STORM RETURNS 0.5 #ffa040", "YELLOW|2.8|Storm 18 b · ETA —|#ffe14d|#ffffff"),
        ),
        "noPurple" to listOf(
            Frame(560, "0.0 #55ffff ring 0.00", null, null, null),
            Frame(610, "0.5 #55ffff ring 0.50", "STAY 0.5 #ffffff", null, null),
        ),
        "noAction" to listOf(
            Frame(560, "0.0 #55ffff ring 0.00", null, null, null),
            Frame(610, "0.5 #55ffff ring 0.50", null, null, null),
            Frame(1080, "0.0 #55ffff ring 0.00", null, null, null),
        ),
        "lateWord" to listOf(
            Frame(910, null, null, null, "LATE · YELLOW|LATE||#ff5555"),
        ),
        "noEta" to listOf(
            Frame(808, "0.6 #55ffff ring 0.60", null, null, "YELLOW|2.6||#ffe14d"),
        ),
        "goldorRing" to listOf(
            Frame(0, "0.0 #ff7a7a ring 0.00", null, null, null),
            Frame(30, "1.5 #ff7a7a ring 0.50", null, null, null),
            Frame(59, "0.1 #ff7a7a ring 0.02", null, null, null),
            Frame(61, "3.0 #ff7a7a ring 0.98", null, null, null),
            Frame(100, "1.0 #ff7a7a ring 0.33", null, null, null),
        ),
        "goldor" to listOf(
            Frame(0, "0.0 #ff7a7a", null, null, null),
            Frame(30, "1.5 #ff7a7a", null, null, null),
            Frame(59, "0.1 #ff7a7a", null, null, null),
            Frame(61, "3.0 #ff7a7a", null, null, null),
            Frame(100, "1.0 #ff7a7a", null, null, null),
        ),
    )

    @Test
    fun clocksLiftOverTheGigaTitle() {
        // The vanilla countdown title (6..1) is up from Giga 2's first title until the strike (model.js snapshot.title).
        val r = MockRun()
        assertEquals(false, r.view(1289).title)
        assertEquals(true, r.view(1290).title)
        assertEquals(true, r.view(1434).title)
        assertEquals(false, r.view(1435).title)
    }

    @Test
    fun standPollIsTheFirstGridTickOfTheStand() {
        assertEquals(620L, CrosshairTimers.standPoll(602, 0, 20))
        assertEquals(620L, CrosshairTimers.standPoll(620, 0, 20))
        assertEquals(640L, CrosshairTimers.standPoll(621, 0, 20))
        assertEquals(10_620L, CrosshairTimers.standPoll(10_604, 10_000, 20))
    }

    @Test
    fun nearPadIsADistanceAndNeverTheRedPad() {
        val m = StormModel(BossTimings.F7)
        val e = StormGeometry.E
        m.player(e.padX0 - 7.9, 170.0, e.padZ0 + 3.0, 1)
        assertEquals(e, CrosshairTimers.nearPad(m))
        m.player(e.padX0 - 8.2, 170.0, e.padZ0 + 3.0, 2)
        assertNull(CrosshairTimers.nearPad(m))
        val en = StormGeometry.EN
        m.player(en.padX0 + 3.0, 170.0, en.padZ0 + 3.0, 3)
        assertNull(CrosshairTimers.nearPad(m))
    }

    @Test
    fun numbersRoundLikeJavaScript() {
        // mock.js fmtN: (ceil(ticks) / 20).toFixed(1); toFixed rounds the binary value (0.15 -> "0.1", 0.25 -> "0.3").
        val o = CrosshairOptions()
        assertEquals("0.1", CrosshairTimers.number(3.0, o))
        assertEquals("0.3", CrosshairTimers.number(5.0, o))
        assertEquals("0.3", CrosshairTimers.number(7.0, o))
        assertEquals("0.5", CrosshairTimers.number(9.0, o))
        assertEquals("6.8", CrosshairTimers.number(135.0, o))
        assertEquals("5.8", CrosshairTimers.number(117.0, o))
        assertEquals("0.8", CrosshairTimers.number(14.2, o), "ceil to whole ticks first")
        assertEquals("0.0", CrosshairTimers.number(-3.0, o))
        assertEquals("+2.35", CrosshairTimers.late(47, o))
        assertEquals("+0.35", CrosshairTimers.late(7, o))
        assertEquals("12", CrosshairTimers.number(11.3, CrosshairOptions(ticks = true)))
    }

    @Test
    fun terminalsCountdownFromStormsDeathThenTheDeathTickFromT() {
        val o = CrosshairOptions()
        val m = StormModel(BossTimings.M7)
        fun at(t: Double, opts: CrosshairOptions = o) = CrosshairTimers.view(CrosshairTimers.places(m, t, opts), opts, stale = false)
        m.chat(StormModel.PATHETIC, 1000)
        m.sound(GoldorPhaseModel.THUNDER, 45.0, 170.0, 42.0, 10000f, 1889)
        m.advance(1890)
        val first = at(1890.0).phase
        assertEquals("TERMINALS", first?.word, "Storm's death strike, his end line still queued")
        assertEquals("3.0", first?.digits)
        m.chat(StormModel.STORM_END, 1906)
        m.advance(1920)
        assertEquals("1.5", at(1920.0).phase?.digits)
        assertNull(at(1920.0, CrosshairOptions(terminals = false)).phase, "its own toggle")
        m.player(107.0, 120.0, 98.0, 1940)
        m.falling(98.5, 221.5, 42.5, 1949)
        m.advance(1949)
        assertEquals("0.1", at(1949.0).phase?.digits)
        m.advance(1960)
        val v = at(1960.0)
        assertNull(v.phase, "T: the countdown ends")
        assertEquals("2.5", v.pad?.text, "Goldor's death tick from T, before `Who dares`")
    }

    @Test
    fun nothingWhenNothingApplies() {
        val m = StormModel(BossTimings.F7)
        val v = CrosshairTimers.view(CrosshairTimers.places(m, 0.0, CrosshairOptions()), CrosshairOptions(), stale = false)
        assertTrue(v.empty)
    }
}
