package com.soulreturns.features.dungeons.devices

import com.soulreturns.features.dungeons.devices.SimonSaysModel.Companion.index
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Simon Says on the user's own F7 recordings (2026-11-12, `docs/research/f7-devices-2026-11-12.md`),
 * cut by `tools/devbridge/ss-fixtures` into `src/test/resources/devices/`:
 * `ss-normal` one start press (nosolver run), `ss-skip-*` the three-press skip (run 2, run 3, storm,
 * ring timers fight 2), `ss-four-*` four start presses (run 1, ring timers fight 1).
 * Grid indices: (123 - y) * 4 + (z - 92).
 */
class SimonSaysFixturesTest {
    /**
     * Click-to-answer round trips to replay with. Never 0: the pling is a packet of its own that can
     * arrive up to 40 ms before the block answer of the same press (nosolver tick 5719), and a live
     * click always precedes both.
     */
    private val latencies = listOf(50L, 120L, 250L)

    private fun i(y: Int, z: Int) = index(y, z)

    /**
     * Every recorded grid press was let through and was the button the solver showed as next, or (at a low latency) a
     * click that raced the round's 16th button: nothing shown yet, counted once the round is decided.
     */
    private fun assertEveryPressExpected(r: SimonSaysReplay, label: String) {
        for (o in r.buttonPresses) {
            assertFalse(o.decision.blocked, "$label: ${o.press} blocked (${o.decision.reason})")
            if (o.expected != null) assertEquals(o.press.target, o.expected, "$label: press at tick ${o.press.tick} was not the shown next button")
        }
        // ring2 at 250 ms: both skip presses (+36, +41) were clicked before the client saw the 16th button.
        assertTrue(r.buttonPresses.count { it.expected == null } <= 2, "$label: presses without a shown next button")
    }

    @Test
    fun normalRunGrowsOneLampPerRoundToFour() {
        for (lat in latencies) {
            val r = SimonSaysReplay("ss-normal", lat)
            val a = i(123, 94)
            val b = i(123, 93)
            val c = i(123, 95)
            val d = i(122, 92)
            assertEquals(listOf(listOf(a), listOf(a, b), listOf(a, b, c), listOf(a, b, c, d)), r.rounds.map { it.second }, "latency $lat")
            assertEquals(10, r.buttonPresses.size)
            assertEveryPressExpected(r, "normal, latency $lat")
            assertFalse(r.startPresses.single().decision.blocked)
            assertEquals(1, r.chat.size)
        }
    }

    @Test
    fun skipRoundsExpectTheSecondAndThirdLamp() {
        // (shown in the skip display, then the real sequence's 3rd and 4th lamps)
        val cases = mapOf(
            "ss-skip-run2" to listOf(i(121, 92), i(123, 92), i(121, 92), i(121, 93), i(121, 95)),
            "ss-skip-run3" to listOf(i(121, 95), i(120, 94), i(120, 92), i(122, 94), i(122, 95)),
            "ss-skip-storm" to listOf(i(120, 95), i(121, 94), i(123, 92), i(123, 94), i(122, 92)),
            "ss-skip-ring2" to listOf(i(122, 94), i(121, 94), i(120, 94), i(123, 94), i(120, 92)),
        )
        for ((name, lamps) in cases) {
            for (lat in latencies) {
                val r = SimonSaysReplay(name, lat)
                val s = lamps.drop(1)
                assertEquals(listOf(s.take(2), s.take(3), s.take(4)), r.rounds.map { it.second }, "$name latency $lat\n${r.logs.joinToString("\n")}")
                assertEquals(3, r.startPresses.size)
                assertTrue(r.startPresses.none { it.decision.blocked }, name)
                assertEquals(9, r.buttonPresses.size, name)
                assertEveryPressExpected(r, "$name, latency $lat")
                assertTrue(r.buttonPresses.take(2).filter { it.expected != null }.all { it.skip }, "$name: first round flagged as the skip")
                assertTrue(r.logs.any { "skip: shown" in it }, name)
            }
        }
    }

