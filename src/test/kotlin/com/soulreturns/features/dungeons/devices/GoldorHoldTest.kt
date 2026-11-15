package com.soulreturns.features.dungeons.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared Goldor phase, the "Override blocking while" modes and Arrow Align's hold of the last click. */
class GoldorHoldTest {
    @Test
    fun theGoldorPhaseIsBeforeOnlyWhenABossLineWasSeenAndNoGoldorLine() {
        val m = GoldorPhaseModel()
        assertFalse(m.before, "unknown holds nothing")
        assertFalse(m.active)
        m.chat("[BOSS] Maxor: WELL! WELL! WELL!", 100)
        assertTrue(m.before)
        m.chat("[BOSS] Storm: Pathetic Maxor, just like expected.", 200)
        assertTrue(m.before)
        m.chat("some other line", 300)
        assertTrue(m.before)
        m.chat("[BOSS] Goldor: Who dares trespass into my domain?", 400)
        assertFalse(m.before)
        assertTrue(m.active)
        m.reset()
        assertFalse(m.before)
        assertFalse(m.active)
        m.chat("[BOSS] Necron: You belong in the Wither King's realm", 500)
        assertTrue(m.active, "a Necron line means Goldor is over")
        assertFalse(m.before)
    }

    /** Storm's death strike at [d], his end line [late] ticks after it (queued on M7). */
    private fun stormDied(d: Long, late: Long = 0): GoldorPhaseModel {
        val m = GoldorPhaseModel()
        m.chat("[BOSS] Storm: Oof", d - 61)
        m.strike(d)
        m.chat(GoldorPhaseModel.STORM_END, d + late)
        return m
    }

    @Test
    fun withYouInGoldorsAreaThePhaseStartsAtStormsDeathPlus61() {
        // M7 6/6 on the lone-ping clock: the collapse at D + 59…60, T = D + 61, `Who dares` at D + 62.
        val m = stormDied(1890, late = 16)
        assertEquals(1951L, m.expected(), "the countdown: the death strike + 61")
        m.collapse(1949, inArea = true)
        assertEquals(1951L, m.start)
        m.advance(1950)
        assertTrue(m.before, "the last click still waits a tick after the collapse + 1")
        m.advance(1951)
        assertTrue(m.active)
        assertFalse(m.before)
        m.chat(GoldorPhaseModel.WHO_DARES, 1952)
        assertEquals(1951L, m.start, "`Who dares` − 1 is the same T")
    }

    @Test
    fun withoutTheStrikeTheCollapsePlusOneStandsIn() {
        val m = GoldorPhaseModel()
        m.chat("[BOSS] Storm: Oof", 1800)
        m.chat(GoldorPhaseModel.STORM_END, 1900)
        m.collapse(1949, inArea = true)
        assertEquals(1950L, m.start)
    }

    @Test
    fun aloneOnTheStormFloorThePhaseWaitsForWhoDares() {
        // F7 solo 11/11: the line came as the falling player passed y ≈ 136, 27.6–59 after the collapse.
        val m = stormDied(1890)
        m.collapse(1950, inArea = false)
        assertEquals(null, m.start)
        assertEquals(1951L, m.expected(), "the countdown ends at the death strike + 61")
        m.advance(1985)
        assertTrue(m.before)
        m.chat(GoldorPhaseModel.WHO_DARES, 1990)
        assertEquals(1989L, m.start)
        assertTrue(m.active)
    }

    @Test
    fun aWhoDaresSoonAfterTheCollapseMeansSomeoneWasDown() {
        val m = stormDied(1890)
        m.collapse(1950, inArea = false)
        m.chat(GoldorPhaseModel.WHO_DARES, 1958)
        assertEquals(1951L, m.start)
        assertTrue(m.fromCollapse)
    }

    @Test
    fun theEndLineStandsInForAMissedStrike() {
        val m = GoldorPhaseModel()
        m.chat("[BOSS] Storm: Oof", 1800)
        m.chat(GoldorPhaseModel.STORM_END, 1900)
        assertEquals(1961L, m.expected())
    }

    @Test
    fun aThunderBeforeStormOrAfterGoldorIsNoDeath() {
        val m = GoldorPhaseModel()
        m.chat("[BOSS] Maxor: WELL! WELL! WELL!", 100)
        m.strike(150)
        assertEquals(null, m.stormDeath, "Maxor's phase")
        m.collapse(160, inArea = true)
        assertEquals(null, m.collapse, "no collapse before Storm died")
        assertEquals(null, m.expected())
    }

