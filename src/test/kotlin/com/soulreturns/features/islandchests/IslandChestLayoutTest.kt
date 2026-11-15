package com.soulreturns.features.islandchests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IslandChestLayoutTest {
    @Test
    fun offscreenOrZeroAreaPanesNeverSubmitItemRenders() {
        val clip = intArrayOf(0, 100, 200, 200)
        assertFalse(IslandChestLayout.hasVisibleArea(0, 200, 200, 300, clip))
        assertFalse(IslandChestLayout.hasVisibleArea(0, 0, 200, 100, clip))
        assertFalse(IslandChestLayout.hasVisibleArea(200, 100, 300, 200, clip))
        assertFalse(IslandChestLayout.hasVisibleArea(0, 0, 200, 300, intArrayOf(0, 100, 200, 100)))
        assertTrue(IslandChestLayout.hasVisibleArea(0, 190, 200, 230, clip))
    }

    @Test
    fun locatorRequiresEnabledOwnIslandAndSameResolvedProfile() {
        assertTrue(IslandChestLayout.canLocate(true, true, "first", "first"))
        assertFalse(IslandChestLayout.canLocate(false, true, "first", "first"))
        assertFalse(IslandChestLayout.canLocate(true, false, "first", "first"))
        assertFalse(IslandChestLayout.canLocate(true, true, "second", "first"))
        assertFalse(IslandChestLayout.canLocate(true, true, null, null))
        assertFalse(IslandChestLayout.canLocate(true, true, "first", null))
    }

    @Test
    fun observationAgeNeverUsesFetchTimeOrGoesNegativeForClockSkew() {
        assertEquals("just now", IslandChestLayout.age(100_000, 99_000))
        assertEquals("just now", IslandChestLayout.age(0, 59_999))
        assertEquals("1 min ago", IslandChestLayout.age(0, 60_000))
        assertEquals("1 hr ago", IslandChestLayout.age(0, 3_600_000))
        assertEquals("3 days ago", IslandChestLayout.age(0, 259_200_000))
    }
}
