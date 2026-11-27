package com.soulreturns.features.scoreboard

import com.soulreturns.config.ScoreboardConfig
import com.soulreturns.config.ScoreboardLook
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Keep lines while switching islands": the last lines stay for 10 s while the sidebar is empty. */
class ScoreboardKeepTest {
    @BeforeTest
    @AfterTest
    fun reset() = Scoreboard.forgetLastShown()

    private val sidebar = ScoreboardSnapshot.ofSidebar("§e§lSKYBLOCK", listOf("§7 11/27/26 m1A", "§7 ⏣ Hub", "", "§ewww.hypixel.net"))
    private val switching = ScoreboardSnapshot(onSkyblock = true)
    private fun cfg(keep: Boolean) = ScoreboardConfig(enabled = true, look = ScoreboardLook(keepWhileSwitching = keep))

    @Test
    fun holdsLinesForTenSecondsWhenOn() {
        val c = cfg(true)
        val shown = Scoreboard.resolve(c, sidebar, 1_000)
        assertTrue(shown.isNotEmpty())
        assertEquals(shown, Scoreboard.resolve(c, switching, 5_000))
        assertTrue(Scoreboard.resolve(c, switching, 11_001).isEmpty())
        assertTrue(Scoreboard.resolve(c, sidebar, 12_000).isNotEmpty())
    }

    @Test
    fun showsNothingWhenOff() {
        val c = cfg(false)
        assertTrue(Scoreboard.resolve(c, sidebar, 1_000).isNotEmpty())
        assertTrue(Scoreboard.resolve(c, switching, 2_000).isEmpty())
    }
}