    @Test
    fun overrideModesAgainstSneakingAndStanding() {
        for (sneaking in listOf(false, true)) {
            assertFalse(BlockOverride.active("never", sneaking))
            assertEquals(!sneaking, BlockOverride.active("standing", sneaking))
            assertEquals(sneaking, BlockOverride.active("sneaking", sneaking))
            assertFalse(BlockOverride.active("garbage", sneaking), "an unknown mode overrides nothing")
        }
    }

    @Test
    fun theOverrideIsPerDevice() {
        val c = com.soulreturns.config.DeviceSolverConfig(lightsOverride = "sneaking", arrowAlignOverride = "standing")
        for (sneaking in listOf(false, true)) {
            assertFalse(BlockOverride.active(Device.SIMON_SAYS.mode(c), sneaking), "Simon Says has no override set")
            assertEquals(sneaking, BlockOverride.active(Device.LIGHTS.mode(c), sneaking))
            assertEquals(!sneaking, BlockOverride.active(Device.ARROW_ALIGN.mode(c), sneaking))
        }
    }

    // ---- Arrow Align ----

    private val layout7 = 6

    /** Layout 7's frames with [needs] (cell -> clicks the arrow still needs) and every other arrow at its target. */
    private fun model(needs: Map<Int, Int>): ArrowAlignModel {
        val m = ArrowAlignModel()
        val l = ArrowAlignModel.LAYOUTS[layout7]
        val seen = ArrayList<ArrowAlignModel.Frame>()
        for (c in 0 until ArrowAlignModel.SIZE) {
            val v = l[c]
            when {
                v == -1 -> {}
                v == -2 -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.START, 0, true)
                v == -3 -> seen += ArrowAlignModel.Frame(c, ArrowAlignModel.END, 0, true)
                else -> {
                    val rot = (v - (needs[c] ?: 0) + 8) % 8
                    seen += ArrowAlignModel.Frame(c, ArrowAlignModel.ARROW, rot, rot == v)
                }
            }
        }
        m.observe(0L, seen)
        return m
    }

    @Test
    fun theClickThatFinishesTheDeviceIsHeldBeforeGoldorAndPassesWithoutTheHold() {
        val held = model(mapOf(21 to 1)).click(100, 21, blockOn = true, holdLast = true)
        assertTrue(held.blocked && held.held, held.reason)
        assertFalse(held.wrong)
        val notHeld = model(mapOf(21 to 1)).click(100, 21, blockOn = true, holdLast = false)
        assertFalse(notHeld.blocked, "no hold: the click goes")
        val noBlock = model(mapOf(21 to 1)).click(100, 21, blockOn = false, holdLast = true)
        assertTrue(noBlock.blocked && noBlock.held, "the hold works without Block wrong clicks")
    }

    @Test
    fun aHeldClickLeavesNoPendingClickSoItCanGoOnceGoldorStarts() {
        val m = model(mapOf(21 to 1))
        assertTrue(m.click(100, 21, true, true).blocked)
        assertEquals(1, m.clicksLeft(21))
        assertFalse(m.click(200, 21, true, false).blocked, "after Goldor starts the same click goes")
    }

    @Test
    fun otherClicksPassAndTheBottomLeftMostArrowIsTheOneLeftLast() {
        val m = model(mapOf(21 to 1, 12 to 1, 13 to 3))
        assertEquals(21, m.lastArrow(), "r5c2 is the bottom-left-most arrow that needs clicks")
        assertEquals(5, m.totalClicksLeft())
        assertFalse(m.click(100, 12, true, true).blocked)
        assertFalse(m.click(110, 13, true, true).blocked)
        assertFalse(m.click(120, 13, true, true).blocked)
        assertFalse(m.click(130, 13, true, true).blocked)
        assertEquals(1, m.totalClicksLeft())
        val last = m.click(140, 21, true, true)
        assertTrue(last.blocked && last.held, last.reason)
    }

    @Test
    fun anArrowWithSeveralClicksLeftIsNotTheLastClick() {
        val m = model(mapOf(21 to 2))
        assertFalse(m.click(100, 21, true, true).blocked)
        assertTrue(m.click(200, 21, true, true).held, "now one click is left in the whole device")
    }

    @Test
    fun anUnknownLayoutHoldsNothing() {
        val m = ArrowAlignModel()
        m.observe(0L, listOf(ArrowAlignModel.Frame(12, ArrowAlignModel.ARROW, 3, null)))
        assertNull(m.totalClicksLeft())
        assertFalse(m.click(100, 12, true, true).held)
        val none = ArrowAlignModel()
        assertFalse(none.click(100, 12, true, true).blocked, "nothing read: nothing held")
    }

    @Test
    fun aFinishedDeviceHoldsNothing() {
        val m = model(emptyMap())
        assertFalse(m.click(100, 21, true, true).held)
    }
}
