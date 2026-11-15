package com.soulreturns.features.dungeons.devices

import com.soulreturns.features.dungeons.devices.ArrowAlignModel.Companion.ARROW
import com.soulreturns.features.dungeons.devices.ArrowAlignModel.Companion.END
import com.soulreturns.features.dungeons.devices.ArrowAlignModel.Companion.LAYOUTS
import com.soulreturns.features.dungeons.devices.ArrowAlignModel.Companion.START
import com.soulreturns.features.dungeons.devices.ArrowAlignModel.Companion.index
import com.soulreturns.features.dungeons.devices.ArrowAlignModel.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Arrow Align on the user's own F7 recordings (2026-11-12, `docs/research/f7-devices-2026-11-12.md` §11), cut by
 * `tools/devbridge/arrows-fixtures` into `src/test/resources/devices/`: nosolver, run1, run2, run3, storm and
 * both ring timers fights. Cells: `(124 - y) * 5 + (79 - z)`.
 *
 * The user's clicks include a few over-rotations (a click on an arrow already at its target, which costs seven
 * more clicks to come back); those are exactly the clicks the solver holds back. Every other click passes.
 */
class ArrowAlignFixturesTest {
    /** Fixture -> the 1-based layout its frames identify. */
    private val layouts = mapOf(
        "arrows-nosolver" to 9, "arrows-run1" to 3, "arrows-run2" to 8, "arrows-run3" to 7,
        "arrows-storm" to 7, "arrows-ring1" to 7, "arrows-ring2" to 6,
    )

    /** Fixture -> (tick, cell) of every click that found its arrow at the target (counted from the recording alone). */
    private val overRotations = mapOf(
        "arrows-nosolver" to listOf(9400L to index(123, 77), 9463L to index(123, 76)),
        "arrows-run1" to listOf(11366L to index(123, 77), 11449L to index(124, 79)),
        "arrows-run2" to emptyList(),
        "arrows-run3" to listOf(8642L to index(122, 76), 8654L to index(122, 77)),
        "arrows-storm" to emptyList(),
        "arrows-ring1" to listOf(13024L to index(122, 77), 13206L to index(124, 78)),
        "arrows-ring2" to listOf(9744L to index(122, 75), 9885L to index(123, 79)),
    )

    private val latencies = listOf(20L, 50L, 120L, 250L, 400L)

    @Test
    fun theFramesAtFirstSightIdentifyTheLayoutUniquely() {
        for ((f, layout) in layouts) {
            val r = ArrowAlignReplay(f)
            assertEquals(listOf(layout - 1), r.layoutsAtFirstClick, "$f\n${r.logs.joinToString("\n")}")
        }
    }

    @Test
    fun everyClickIsOnAFrameWhoseTargetIsKnownAndTheClicksLeftShownAreTheServers() {
        for (f in layouts.keys) for (lat in latencies) {
            // Blocking off: the model's own accounting of the clicks is then exactly what the recording shows.
            val r = ArrowAlignReplay(f, lat, blockOn = false)
            for (o in r.outcomes) {
                val where = "$f @$lat: ${ArrowAlignModel.name(o.click.cell)} tick ${o.click.tick}"
                assertTrue(o.target != null && o.truthRot != null, "$where: target not known\n${r.logs.joinToString("\n")}")
                assertEquals((o.target!! - o.truthRot!! + 8) % 8, o.leftBefore, "$where: clicks left shown")
                assertEquals(o.overRotation, o.decision.wrong, "$where: verdict ${o.decision}")
            }
        }
    }

    @Test
    fun theOverRotationsAreTheOnesTheRecordingsHold() {
        for ((f, expected) in overRotations) for (lat in latencies) {
            val r = ArrowAlignReplay(f, lat, blockOn = false)
            assertEquals(expected, r.outcomes.filter { it.overRotation }.map { it.click.tick to it.click.cell }, "$f @$lat")
        }
    }

    @Test
    fun withBlockingOnTheFirstHeldBackClickOfAFrameIsAnOverRotationAndNothingElseIsHeldBackBeforeIt() {
        for ((f, expected) in overRotations) for (lat in latencies) {
            val r = ArrowAlignReplay(f, lat)
            val seenBlock = HashSet<Int>()
            for (o in r.outcomes) {
                val where = "$f @$lat: ${ArrowAlignModel.name(o.click.cell)} tick ${o.click.tick}"
                if (o.click.cell in seenBlock) continue // the recording went on with the click the replay held back
                assertEquals(o.overRotation, o.decision.blocked, "$where: ${o.decision}")
                if (o.decision.blocked) seenBlock += o.click.cell
            }
            assertEquals(expected.size, seenBlock.size.let { _ -> r.outcomes.count { it.decision.blocked && it.overRotation } }, "$f @$lat: held back over-rotations")
        }
    }

