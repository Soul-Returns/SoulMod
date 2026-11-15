package com.soulreturns.features.dungeons.devices

import com.soulreturns.features.dungeons.devices.LightsModel.Companion.ALL
import com.soulreturns.features.dungeons.devices.LightsModel.Companion.SOLUTION
import com.soulreturns.features.dungeons.devices.LightsModel.Companion.index
import com.soulreturns.features.dungeons.devices.LightsModel.Companion.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lights on the user's own F7 recordings (2026-11-12, `docs/research/f7-devices-2026-11-12.md` §10), cut by
 * `tools/devbridge/lights-fixtures` into `src/test/resources/devices/`: nosolver, run1, run2, run3, storm and
 * both ring timers fights, each six flips of the same six levers in its own order. Plus two M7 party runs
 * (`lights-m7-party1`, `-party2`, `--lead 60000`): a teammate lit the grid in Storm's phase, another touched a
 * lever in Goldor's phase and that completed it.
 */
class LightsFixturesTest {
    private val fixtures = listOf("lights-nosolver", "lights-run1", "lights-run2", "lights-run3", "lights-storm", "lights-ring1", "lights-ring2")

    /** Click-to-answer round trips to replay with (never 0: a live click precedes its answer). */
    private val latencies = listOf(20L, 50L, 120L, 250L, 400L)

    private fun cells(mask: Int) = (0 until LightsModel.SIZE).filter { mask and (1 shl it) != 0 }

    @Test
    fun theSixLeversAreTheOnesEveryRecordedRunFlipped() {
        assertEquals(6, Integer.bitCount(SOLUTION))
        for (f in fixtures) {
            val r = LightsReplay(f)
            assertEquals(SOLUTION, r.clicks.fold(0) { m, o -> m or (1 shl o.click.cell) }, f)
            assertEquals(6, r.clicks.size, f)
        }
    }

    @Test
    fun theSixLeversLightEveryLampAndNothingElseDoes() {
        // Own plus-shapes cover all 20 lamps; each lever is needed (without it some lamp stays dark).
        val union = cells(SOLUTION).fold(0) { m, i -> m or LightsModel.plus(i) }
        assertEquals(ALL, union)
        for (i in cells(SOLUTION)) {
            val without = cells(SOLUTION).filter { it != i }.fold(0) { m, j -> m or LightsModel.plus(j) }
            assertTrue(without != ALL, "${name(i)} is redundant")
        }
    }

    @Test
    fun everyRealFlipIsLetThroughAndWasShownAsRemaining() {
        for (f in fixtures) for (lat in latencies) {
            val r = LightsReplay(f, lat)
            for (o in r.clicks) {
                assertFalse(o.decision.blocked, "$f @$lat: ${name(o.click.cell)} blocked (${o.decision.reason})")
                assertTrue(o.click.cell in o.remainingBefore, "$f @$lat: ${name(o.click.cell)} was not shown as remaining at tick ${o.click.tick}: ${o.remainingBefore.map(::name)}")
            }
            assertTrue(r.blocked.isEmpty(), "$f @$lat")
        }
    }

    @Test
    fun theDeviceIsDoneAfterTheLastFlipAndShowsNothing() {
        for (f in fixtures) {
            val r = LightsReplay(f)
            assertTrue(r.model.done, f)
            assertEquals(emptyList(), r.model.remaining(), f)
            assertFalse(r.model.preFinished, "$f: the completion line came with the last lamps")
            assertTrue(r.plings.isNotEmpty(), "$f: the device's pling is in the fixture")
            assertTrue(r.logs.any { it.startsWith("device complete") }, "$f: ${r.logs}")
        }
    }

    private val m7 = listOf("lights-m7-party1", "lights-m7-party2")

    @Test
    fun m7GridLitInStormsPhaseIsPreFinishedUntilTheTouch() {
        for (f in m7) {
            val r = LightsReplay(f, ownFlips = false)
            // The teammate's six levers in Storm's phase lit everything; the S1 device's completion line (go9o, 7/7) came ~45 s later and is not this device's.
            assertEquals(listOf(LightsReplay.State(preFinished = true, done = false, remaining = emptyList())), r.beforeFlips, "$f: lit, not complete, nothing suggested just before the touch")
            assertTrue(r.chat.size >= 2, "$f: other devices' lines are in the fixture")
            // The touch (a lever that was off) and the completion line share a tick.
            assertTrue(r.model.done, "$f: ${r.logs}")
            assertFalse(r.model.preFinished, f)
        }
    }

    @Test
    fun m7PreFinishedLetsEveryLeverThrough() {
        for (f in m7) {
            // Just before the teammate's touch: one of the six (on) and the lever the teammate then touched (off) both pass.
            val on = LightsReplay(f, ownFlips = false, extra = { _ -> index(62, 133) })
            assertEquals(1, on.outcomes.size, f)
            assertFalse(on.outcomes[0].decision.blocked, "$f: ${on.outcomes[0].decision.reason}")
            val off = LightsReplay(f, ownFlips = false, extra = { c -> c.cell })
            assertFalse(off.outcomes[0].decision.blocked, "$f: ${off.outcomes[0].decision.reason}")
            assertTrue(off.model.done, f)
        }
    }

