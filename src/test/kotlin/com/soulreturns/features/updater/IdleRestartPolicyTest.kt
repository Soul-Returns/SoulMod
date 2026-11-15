package com.soulreturns.features.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdleRestartPolicyTest {
    @Test fun requiresAnUninterruptedIdleMinuteAndOnlyShowsOnce() {
        val policy = IdleRestartPolicy()
        assertFalse(policy.due(0, true, "v1"))
        assertFalse(policy.due(59_999, true, "v1"))
        assertTrue(policy.due(60_000, true, "v1"))
        assertFalse(policy.due(120_000, true, "v1"))
        assertTrue(policy.due(120_000, true, "v2"))
    }

    @Test fun InputRestartsTheIdleWait() {
        val policy = IdleRestartPolicy()
        assertFalse(policy.due(0, true, "v1"))
        policy.activity()
        assertFalse(policy.due(59_000, true, "v1"))
        assertFalse(policy.due(60_000, true, "v1"))
        assertTrue(policy.due(119_000, true, "v1"))
    }

    @Test fun ActivityMenusAndNonAutomaticStatesCannotConsumeThePrompt() {
        val policy = IdleRestartPolicy()
        assertFalse(policy.due(0, true, "v1"))
        assertFalse(policy.due(60_000, false, "v1"))
        assertFalse(policy.due(120_000, true, "v1"))
        assertTrue(policy.due(180_000, true, "v1"))
    }

    @Test fun AlreadyVisibleRestartWindowDoesNotPopUpAgain() {
        val policy = IdleRestartPolicy()
        policy.markShown("v1")
        assertFalse(policy.due(0, true, "v1"))
        assertFalse(policy.due(60_000, true, "v1"))
    }
}
