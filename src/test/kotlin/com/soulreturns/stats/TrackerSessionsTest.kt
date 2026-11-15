package com.soulreturns.stats

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerSessionsTest {
    private val now = 1_800_000_000_000L

    @Test
    fun quickRestartKeepsSessions() {
        assertFalse(TrackerSessions.isFreshStart(now - 60_000L, now))
        assertFalse(TrackerSessions.isFreshStart(now - TrackerSessions.FRESH_AFTER_MS, now))
    }

    @Test
    fun longGapResets() {
        assertTrue(TrackerSessions.isFreshStart(now - TrackerSessions.FRESH_AFTER_MS - 1L, now))
        assertTrue(TrackerSessions.isFreshStart(now - 24 * 3_600_000L, now))
    }

    @Test
    fun missingStampResets() {
        assertTrue(TrackerSessions.isFreshStart(0L, now))
    }
}