    @Test
    fun aLineFarFromTheGridsLastChangeIsAnotherDevice() {
        val m = LightsModel()
        m.observe(0, 0, 0)
        m.observe(SOLUTION, ALL, 1000)
        m.completion(1000 + LightsModel.LINE_MS + 1)
        assertTrue(m.preFinished)
        m.completion(60_000)
        assertTrue(m.preFinished)
        // The touch: a lever goes on with every lamp lit, the line comes in the same tick (before or after the block).
        m.completion(70_000)
        m.observe(SOLUTION or (1 shl index(61, 135)), ALL, 70_010)
        assertTrue(m.done)
    }

    @Test
    fun remainingShrinksByTheFlippedLeverEachTime() {
        for (f in fixtures) for (lat in latencies) {
            val r = LightsReplay(f, lat)
            // Six shown at the first flip, one fewer at each next one, also when two flips share a server tick.
            assertEquals(listOf(6, 5, 4, 3, 2, 1), r.clicks.map { it.remainingBefore.size }, "$f @$lat")
        }
    }

    @Test
    fun aLeverOutsideTheSixPassesBetweenRealFlips() {
        // Before every real flip, also click a lever that is not one of the six: other covers are legitimate, nothing is held back.
        for (f in fixtures) {
            var n = 0
            val r = LightsReplay(f, 120, extra = { _ -> outside[n++ % outside.size] })
            val injected = r.outcomes.filter { it.injected }
            assertEquals(6, injected.size, f)
            assertTrue(injected.none { it.decision.blocked }, "$f: an extra lever was held back: ${injected.filter { it.decision.blocked }.map { it.decision.reason }}")
            assertTrue(r.clicks.none { it.decision.blocked }, "$f: a real flip got blocked after an extra click")
        }
    }

    @Test
    fun aLeverAlreadyFlickedIsBlockedBetweenRealFlips() {
        // Before every real flip but the first, click the lever flicked just before it again: it would turn lights off.
        for (f in fixtures) for (lat in latencies) {
            var last: Int? = null
            val r = LightsReplay(f, lat, extra = { c -> last.also { last = c.cell } })
            val injected = r.outcomes.filter { it.injected }
            assertEquals(5, injected.size, "$f @$lat")
            assertTrue(injected.all { it.decision.blocked }, "$f @$lat: an already flicked lever was let through: ${injected.filter { !it.decision.blocked }.map { it.decision.reason }}")
            assertTrue(r.clicks.none { it.decision.blocked }, "$f @$lat: a real flip got blocked")
        }
    }

    @Test
    fun blockingOffLetsEverythingThrough() {
        for (f in fixtures) {
            val r = LightsReplay(f, 120, blockOn = false, extra = { _ -> index(61, 135) })
            assertTrue(r.outcomes.none { it.decision.blocked }, f)
        }
    }

    @Test
    fun aSecondClickOnTheSameLeverBeforeTheAnswerIsBlocked() {
        // The flip's answer arrives one round trip later; a double click in that window would flip it back.
        for (f in fixtures) {
            val r = LightsReplay(f, 250, extra = { c -> c.cell })
            val injected = r.outcomes.filter { it.injected }
            assertTrue(injected.isNotEmpty(), f)
            // The injected click comes first and is the real one then; the recorded click after it is the double.
            assertTrue(injected.all { !it.decision.blocked }, f)
            // Also the last pair: the device is only complete once the server's own lamps say so.
            assertTrue(r.clicks.all { it.decision.blocked }, "$f: the second click of each pair must be held back")
        }
    }

    @Test
    fun joiningMidwayShowsWhatIsLeftFromTheWorld() {
        // A client that loads the chunk after the third flip (tick 7587) reads the levers and lamps and plans from there.
        val r = LightsReplay("lights-run2", 120, fromTick = 7588)
        val flipped = listOf(index(62, 133), index(62, 136), index(60, 135))
        val first = r.clicks.first()
        assertEquals(index(60, 134), first.click.cell)
        assertEquals(3, first.remainingBefore.size, "three levers were already on: ${first.remainingBefore.map(::name)}")
        assertTrue(first.remainingBefore.none { it in flipped })
        assertTrue(r.blocked.isEmpty())
    }

    @Test
    fun unreadableGridBlocksNothing() {
        val m = LightsModel()
        val wrong = index(61, 135)
        assertFalse(m.leverClick(0, wrong, true).blocked)
        assertNull(m.remaining().firstOrNull())
        m.observe(1 shl wrong, LightsModel.plus(wrong), 0)
        assertTrue(m.leverClick(0, wrong, true).blocked, "an already flicked lever")
        m.reset("left")
        assertFalse(m.leverClick(1, wrong, true).blocked)
    }