    @Test
    fun aBurstOfClicksInOneServerTickIsCutAtTheTarget() {
        // ring1 tick 13206: seven clicks on r1c2 in one server tick (a 4 s lag spike): 7 -> 0 -> 1 (target) and then five that
        // would have gone round again. Two pass, the other five are held back.
        for (lat in latencies) {
            val r = ArrowAlignReplay("arrows-ring1", lat)
            val burst = r.outcomes.filter { it.click.tick == 13206L }
            assertEquals(7, burst.size)
            assertEquals(List(2) { false } + List(5) { true }, burst.map { it.decision.blocked }, "@$lat")
        }
    }

    @Test
    fun theDeviceIsDoneAtTheEndAndShowsNothing() {
        for (f in layouts.keys) {
            val r = ArrowAlignReplay(f, 120, blockOn = false)
            assertTrue(r.model.done || r.model.remaining().isEmpty(), "$f: remaining ${r.model.remaining().map(ArrowAlignModel::name)}")
            assertEquals(1, r.chat.size, "$f: one completion line")
        }
    }

    @Test
    fun blockingOffLetsEverythingThrough() {
        for (f in layouts.keys) assertTrue(ArrowAlignReplay(f, 120, blockOn = false).blocked.isEmpty(), f)
    }

    // ---- synthetic ----

    /** The frames of layout [layout] (1-based) with every arrow at [rot], lanterns lit exactly at the target. */
    private fun frames(layout: Int, rot: (Int) -> Int, only: (Int) -> Boolean = { true }): List<Frame> {
        val l = LAYOUTS[layout - 1]
        val out = ArrayList<Frame>()
        for (c in l.indices) {
            val v = l[c]
            if (v == -1 || !only(c)) continue
            out += when (v) {
                -2 -> Frame(c, START, 0, true)
                -3 -> Frame(c, END, 0, true)
                else -> Frame(c, ARROW, rot(c), rot(c) == v)
            }
        }
        return out
    }

    private fun oneShort(layout: Int): (Int) -> Int = { c -> (LAYOUTS[layout - 1][c] + 7) % 8 }

    @Test
    fun aDoubleClickBeforeTheEchoIsHeldBackAtTheTarget() {
        val m = ArrowAlignModel()
        val cell = index(120, 78)
        m.observe(1000, frames(7, oneShort(7)))
        assertEquals(listOf(6), m.layouts)
        assertEquals(1, m.clicksLeft(cell))
        assertFalse(m.click(1100, cell, true).blocked)
        assertEquals(0, m.clicksLeft(cell))
        assertTrue(m.click(1150, cell, true).blocked, "second click, the echo has not arrived")
        // The echo of the first click arrives: rotation +1, lantern on.
        m.observe(1180, frames(7, { if (it == cell) 1 else oneShort(7)(it) }))
        assertTrue(m.click(1200, cell, true).blocked, "at the target in the world")
        assertEquals(0, m.clicksLeft(cell))
    }

    @Test
    fun theWorldAdvancingConsumesTheOwnPendingClicksNotMore() {
        val m = ArrowAlignModel()
        val cell = index(120, 78)
        // Target 1; the arrow is at 6: three clicks to go. One more arrow is off its target, so the device is not complete.
        val other = index(120, 77)
        val base: (Int) -> Int = { if (it == other) 4 else LAYOUTS[6][it] }
        m.observe(1000, frames(7, { if (it == cell) 6 else base(it) }))
        assertEquals(3, m.clicksLeft(cell))
        assertFalse(m.click(1010, cell, true).blocked)
        assertFalse(m.click(1020, cell, true).blocked)
        assertEquals(1, m.clicksLeft(cell))
        // The server shows two of them (6 -> 0): one is still on the way.
        m.observe(1100, frames(7, { if (it == cell) 0 else base(it) }))
        assertEquals(1, m.clicksLeft(cell), "6 -> 0 is two clicks; 0 + 1 pending reaches the target")
        // A second of the pending ones shown: 0 -> 1, lantern on.
        m.observe(1200, frames(7, base))
        assertEquals(0, m.clicksLeft(cell))
        assertTrue(m.click(1210, cell, true).blocked)
    }

    @Test
    fun anUnansweredClickDropsOutAfterServerTrafficNotBefore() {
        val m = ArrowAlignModel()
        val cell = index(120, 78)
        m.observe(1000, frames(7, oneShort(7)))
        assertFalse(m.click(1000, cell, true).blocked)
        m.check(1000)
        assertTrue(m.click(1100, cell, true).blocked)
        // A silent server never times it out; traffic after CONFIRM_MS does.
        m.check(1000 + ArrowAlignModel.CONFIRM_MS + 1)
        assertEquals(1, m.clicksLeft(cell))
        assertFalse(m.click(2100, cell, true).blocked)
    }

