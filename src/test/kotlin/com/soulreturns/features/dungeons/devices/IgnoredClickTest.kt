package com.soulreturns.features.dungeons.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Clicks the server ignores (a sneaking click on a lever does nothing on Hypixel) must not keep blocking the retry:
 * the player's real session of 2026-11-12 18:58-18:59 (r2c3 and r3c3 sent, retried, "Blocked ... already flicked", then
 * "not shown after 1544 ms").
 */
class IgnoredClickTest {
    private fun cell(r: Int, c: Int) = LightsModel.index(LightsModel.X_RIGHT - (c - 1), LightsModel.Y_TOP - (r - 1))

    @Test
    fun anIgnoredLeverClickBlocksTheRetryOnlyForAnAnswerTime() {
        val m = LightsModel()
        m.observe(0, 0, 0)
        val r2c3 = cell(2, 3)
        val r3c3 = cell(3, 3)
        var t = 1000L
        assertFalse(m.leverClick(t, r2c3, true).blocked, "first click sent")
        m.check(t + 100)
        assertTrue(m.leverClick(t + 150, r2c3, true).blocked, "a retry while the answer may still come is held (it would turn the lever off)")
        // The server never answers: after the answer time the click no longer counts.
        m.check(t + LightsModel.CONFIRM_MS + 50)
        assertFalse(m.leverClick(t + LightsModel.CONFIRM_MS + 60, r2c3, true).blocked, "the retry goes through once no answer came")
        t += 3000
        assertFalse(m.leverClick(t, r3c3, true).blocked)
        m.check(t + LightsModel.CONFIRM_MS + 50)
        assertFalse(m.leverClick(t + LightsModel.CONFIRM_MS + 60, r3c3, true).blocked)
        assertTrue(LightsModel.CONFIRM_MS <= 800, "an answer takes a few hundred ms, the assumption must not outlive that by much")
    }

    @Test
    fun anAnsweredLeverClickStaysCounted() {
        val m = LightsModel()
        m.observe(0, 0, 0)
        val i = cell(4, 1)
        assertFalse(m.leverClick(1000, i, true).blocked)
        m.observe(1 shl i, LightsModel.plus(i), 1050)
        m.check(1100)
        assertEquals(true, m.leverClick(1200, i, true).blocked, "the lever is on in the world: a second click would turn it off")
    }
}