    @Test
    fun fourthRapidStartPressIsBlocked() {
        for (name in listOf("ss-four-ring1", "ss-four-run1")) {
            for (lat in latencies) {
                val r = SimonSaysReplay(name, lat)
                val starts = r.startPresses
                assertFalse(starts[0].decision.blocked || starts[1].decision.blocked || starts[2].decision.blocked, name)
                assertTrue(starts[3].decision.blocked, "$name latency $lat: 4th start press (tick ${starts[3].press.tick}) let through: ${starts[3].decision.reason}")
                // Every later start press is seconds later: a deliberate restart, never blocked.
                assertTrue(starts.drop(4).none { it.decision.blocked }, name)
                // No button press is ever blocked: the cut round is unknown, the later rounds are solved.
                assertTrue(r.buttonPresses.none { it.decision.blocked }, "$name: ${r.blocked}")
            }
        }
    }

    @Test
    fun afterFourPressesTheCutRoundIsUnknownAndTheRestartSolved() {
        val r = SimonSaysReplay("ss-four-ring1")
        // The two presses on the cut round's buttons (the server ignored them): unknown, let through.
        val cut = r.buttonPresses.take(2)
        assertTrue(cut.all { it.expected == null && !it.decision.blocked })
        assertEquals(
            listOf(listOf(i(123, 92), i(121, 95)), listOf(i(123, 92), i(121, 95), i(123, 95)), listOf(i(123, 92), i(121, 95), i(123, 95), i(123, 94))),
            r.rounds.map { it.second },
        )
        val run1 = SimonSaysReplay("ss-four-run1")
        assertEveryPressExpected(run1, "run1")
        // run 1: the restart after the cut round solved its two lamps (r2c1 r4c2), then a reset and four clean rounds.
        assertEquals(
            listOf(listOf(i(122, 92), i(120, 93)), listOf(i(120, 92)), listOf(i(120, 92), i(122, 92)),
                listOf(i(120, 92), i(122, 92), i(121, 92)), listOf(i(120, 92), i(122, 92), i(121, 92), i(122, 94))),
            run1.rounds.map { it.second }, run1.logs.joinToString("\n"),
        )
    }

    @Test
    fun withBlockingOffTheFourthPressGoesOutAndNothingIsSolvedForThatRound() {
        val r = SimonSaysReplay("ss-four-ring1", blockOn = false)
        assertTrue(r.blocked.isEmpty())
        assertTrue(r.logs.any { "round unknown" in it && "4 start presses" in it }, r.logs.joinToString("\n"))
    }

    @Test
    fun wrongPressIsBlockedAndTheRightOneStillGoesThrough() {
        // Before the 3rd recorded grid press (round 2, second press: r1c2 expected), click r4c4.
        var n = 0
        val r = SimonSaysReplay("ss-normal", extra = { p -> if (!p.start && ++n == 3) i(120, 95) else null })
        val injected = r.outcomes.single { it.injected }
        assertTrue(injected.decision.blocked, injected.decision.reason)
        assertEquals(i(123, 93), injected.expected)
        assertEveryPressExpected(r, "after the blocked one")
        assertEquals(1, r.blocked.size)
    }


    @Test
    fun slowStartPressesAreNotTheSkip() {
        val m = SimonSaysModel()
        m.observe(0, 0, 0)
        assertFalse(m.startPress(1000, true).blocked)
        assertFalse(m.startPress(1700, true).blocked)
        assertFalse(m.startPress(2400, true).blocked)
        // A fourth press 300 ms after the third: the three were no burst, nothing to protect.
        assertFalse(m.startPress(2700, true).blocked)
        // Even with the skip's display, a non-burst start is not solved as a skip.
        val a = i(121, 92)
        val b = i(123, 92)
        val c = i(121, 93)
        m.observe(2800, 1 shl a, 0)
        m.observe(3050, 1 shl b, 0)
        m.observe(3450, 1 shl c, 0)
        m.observe(3700, 1 shl c, 0xFFFF and (1 shl c).inv())
        m.observe(3850, 0, 0xFFFF and (1 shl c).inv())
        m.observe(4100, 0, 0xFFFF)
        assertNull(m.solution)
        assertFalse(m.buttonPress(4000, i(120, 95), true).blocked)
    }

