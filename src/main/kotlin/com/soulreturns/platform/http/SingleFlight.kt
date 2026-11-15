package com.soulreturns.platform.http

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * At most one handshake at a time. A caller that finds no usable value takes the lock and runs
 * [run]'s `handshake`; every other caller parks on the lock and, once it gets it, uses whatever the
 * winner left behind instead of starting a handshake of its own. Without this, N concurrent callers
 * at startup meant N Mojang `joinServer` calls, each replacing the previous serverId (so the backend
 * rejected all but one) and burning Mojang's per-account join budget.
 *
 * Callers block their thread while waiting, so this is for IO workers and dedicated threads only.
 *
 * @param cached the currently usable value, or null.
 * @param blocked whether a cooldown forbids a new attempt right now; its argument says the caller
 *   may bypass the generic-failure cooldown (a deliberate user action), never the rate-limit one.
 */
internal class SingleFlight<T : Any>(
    private val cached: () -> T?,
    private val blocked: (bypassCooldown: Boolean) -> Boolean,
) {
    private val lock = ReentrantLock()
    private val attempts = AtomicInteger()

    /**
     * @param skipCache run a handshake even though [cached] has a value (a forced refresh).
     * @param onMinted called with a freshly minted value after the lock is released, so listeners
     *   cannot deadlock or hold up the queue.
     * @param handshake returns the new value, or null on failure; it counts as an attempt either way.
     */
    fun run(
        skipCache: Boolean,
        bypassCooldown: Boolean,
        onMinted: (T) -> Unit,
        handshake: () -> T?,
    ): T? {
        if (!skipCache) cached()?.let { return it }
        val seen = attempts.get()
        var minted: T? = null
        val result: T?
        lock.lock()
        try {
            // Someone attempted while we waited: take their outcome (null after a failure) rather
            // than hammering Mojang again, forced or not.
            result = if (attempts.get() != seen) {
                cached()
            } else if (!skipCache && cached() != null) {
                cached()
            } else if (blocked(bypassCooldown)) {
                null
            } else {
                try {
                    handshake().also { minted = it }
                } finally {
                    attempts.incrementAndGet()
                }
            }
        } finally {
            lock.unlock()
        }
        minted?.let(onMinted)
        return result
    }
}

/** Whether [t] (or a cause) is a Mojang session-service HTTP 429, e.g. `RateLimiter disallowed request`. */
internal fun isMojangRateLimit(t: Throwable): Boolean {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth++ < 8) {
        val text = "${cur.javaClass.simpleName} ${cur.message ?: ""} ${cur}"
        if ("RateLimiter disallowed request" in text || "status=429" in text || "HTTP 429" in text) return true
        cur = cur.cause
    }
    return false
}
