package com.soulreturns.features.dungeons

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DungeonCommandGuardTest {
    private fun blocked(command: String, inDungeon: Boolean = true, enabled: Boolean = true, allowDh: Boolean = true) =
        DungeonCommandGuard.blocks(command, inDungeon, enabled, allowDh)

    @Test
    fun leavingCommandsBlocked() {
        listOf(
            "warp hub", "warp anything_new", "/warp hub", "WARP Hub", "  warp   hub  ", "warp\tforge",
            "hub", "is", "island", "l", "lobby", "leave", "garden", "warpforge", "rejoin", "skyblock", "sb",
            "play skyblock", "visit Notch", "hub extra args",
        ).forEach { assertTrue(blocked(it), it) }
    }

    @Test
    fun harmlessCommandsPass() {
        listOf(
            "", "  ", "warp", "visit", "play", "pc hello", "p", "party", "p list", "p invite Notch",
            "party kick Notch", "pv", "sbmenu", "warpback", "ec", "pets", "soul", "gfs ender_pearl 16",
            "hubx", "islands", "p leave", "party leave", "P   Leave", "p disband", "party disband",
            "p transfer Notch", "party warp",
        ).forEach { assertFalse(blocked(it), it) }
    }

    @Test
    fun dungeonHubException() {
        listOf("warp dh", "warp dungeon_hub", "/WARP DH", "warp  dungeon_hub ").forEach {
            assertFalse(blocked(it), it)
            assertTrue(blocked(it, allowDh = false), it)
        }
        assertTrue(blocked("warp dungeon"))
        assertTrue(blocked("warp hub", allowDh = true))
    }

    @Test
    fun onlyInRunWithToggle() {
        assertFalse(blocked("warp hub", inDungeon = false))
        assertFalse(blocked("warp hub", enabled = false))
        assertFalse(blocked("pc hi", inDungeon = false))
    }
}
