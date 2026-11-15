package com.soulreturns.features.islandchests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IslandChestSearchSessionTest {
    @Test
    fun gameplayCanOpenSearchBeforeAnythingWasLocated() {
        val session = IslandChestSearchSession()
        assertTrue(session.request(enabled = true, inWorld = true, screenOpen = false, focused = true))
        assertTrue(session.takeRequest(enabled = true, inWorld = true, screenOpen = false, focused = true))
        assertEquals(IslandChestSearchSession.Search(), session.restore("profile", null))
        assertFalse(session.takeRequest(enabled = true, inWorld = true, screenOpen = false, focused = true))
    }

    @Test
    fun returningFromLocatingRestoresQueryAndSelectedItemForThatProfile() {
        val session = IslandChestSearchSession()
        session.remember("profile", "enchanted iron", "IRON")
        assertTrue(session.request(true, true, false, true))
        assertTrue(session.takeRequest(true, true, false, true))
        assertEquals(IslandChestSearchSession.Search("enchanted iron", "IRON"), session.restore("profile", "IRON"))
        assertEquals(IslandChestSearchSession.Search("enchanted iron", "IRON"), session.restore("profile", null))
        assertEquals(IslandChestSearchSession.Search("enchanted iron", "NEW_KEY"), session.restore("profile", "NEW_KEY"))
    }

    @Test
    fun unresolvedAndOtherProfilesNeverRestoreAnotherProfilesSearch() {
        val session = IslandChestSearchSession()
        session.remember("first", "private item", "ITEM")
        assertEquals(IslandChestSearchSession.Search(), session.restore(null, null))
        assertEquals(IslandChestSearchSession.Search(), session.restore("second", null))
        session.remember(null, "unresolved", "ITEM")
        assertEquals(IslandChestSearchSession.Search(), session.restore(null, "ITEM"))
    }

    @Test
    fun disabledFeatureScreensDisconnectAndFocusLossDoNotCapturePresses() {
        val session = IslandChestSearchSession()
        assertFalse(session.request(false, true, false, true))
        assertFalse(session.request(true, false, false, true))
        assertFalse(session.request(true, true, true, true))
        assertFalse(session.request(true, true, false, false))
        assertFalse(session.takeRequest(true, true, false, true))
    }

    @Test
    fun deferredOpenIsCancelledIfGameplayEndsBeforeTheTick() {
        for (context in listOf(
            booleanArrayOf(false, true, false, true),
            booleanArrayOf(true, false, false, true),
            booleanArrayOf(true, true, true, true),
            booleanArrayOf(true, true, false, false),
        )) {
            val session = IslandChestSearchSession()
            assertTrue(session.request(true, true, false, true))
            assertFalse(session.takeRequest(context[0], context[1], context[2], context[3]))
            assertFalse(session.takeRequest(true, true, false, true))
        }
    }
}
