package com.soulreturns.platform.updater

import kotlin.random.Random

/**
 * Pure scheduling of the update checks a backend `mod-release` hint triggers. The hint is never
 * trusted, so this only decides *when* to run the normal check: after a random 5-60 s delay (online
 * clients must not hit the backend in the same second), one pending check at a time, and at least
 * [MIN_GAP_MS] after the previous push-triggered check; events inside that window coalesce into the
 * one check at its end. Times are epoch milliseconds from the caller.
 */
class PushCheckScheduler(private val random: Random = Random.Default) {
    private var pendingAt: Long? = null
    private var lastRunAt: Long? = null

    @get:Synchronized
    val scheduledAt: Long? get() = pendingAt

    @get:Synchronized
    val lastRun: Long? get() = lastRunAt

    /** Delay in ms until the check should run, or null when one is already pending. */
    @Synchronized
    fun onEvent(now: Long): Long? {
        if (pendingAt != null) return null
        val jittered = now + MIN_DELAY_MS + random.nextLong(MAX_DELAY_MS - MIN_DELAY_MS + 1)
        val due = maxOf(jittered, (lastRunAt ?: Long.MIN_VALUE / 2) + MIN_GAP_MS)
        pendingAt = due
        return due - now
    }

    /** The pending check is about to run. */
    @Synchronized
    fun started(now: Long) {
        pendingAt = null
        lastRunAt = now
    }

    companion object {
        const val MIN_DELAY_MS = 5_000L
        const val MAX_DELAY_MS = 60_000L
        const val MIN_GAP_MS = 5 * 60_000L

        /** A well-formed `mod-release` payload names a version and a channel; neither is used. */
        fun wellFormed(version: String?, channel: String?): Boolean = !version.isNullOrBlank() && !channel.isNullOrBlank()
    }
}