    @Test
    fun rapidThirdThenFourthIsBlockedOnlyInsideTheWindow() {
        val m = SimonSaysModel()
        m.observe(0, 0, 0)
        m.startPress(1000, true)
        m.startPress(1150, true)
        m.startPress(1300, true)
        val fourth = m.startPress(1500, true)
        assertTrue(fourth.blocked, fourth.reason)
        assertTrue(m.startPress(1900, true).blocked)
        assertFalse(m.startPress(1300 + SimonSaysModel.FOURTH_BLOCK_MS + 1, true).blocked)
        // Blocking off: never held back.
        val off = SimonSaysModel()
        off.observe(0, 0, 0)
        repeat(3) { off.startPress(1000L + it * 150, false) }
        assertFalse(off.startPress(1500, false).blocked)
    }

    @Test
    fun arrivingMidRoundBlocksNothingUntilAWholeDisplayWasSeen() {
        for (lat in latencies) {
            // Tick 5770: round 3's first lamp is lit (it came on at 5767).
            val r = SimonSaysReplay("ss-normal", lat, fromTick = 5770)
            val round3 = r.buttonPresses.filter { it.press.tick in 5800..5822 }
            assertEquals(3, round3.size)
            assertTrue(round3.all { it.expected == null && !it.decision.blocked }, "latency $lat")
            // Round 4 starts in front of us after someone's last press: solved.
            assertEquals(listOf(listOf(i(123, 94), i(123, 93), i(123, 95), i(122, 92))), r.rounds.map { it.second }, r.logs.joinToString("\n"))
            assertTrue(r.blocked.isEmpty())
        }
    }

    @Test
    fun unknownStateLetsEverythingThrough() {
        val m = SimonSaysModel()
        // Never saw anything: nothing blocked.
        assertFalse(m.buttonPress(0, 0, true).blocked)
        m.observe(10, 0, 0xFFFF)
        for (b in 0 until 16) assertFalse(m.buttonPress(20, b, true).blocked)
        assertNull(m.next())
    }

    @Test
    fun anUnansweredPressDropsTheState() {
        val m = SimonSaysModel()
        m.observe(0, 0, 0)
        m.startPress(100, true)
        m.observe(300, 1 shl 5, 0)
        m.observe(700, 0, 0)
        m.observe(1200, 0, 0xFFFF)
        assertEquals(listOf(5), m.solution)
        m.buttonPress(1300, 5, true)
        m.check(2000, 2000)
        assertEquals(listOf(5), m.solution)
        // No pling after 1.5 s of server traffic: the state is gone, nothing is blocked any more.
        m.check(2900, 2900)
        assertNull(m.solution)
        assertFalse(m.buttonPress(3000, 7, true).blocked)
    }

    @Test
    fun someoneElsePressingDropsTheState() {
        val m = SimonSaysModel()
        m.observe(0, 0, 0)
        m.startPress(100, true)
        m.observe(300, 1 shl 5, 0)
        m.observe(700, 0, 0)
        m.observe(1200, 0, 0xFFFF)
        m.pling(1400)
        assertNull(m.solution)
    }

    @Test
    fun aSecondClickOnTheRoundsLastButtonIsBlocked() {
        val m = SimonSaysModel()
        m.observe(0, 0, 0)
        m.startPress(100, true)
        m.observe(300, 1 shl 5, 0)
        m.observe(700, 0, 0)
        m.observe(1200, 0, 0xFFFF)
        assertFalse(m.buttonPress(1300, 5, true).blocked)
        assertTrue(m.buttonPress(1350, 5, true).blocked)
        m.pling(1400)
        // The server takes the buttons away and shows round 2: the old block ends with them.
        m.observe(1500, 1 shl 5, 0)
        assertNull(m.next())
        assertFalse(m.buttonPress(1550, 9, true).blocked)
    }

