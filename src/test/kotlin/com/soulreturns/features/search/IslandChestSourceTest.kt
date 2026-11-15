package com.soulreturns.features.search

import kotlin.test.Test
import kotlin.test.assertEquals

class IslandChestSourceTest {
    @Test
    fun coopTextFollowsTheLastSyncOutcomeAnywhere() {
        assertEquals("co-op sharing off", IslandChestSource.coopText(false, "offline"))
        assertEquals("co-op sharing on", IslandChestSource.coopText(true, "shared"))
        assertEquals("co-op sharing on", IslandChestSource.coopText(true, "syncing"))
        assertEquals("co-op sharing on", IslandChestSource.coopText(true, "local"))
        assertEquals("co-op offline", IslandChestSource.coopText(true, "offline"))
        assertEquals("co-op sync paused", IslandChestSource.coopText(true, "rate-limited"))
        assertEquals("co-op sync unavailable", IslandChestSource.coopText(true, "forbidden"))
    }
}