    @Test
    fun anUnansweredClickDropsOutAfterServerTrafficAndTheLeverShowsAgain() {
        val m = LightsModel()
        m.observe(0, 0, 0)
        val lever = index(62, 136)
        assertFalse(m.leverClick(1000, lever, true).blocked)
        assertFalse(lever in m.remaining())
        // No traffic at all: the click stays assumed (a silent server is not a rejected click).
        m.check(1000)
        assertFalse(lever in m.remaining())
        m.check(1000 + LightsModel.CONFIRM_MS + 1)
        assertTrue(lever in m.remaining())
        assertFalse(m.leverClick(3000, lever, true).blocked)
    }

    @Test
    fun aGridFirstSeenLitIsPreFinished() {
        // Arriving at a lit grid (lit in Storm's phase, as in every M7 party run): any lever that is not on yet completes it.
        val m = LightsModel()
        m.observe(SOLUTION, ALL, 0)
        assertFalse(m.done)
        assertTrue(m.preFinished)
        assertEquals(emptyList(), m.remaining())
        assertFalse(m.leverClick(0, index(62, 133), true).blocked, "one of the six is on: any lever completes it (user)")
        assertFalse(m.leverClick(0, index(61, 135), true).blocked)
        assertFalse(m.preFinished, "after the touch it is no longer 'any lever'")
        assertFalse(m.leverClick(1, index(61, 135), true).blocked, "nothing is held back while every lamp is lit")
        // The server shows the touch with the completion line: complete, nothing held back.
        m.completion(100)
        m.observe(SOLUTION or (1 shl index(61, 135)), ALL, 110)
        assertTrue(m.done)
        assertFalse(m.leverClick(200, index(62, 133), true).blocked)
    }

    @Test
    fun anAlreadyFlickedLeverIsBlockedAndEveryOtherLeverPasses() {
        val m = LightsModel()
        val theirs = index(62, 133)
        m.observe(1 shl theirs, LightsModel.plus(theirs), 0)
        assertTrue(m.leverClick(0, theirs, true).blocked)
        assertFalse(m.leverClick(0, theirs, false).blocked, "toggle off")
        // A lever outside the six, and one whose lamps are already lit twice over, pass.
        assertFalse(m.leverClick(0, index(61, 135), true).blocked)
        assertFalse(m.leverClick(0, index(61, 133), true).blocked, "its own lamp and one neighbour are already lit: lit lamps stay lit")
    }

    @Test
    fun theSuggestionAdaptsToWhatIsAlreadyLit() {
        // Brute-force reference: the fewest unflicked levers covering every dark lamp.
        fun least(levers: Int, lamps: Int): Int {
            val dark = lamps.inv() and ALL
            if (dark == 0) return 0
            val free = (0 until LightsModel.SIZE).filter { levers and (1 shl it) == 0 }
            for (k in 1..free.size) {
                fun go(from: Int, left: Int, covered: Int): Boolean {
                    if (left == 0) return covered and dark == dark
                    for (i in from..free.size - left) if (go(i + 1, left - 1, covered or LightsModel.plus(free[i]))) return true
                    return false
                }
                if (go(0, k, 0)) return k
            }
            return -1
        }
        // A teammate used another cover for part of it; a second one flipped two levers next to each other.
        val states = listOf(
            listOf(index(59, 134)),
            listOf(index(61, 135), index(61, 134)),
            listOf(index(60, 134), index(62, 136), index(58, 133)),
            listOf(index(58, 134), index(59, 136), index(60, 133), index(61, 135)),
            listOf(index(62, 133), index(62, 136), index(60, 135), index(60, 134), index(58, 136)),
        )
        for (flicked in states) {
            val levers = flicked.fold(0) { m, c -> m or (1 shl c) }
            val lamps = flicked.fold(0) { m, c -> m or LightsModel.plus(c) }
            val m = LightsModel()
            m.observe(levers, lamps, 0)
            val s = m.remainingMask()
            assertEquals(0, s and levers, "only levers not flicked: ${LightsModel.names(s)}")
            val covered = (0 until LightsModel.SIZE).filter { s and (1 shl it) != 0 }.fold(lamps) { acc, c -> acc or LightsModel.plus(c) }
            assertEquals(ALL, covered, "the suggestion lights every dark lamp (flicked ${LightsModel.names(levers)}): ${LightsModel.names(s)}")
            assertEquals(least(levers, lamps), Integer.bitCount(s), "the fewest levers (flicked ${LightsModel.names(levers)}): ${LightsModel.names(s)}")
        }
        // A fresh grid suggests exactly the six.
        val fresh = LightsModel()
        fresh.observe(0, 0, 0)
        assertEquals(SOLUTION, fresh.remainingMask())
    }

    @Test
    fun aLeverSomeoneElseFlippedIsNotSuggestedAgain() {
        val m = LightsModel()
        val theirs = index(62, 133)
        m.observe(1 shl theirs, LightsModel.plus(theirs), 0)
        assertFalse(theirs in m.remaining())
        assertEquals(5, m.remaining().size)
    }

    /** Levers that are not part of the six. */
    private val outside = listOf(index(61, 135), index(59, 134), index(59, 135), index(61, 134), index(60, 133), index(60, 136))
}
