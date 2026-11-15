package com.soulreturns.features.dungeons.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first live pre-device run (2026-11-12 19:06, layout 2): every arrow lantern stayed dark after the arrows were
 * turned to the table's targets (before Goldor's phase), the lantern check dropped the layout and the solver
 * showed and blocked nothing for the rest of the device.
 */
class PreDeviceLanternTest {
    private val layout2 = 1

    /** Layout [index]'s frames with [rotOf] (cell, target) -> rotation and [lantern] (cell, rot, target) -> lantern state. */
    private fun frames(index: Int, rotOf: (Int, Int) -> Int, lantern: (Int, Int, Int) -> Boolean?): List<ArrowAlignModel.Frame> {
        val l = ArrowAlignModel.LAYOUTS[index]
        val out = ArrayList<ArrowAlignModel.Frame>()
        for (c in 0 until ArrowAlignModel.SIZE) {
            val v = l[c]
            when {
                v == -1 -> {}
                v == -2 -> out += ArrowAlignModel.Frame(c, ArrowAlignModel.START, 0, true)
                v == -3 -> out += ArrowAlignModel.Frame(c, ArrowAlignModel.END, 0, true)
                else -> { val r = rotOf(c, v); out += ArrowAlignModel.Frame(c, ArrowAlignModel.ARROW, r, lantern(c, r, v)) }
            }
        }
        return out
    }

    @Test
    fun darkLanternsBesideArrowsAtTheirTargetsKeepTheLayoutAndTheBlocking() {
        val m = ArrowAlignModel()
        // Pre-device: every arrow at its target, every arrow lantern dark, the wool lanterns lit.
        for (t in 0L..3000L step 50) m.observe(t, frames(layout2, { _, v -> v }, { _, _, _ -> false }))
        assertEquals(listOf(layout2), m.layouts, "the layout stays")
        assertTrue(m.done, "every arrow is at its target in the server's state")
        // One arrow short: the click that finishes it is held, any other still counts.
        val short = ArrowAlignModel()
        val cell = 16
        for (t in 0L..3000L step 50) short.observe(t, frames(layout2, { c, v -> if (c == cell) (v + 7) % 8 else v }, { _, _, _ -> false }))
        assertEquals(listOf(layout2), short.layouts)
        assertEquals(1, short.clicksLeft(cell))
        assertTrue(short.click(4000, cell, true, holdLast = true).held)
        assertFalse(short.click(4001, cell, true, holdLast = false).blocked)
    }

    @Test
    fun aLitLanternBesideAnArrowOffItsTargetStillDropsTheLayoutAndItComesBack() {
        val m = ArrowAlignModel()
        val bad = frames(layout2, { c, v -> if (c == 16) (v + 3) % 8 else v }, { c, _, _ -> if (c == 16) true else false })
        for (t in 0L..2000L step 50) m.observe(t, bad)
        assertTrue(m.layouts.isEmpty(), "a lit lantern at an arrow that is not at the table's target contradicts it")
        assertTrue(m.dump().contains("r4c2:arrow/rot"), m.dump())
        // The lanterns agree again (the lag was over): the layout is back.
        val ok = frames(layout2, { _, v -> v }, { c, r, v -> r == v })
        m.observe(2100, ok)
        assertEquals(listOf(layout2), m.layouts)
    }

    @Test
    fun theLogCarriesEveryFrameAtTheLayoutAndAtAContradiction() {
        val logs = ArrayList<String>()
        val m = ArrowAlignModel { logs += it }
        m.observe(0, frames(layout2, { _, v -> v }, { _, _, _ -> false }))
        assertTrue(logs.any { it.startsWith("layout 2:") && it.contains("r1c1:arrow/rot1/dark") && it.contains("r1c3:end/rot0/lit") }, logs.toString())
        val snap = m.snapshot()
        assertEquals(listOf(2), snap["layouts"])
    }
}
