package com.soulreturns.platform.concurrent

import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents

/**
 * The mod's single owner of background work.
 *
 * Everything that talks to the network or the disk off the client thread runs in [io]. It is a
 * [SupervisorJob] so one failing coroutine never takes its siblings down, and it is cancelled on
 * `CLIENT_STOPPING` so a quit never leaves work running against a dying client.
 *
 * **Why a scope and not a thread pool.** The legacy mod had a fixed 2-thread `SoulExecutor` and
 * then grew a bespoke thread for each subsystem that needed to block (sync engine, realtime, price
 * cache), each carrying a comment warning the next reader not to block on the pool. That is a
 * starvation bug wearing three hats. [Dispatchers.IO] is elastic, so a blocking JDK `HttpClient`
 * call parks a worker it can spare instead of deadlocking the whole mod.
 *
 * **The one deliberate exception** is the realtime SSE reader
 * ([com.soulreturns.platform.realtime.RealtimeClient]): it is a permanently-blocked reader, not a
 * task, and pinning an `IO` worker for the entire session is worse than owning one named daemon
 * thread you can see in a thread dump.
 */
object SoulScope {
    private val logger = SoulLogger("Soul/Scope")

    private val job = SupervisorJob()

    /**
     * Catches anything a launched coroutine throws. A `launch` with no handler would route the
     * exception to the thread's default handler and print a bare stack trace with no attribution;
     * this labels it and keeps the scope alive.
     */
    private val handler = CoroutineExceptionHandler { context, t ->
        logger.warn("Uncaught in ${context[CoroutineName]?.name ?: "soul coroutine"}", t)
    }

    /** Network + disk. Use for anything that must not run on the client thread. */
    val io: CoroutineScope = CoroutineScope(job + Dispatchers.IO + CoroutineName("soul-io") + handler)

    private var registered = false

    /** Wire the shutdown hook. Called once from [com.soulreturns.Soul.onInitializeClient]. */
    fun register() {
        if (registered) return
        registered = true
        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping { shutdown() })
    }

    /** Cancel every outstanding coroutine. Idempotent; the scope is unusable afterwards. */
    fun shutdown() {
        if (!job.isActive) return
        logger.info("Cancelling background work")
        io.cancel()
    }
}
