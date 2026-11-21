package com.soulreturns.features.contests.hud

import kotlin.test.Test
import kotlin.test.assertEquals

class ContestHudRowsTest {
    @Test
    fun `a small contest shows everyone wherever you stand`() {
        for (idx in 0 until 3) assertEquals(listOf(0, 1, 2), ContestHud.compactWindow(3, idx))
        for (idx in 0 until 4) assertEquals(listOf(0, 1, 2, 3), ContestHud.compactWindow(4, idx))
    }

    @Test
    fun `a larger contest shows the leader and three rows around you`() {
        assertEquals(listOf(0, 1, 2, 3), ContestHud.compactWindow(8, 0))
        assertEquals(listOf(0, 1, 2, 3), ContestHud.compactWindow(8, 1))
        assertEquals(listOf(0, 3, 4, 5), ContestHud.compactWindow(8, 4))
        assertEquals(listOf(0, 5, 6, 7), ContestHud.compactWindow(8, 7))
    }
}