    @Test
    fun woolFramesAndUnknownCellsAreNeverBlocked() {
        val m = ArrowAlignModel()
        m.observe(1000, frames(7, { LAYOUTS[6][it] }))
        for (c in listOf(index(124, 79), index(124, 75), index(122, 79), index(122, 75), index(120, 79), index(120, 75))) {
            assertFalse(m.click(1000, c, true).blocked, "wool at ${ArrowAlignModel.name(c)}")
        }
        assertFalse(m.click(1000, index(123, 77), true).blocked, "no frame there")
        assertFalse(ArrowAlignModel().click(1000, index(120, 78), true).blocked)
    }

    @Test
    fun partialViewsAgreeOrShowNothing() {
        // Brute-force reference: the layouts every visible frame fits, and a target known only where they all agree.
        val seen = frames(1, { 0 }).filter { it.kind == ARROW && LAYOUTS[2][it.cell] == LAYOUTS[0][it.cell] }
        val m = ArrowAlignModel()
        m.observe(1000, seen)
        val fits = LAYOUTS.indices.filter { l -> seen.all { LAYOUTS[l][it.cell] >= 0 } }
        assertEquals(fits, m.layouts)
        assertTrue(fits.size > 1, "ambiguous: $fits")
        for (c in 0 until 25) {
            val ts = fits.map { LAYOUTS[it][c] }.distinct()
            assertEquals(if (ts.size == 1 && ts[0] >= 0) ts[0] else null, m.target(c), "cell ${ArrowAlignModel.name(c)}")
        }
        assertFalse(m.done)
    }

    @Test
    fun aLanternThatContradictsTheOnlyLayoutForLongEnoughMakesTheTargetsUnknownButTheLanternStillBlocks() {
        val m = ArrowAlignModel()
        val cell = index(120, 78)
        // Layout 7; the server shows a lit lantern behind an arrow at the wrong rotation: the layout cannot be right.
        val dark = index(120, 77)
        val wrong = frames(7, { LAYOUTS[6][it] }).map {
            when (it.cell) {
                cell -> it.copy(rot = (LAYOUTS[6][cell] + 3) % 8, lantern = true)
                dark -> it.copy(rot = (LAYOUTS[6][dark] + 3) % 8, lantern = false)
                else -> it
            }
        }
        m.observe(5000, wrong)
        assertEquals(listOf(6), m.layouts)
        m.observe(5100, wrong)
        assertEquals(listOf(6), m.layouts, "still within the grace period")
        m.observe(5000 + ArrowAlignModel.GRACE_MS + 1, wrong)
        assertTrue(m.layouts.isEmpty())
        assertNull(m.target(cell))
        // Without a table the lit lantern alone says "at its target".
        assertTrue(m.click(6000, cell, true).blocked)
        // Another arrow with a dark lantern: nothing known about it, the click passes.
        assertFalse(m.click(6000, dark, true).blocked)
    }

    @Test
    fun aLayoutRecoversWhenTheLanternsAgreeAgain() {
        val m = ArrowAlignModel()
        val cell = index(120, 78)
        val ok = frames(7, { LAYOUTS[6][it] })
        val glitch = ok.map { if (it.cell == cell) it.copy(lantern = false) else it }
        m.observe(1000, ok)
        // The rotation arrived, the lantern not yet: a packet apart, inside the grace period.
        m.observe(1010, glitch)
        assertEquals(listOf(6), m.layouts)
        m.observe(1100, ok)
        m.observe(2000, ok)
        assertEquals(listOf(6), m.layouts)
    }

    @Test
    fun aFinishedDeviceBlocksNothingButOwnUnansweredClicksDoNotFinishIt() {
        val m = ArrowAlignModel()
        m.observe(1000, frames(9, { LAYOUTS[8][it] }))
        assertTrue(m.done)
        assertEquals(emptyList(), m.remaining())
        // Aligned is not complete: over-rotations stay blocked until the server completes the device.
        assertTrue(m.click(1000, index(121, 76), true).blocked)
        m.complete()
        assertFalse(m.click(1001, index(121, 76), true).blocked, "completed by the server: nothing is blocked")
        // One arrow one click short: after the own click it looks done, but only the server's word ends the blocking.
        val n = ArrowAlignModel()
        val cell = index(121, 76)
        n.observe(1000, frames(9, { if (it == cell) (LAYOUTS[8][it] + 7) % 8 else LAYOUTS[8][it] }))
        assertFalse(n.done)
        assertFalse(n.click(1010, cell, true).blocked)
        assertFalse(n.done)
        assertTrue(n.click(1020, cell, true).blocked, "the last arrow's second click, before the server has completed the device")
    }

    @Test
    fun remainingListsTheArrowsThatStillNeedClicks() {
        val m = ArrowAlignModel()
        val short = setOf(index(120, 78), index(122, 76))
        m.observe(1000, frames(7, { if (it in short) (LAYOUTS[6][it] + 5) % 8 else LAYOUTS[6][it] }))
        assertEquals(short.sorted(), m.remaining())
        assertEquals(3, m.clicksLeft(index(120, 78)))
    }
}