    @Test
    fun liveSkipShowsTheFirstPressBeforeTheClick() {
        // 2026-11-12 17:26, the user's first live run: the solver waited for the 16th button (tick 7753) and the click on b
        // (answer at 7754) went out with nothing shown. Decided at c off (7743) now.
        for (lat in latencies) {
            val r = SimonSaysReplay("ss-skip-live1", lat)
            val label = "latency $lat\n${r.logs.joinToString("\n")}"
            assertTrue(r.blocked.isEmpty(), label)
            assertEquals(3, r.startPresses.size, label)
            val presses = r.buttonPresses
            assertTrue(presses.isNotEmpty(), label)
            for (o in presses) {
                assertTrue(o.visibleMs != null && o.visibleMs <= o.press.t, "$label: press at tick ${o.press.tick} had no visible highlight (visible ${o.visibleTick})")
                assertEquals(o.press.target, o.expected, label)
            }
            assertEquals(7743L, presses.first().visibleTick, label)
            assertEquals(expectedVisible(lat), presses.map { it.visibleTick }, label)
        }
    }

    /**
     * Tick at which the highlight for each recorded press became visible: b at c off (7743); c with its button, the 16th
     * (7753), or at 50 ms latency when b's own click (client tick 7754) moves the box on.
     */
    private fun expectedVisible(lat: Long): List<Long> = listOf(7743, if (lat == 50L) 7754 else 7753, 7802, 7811, 7824, 7876, 7888, 7901, 7904)

    /** The skip as the live run showed it: three lamps, 15 buttons while the third is lit, the 16th ten ticks after it went out. */
    private fun skipUntilCOff(m: SimonSaysModel): Triple<Int, Int, Int> {
        val a = i(121, 92)
        val b = i(123, 93)
        val c = i(122, 95)
        m.observe(0, 0, 0)
        m.startPress(1000, true)
        m.startPress(1150, true)
        m.startPress(1300, true)
        m.error(1350)
        m.observe(1400, 1 shl a, 0)
        m.observe(1600, 1 shl b, 0)
        m.observe(1800, 1 shl c, 0)
        m.observe(1900, 1 shl c, 0xFFFF and (1 shl c).inv())
        m.observe(2100, 0, 0xFFFF and (1 shl c).inv())
        return Triple(a, b, c)
    }

    @Test
    fun theSkipIsDecidedWhenTheThirdLampGoesOutAndPressesBeforeTheFullGridAreNeverBlocked() {
        val m = SimonSaysModel()
        val (_, b, c) = skipUntilCOff(m)
        assertEquals(listOf(b, c), m.solution)
        assertEquals(b, m.next())
        assertTrue(m.buttonUp(b) && !m.buttonUp(c))
        // A wrong press before the 16th button goes out (and the state is lost) rather than being guessed about.
        val wrong = SimonSaysModel()
        skipUntilCOff(wrong)
        assertFalse(wrong.buttonPress(2150, i(120, 95), true).blocked)
        assertNull(wrong.solution)
        // The right press is tentative: the box moves on to c, the pling confirms it.
        assertFalse(m.buttonPress(2150, b, true).blocked)
        assertEquals(c, m.next())
        assertEquals(1, m.remaining())
        m.pling(2200)
        assertEquals(c, m.next())
        m.observe(2300, 0, 0xFFFF)
        // Complete grid, nothing outstanding: a wrong press is blocked now.
        val d = m.buttonPress(2400, i(120, 95), true)
        assertTrue(d.blocked, d.reason)
        assertFalse(m.buttonPress(2450, c, true).blocked)
    }

    @Test
    fun anUnansweredTentativePressIsDroppedWithoutLosingTheRound() {
        val m = SimonSaysModel()
        val (_, b, c) = skipUntilCOff(m)
        assertFalse(m.buttonPress(2150, b, true).blocked)
        m.check(2150 + SimonSaysModel.CONFIRM_MS + 1, 2150 + SimonSaysModel.CONFIRM_MS + 1)
        assertEquals(listOf(b, c), m.solution)
        assertEquals(b, m.next())
        m.observe(3800, 0, 0xFFFF)
        assertTrue(m.buttonPress(3900, i(120, 95), true).blocked)
    }

