package com.soulreturns.features.dungeons.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Aligned is not complete (live run 2026-11-12 19:19-19:28: every arrow at its target for minutes before Goldor, no
 * completion until the click in Goldor's phase): only the server's word ends the device, over-rotations stay blocked,
 * and in Goldor's phase the bottom-left-most arrow may take a full turn (unverified that it completes).
 */
class AlignedBoardTest {
    private fun aligned(): ArrowAlignModel {
        val m = ArrowAlignModel()
        val l = ArrowAlignModel.LAYOUTS[6]
        val seen = ArrayList<ArrowAlignModel.Frame>()
        for (c in 0 until ArrowAlignModel.SIZE) {
            val v = l[c]
            when {
                v == -1 -> {}
                v == -2 -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.START, 0, true)
                v == -3 -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.END, 0, true)
                else -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.ARROW, v, false)
            }
        }
        m.observe(0, seen)
        return m
    }

    private val bottomLeft = 21
    private val other = 22

    @Test
    fun anAlignedBoardBeforeGoldorBlocksEveryClick() {
        val m = aligned()
        assertTrue(m.done)
        assertFalse(m.completed)
        for (cell in listOf(bottomLeft, other)) {
            val d = m.click(100, cell, blockOn = true, holdLast = true, goldorActive = false)
            assertTrue(d.blocked, "r${cell / 5 + 1}c${cell % 5 + 1} would turn an aligned arrow away")
        }
    }

    @Test
    fun inGoldorsPhaseTheBottomLeftMostArrowTakesAFullTurnAndTheOthersStayBlocked() {
        val m = aligned()
        assertEquals(bottomLeft, m.fullTurnArrow())
        assertTrue(m.click(100, other, true, false, goldorActive = true).blocked)
        val d = m.click(110, bottomLeft, true, false, goldorActive = true)
        assertFalse(d.blocked, d.reason)
        assertTrue(d.reason.contains("full turn"), d.reason)
        // Seven more and the arrow is back at its target: the click that completes it passes as well.
        repeat(7) { assertFalse(m.click(120L + it, bottomLeft, true, false, goldorActive = true).blocked) }
    }

    @Test
    fun theServersCompletionEndsEverything() {
        val m = aligned()
        m.complete()
        assertEquals(-1, m.fullTurnArrow())
        assertFalse(m.click(100, other, true, true, true).blocked)
    }

    @Test
    fun aBoardThatIsNotAlignedHasNoFullTurn() {
        val m = ArrowAlignModel()
        val l = ArrowAlignModel.LAYOUTS[6]
        val seen = ArrayList<ArrowAlignModel.Frame>()
        for (c in 0 until ArrowAlignModel.SIZE) {
            val v = l[c]
            when {
                v == -1 -> {}
                v == -2 -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.START, 0, true)
                v == -3 -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.END, 0, true)
                else -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.ARROW, if (c == other) (v + 3) % 8 else v, false)
            }
        }
        m.observe(0, seen)
        assertEquals(-1, m.fullTurnArrow())
    }

    @Test
    fun theStoredHoldAndPhaseSequenceOfTheLiveRunIsReproducedByTheToggle() {
        // The 19:20:35 click: r5c2 one short, everything else aligned. Hold on + phase before: held. Hold off: sent (the user was in the menu).
        val short = ArrowAlignModel()
        val l = ArrowAlignModel.LAYOUTS[6]
        val seen = ArrayList<ArrowAlignModel.Frame>()
        for (c in 0 until ArrowAlignModel.SIZE) {
            val v = l[c]
            when {
                v == -1 -> {}
                v == -2 -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.START, 0, true)
                v == -3 -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.END, 0, true)
                else -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.ARROW, if (c == bottomLeft) (v + 7) % 8 else v, false)
            }
        }
        short.observe(0, seen)
        assertTrue(short.click(100, bottomLeft, true, holdLast = true).held)
        assertFalse(short.click(101, bottomLeft, true, holdLast = false).blocked)
    }
}
