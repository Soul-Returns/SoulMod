package com.soulreturns.features.mining.mineshaft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every recording's harps at the player (`mineshaft/menu-harps.txt`, Organ Donor dings in mineshafts
 * and the harps Hypixel plays for menu clicks) through [MenuQuiet] on 50 ms ticks, with containers
 * open and closed as recorded: every menu harp is blocked, and only a few real dings are lost.
 */
class MenuQuietReplayTest {
    private class Result(var menus: Int = 0, var menusBlocked: Int = 0, var menusOpen: Int = 0, var dings: Int = 0, var dingsOpen: Int = 0, var dingsAfterClose: Int = 0)

    private fun replay(): Result {
        val r = Result()
        var quiet = MenuQuiet()
        var open = false
        var next = 50L
        for (line in javaClass.getResource("/mineshaft/menu-harps.txt")!!.readText().lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split(' ')
            if (f[0] == "rec") {
                quiet = MenuQuiet()
                open = false
                next = 50L
                continue
            }
            val t = f[0].toLong()
            while (next <= t) {
                quiet.tick(open)
                next += 50
            }
            when (f[1]) {
                "open" -> open = true
                "close" -> open = false
                "menu" -> {
                    r.menus++
                    if (quiet.blocks(open)) r.menusBlocked++
                    if (open) r.menusOpen++
                }
                "ding" -> {
                    r.dings++
                    if (open) {
                        r.dingsOpen++
                    } else if (quiet.blocks(false)) {
                        r.dingsAfterClose++
                    }
                }
            }
        }
        return r
    }

    @Test
    fun menuHarpsNeverCountAsDingsAndFewDingsAreLost() {
        val r = replay()
        println(
            "menu harps: ${r.menus}, blocked ${r.menusBlocked} (${r.menusOpen} with the menu still open); " +
                "mineshaft dings: ${r.dings}, lost ${r.dingsOpen} with a container open and ${r.dingsAfterClose} within ${MenuQuiet.QUIET_TICKS} ticks after one closed",
        )
        assertEquals(291, r.menus)
        assertEquals(r.menus, r.menusBlocked, "a menu harp got through")
        assertTrue(r.menusOpen < r.menus, "the Bazaar chime plays after its menu closed: the window after the close is load-bearing")
        assertTrue(r.dingsOpen + r.dingsAfterClose <= r.dings / 50, "more than 2 % of the real dings lost")
    }
}
