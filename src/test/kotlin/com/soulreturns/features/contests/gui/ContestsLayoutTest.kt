package com.soulreturns.features.contests.gui

import com.soulreturns.features.contests.gui.ContestsLayout as L
import com.soulreturns.features.contests.hud.EndRevealTimeline as T
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mock's layout numbers (`mocks/contests/mock.js` constants) and the end reveal's timeline (`endfx.js`). */
class ContestsLayoutTest {
    @Test
    fun cardAndMainWidthAt1080pSoul15() {
        // 1920x1080 at Soul scale 1.5: 1280 x 720 Soul px.
        val c = L.card(1280f, 720f)
        assertEquals(1177, c.w)
        assertEquals(648, c.h)
        assertEquals(L.RAIL_W, c.railW)
        assertEquals(969, L.mainWidth(1280f))
        assertFalse(L.feedStacked(969))
        assertFalse(L.narrow(969))
        assertEquals(969 - 288 - 8, L.leaderboardW(969))
    }

    @Test
    fun narrowWindowStacksTheFeed() {
        // 1366x768 at 1.5: 910.67 Soul px wide.
        val main = L.mainWidth(1366 / 1.5f)
        assertEquals(837 - 176 - 32, main)
        assertTrue(L.feedStacked(main))
        assertTrue(L.narrow(main))
        assertEquals(main, L.leaderboardW(main))
    }

    @Test
    fun railCollapsesBelow820() {
        val c = L.card(880f, 600f)
        assertEquals(L.RAIL_MINI_W, c.railW)
        assertTrue(c.mini)
    }

    @Test
    fun gridResolvesFixedAndFlexibleTracks() {
        val ws = L.resolve(listOf(L.px(40), L.fr(100, 1f), L.fr(100, 3f)), 40 + 400 + 2 * 10, 10)
        assertEquals(40, ws[0])
        assertEquals(100, ws[1])
        assertEquals(300, ws[2])
    }

    @Test
    fun podiumScheduleMatchesEndfx() {
        val s = T.schedule(T.PODIUM, 5)
        assertContentEquals(longArrayOf(3760, 2460, 1510, 980, 700), s.at)
        assertEquals(4210L, s.burst)
        assertEquals(5360L, s.settled)
        assertEquals(15360L, s.close)
        assertEquals(15360L + T.FADE_OUT_MS, s.end)
    }

    @Test
    fun framesAtFixedTimes() {
        val s = T.schedule(T.PODIUM, 5)
        // Before a place's moment its block is empty; 450 ms later it is full height.
        assertEquals(0.0, T.blockHeight(0, 3000, s))
        assertEquals(T.POD_HEIGHT[0].toDouble(), T.blockHeight(0, 3760 + 450, s), 1e-9)
        // Title fades in over 120..570 ms.
        assertEquals(0.0, T.titleAlpha(100, s), 1e-9)
        assertEquals(1.0, T.titleAlpha(570, s), 1e-9)
        // Countdown after settling, then the fade-out.
        assertEquals(10, T.secondsLeft(5360, s))
        assertEquals(1, T.secondsLeft(14500, s))
        assertEquals(0.0, T.out(15360 + T.FADE_OUT_MS, s), 1e-9)
        // No confetti before the burst, some right after.
        assertTrue(T.confetti(4000, s.burst, 640.0, 200.0, 1.0).isEmpty())
        assertTrue(T.confetti(4400, s.burst, 640.0, 200.0, 1.0).isNotEmpty())
    }

    @Test
    fun bannerHasNoBurst() {
        val s = T.schedule(T.BANNER, 4)
        assertNull(s.burst)
        assertTrue(s.close > s.settled)
    }

    @Test
    fun goalStepperWalksOneFiveTen() {
        assertEquals(listOf(5, 10, 15), generateSequence(1) { goalStep(it, up = true) }.drop(1).take(3).toList())
        assertEquals(listOf(10, 5, 1, 1), generateSequence(15) { goalStep(it, up = false) }.drop(1).take(4).toList())
        assertEquals(10, goalStep(7, up = true))
        assertEquals(5, goalStep(7, up = false))
    }
}