    @Test
    fun whileATentativePressIsUnansweredNothingIsBlocked() {
        val m = SimonSaysModel()
        val (_, b, c) = skipUntilCOff(m)
        assertFalse(m.buttonPress(2150, b, true).blocked)
        m.observe(2300, 0, 0xFFFF)
        // Full grid, but b is still unanswered: the next press is tentative too (and a wrong one goes out, state lost).
        assertFalse(m.buttonPress(2350, c, true).blocked)
        assertEquals(0, m.remaining())
        m.pling(2400)
        m.pling(2450)
        assertNull(m.next())
    }

    @Test
    fun aLampLightingWhileTheDecidedRoundsButtonsAreUpLosesTheState() {
        val m = SimonSaysModel()
        skipUntilCOff(m)
        m.observe(2300, 1 shl 3, 0xFFFF)
        assertNull(m.solution)
    }

    @Test
    fun liveSkipTintsTheWallWhenEachLampIsKnown() {
        // The user: "which to click feels late". The wall cell of b is tinted the moment b's lamp lights (a does not count in a
        // skip), 17 ticks (850 ms) before the button highlight of the previous build (c off, 7743).
        val a = i(120, 95)
        val b = i(120, 93)
        val c = i(123, 94)
        for (lat in latencies) {
            val r = SimonSaysReplay("ss-skip-live1", lat)
            val label = "latency $lat\n${r.walls.joinToString("\n")}"
            val pre = r.walls.filter { !it.decided && it.tick < 7760 }
            assertEquals(listOf(b), pre.first().cells.map { it.index }, label)
            assertTrue(pre.first().cells.single().let { it.next && it.certain && it.order == 1 }, label)
            assertEquals(WALL_B_TICK, pre.first().tick, label)
            assertEquals(listOf(b, c), pre.last().cells.map { it.index }, label)
            assertEquals(listOf(1, 2), pre.last().cells.map { it.order }, label)
            assertEquals(WALL_C_TICK, pre.last().tick, label)
            assertTrue(pre.none { a in it.cells.map { c -> c.index } }, "a never shown: $label")
            // Earlier than the button highlight of the previous build (7743) by this many ticks.
            assertEquals(17L, 7743L - pre.first().tick, label)
            // Then the decided round keeps the tint on b (next) and c (after): the spot never jumps.
            // (At 50 ms b's own click, answered at 7754, has already moved the tint on to c.)
            val decided = r.walls.first { it.decided }
            assertEquals(listOf(b, c).takeLast(decided.cells.size), decided.cells.map { it.index }, label)
            assertTrue(decided.cells.first().next, label)
        }
    }

    @Test
    fun everyWallTintShownBeforeTheButtonsIsAPrefixOfTheRoundThatFollows() {
        for (name in listOf("ss-normal", "ss-skip-run2", "ss-skip-run3", "ss-skip-storm", "ss-skip-ring2", "ss-skip-live1")) {
            for (lat in latencies) {
                val r = SimonSaysReplay(name, lat)
                val label = "$name latency $lat\n${r.logs.joinToString("\n")}"
                assertTrue(r.walls.any { !it.decided }, label)
                for ((k, round) in r.rounds.withIndex()) {
                    val from = r.rounds.getOrNull(k - 1)?.first ?: Long.MIN_VALUE
                    val before = r.walls.filter { !it.decided && it.tick > from && it.tick <= round.first }
                    assertTrue(before.isNotEmpty(), "$label round $k: nothing tinted before the buttons")
                    for (w in before) {
                        val certain = w.cells.filter { it.certain }.map { it.index }
                        assertEquals(round.second.take(certain.size), certain, "$label round $k at ${w.tick}")
                    }
                    // By the time the round is decided the whole sequence was on the wall.
                    assertEquals(round.second, before.last().cells.map { it.index }, "$label round $k")
                }
            }
        }
    }

    private val WALL_B_TICK = 7726L
    private val WALL_C_TICK = 7735L
}
