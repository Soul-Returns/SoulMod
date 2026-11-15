package com.soulreturns.stats

import com.soulreturns.util.SoulLogger
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The `trackerSessions` namespace payload (one global bucket).
 *
 * @property lastAliveAt epoch ms the client was last seen running (stamped every minute and on a clean
 *   shutdown, so a crash loses at most a minute). 0 = never.
 * @property clockMs [TrackerSessions.clock] at that stamp.
 */
@Serializable
data class ClientAlive(val lastAliveAt: Long = 0L, val clockMs: Long = 0L)

/**
 * The one lifetime policy for every tracker panel's Session tab: sessions are persisted and survive a
 * restart, and all of them reset when the client launches more than [FRESH_AFTER_MS] after it last
 * ran ([freshStart]). A quick restart keeps them; the next day starts fresh. No clock-of-day logic.
 *
 * Trackers call [resetOnFreshStart] from their `register()` with the same reset their Reset Session
 * button applies. Session clocks that count wall time use [clock], which only advances while the
 * client runs, so a quick restart does not add the offline gap to a session.
 *
 * Registered in `Soul.kt` before [PersistentStats.register], so the shutdown stamp runs before the
 * synchronous stats flush.
 */
object TrackerSessions {
    const val NAMESPACE: String = "trackerSessions"

    /** A launch this long after the client last ran starts every tracker session fresh. */
    const val FRESH_AFTER_MS: Long = 30 * 60_000L

    private const val STAMP_INTERVAL_MS = 60_000L

    private val logger = SoulLogger("Soul/TrackerSessions")

    private val handle: StatsHandle<ClientAlive> by lazy {
        PersistentStats.namespace(NAMESPACE, ClientAlive.serializer(), { ClientAlive() }, { "client" })
    }

    /** Decided once per launch, in [register]. */
    var freshStart: Boolean = false
        private set

    private var clockBase = 0L
    private var launchAt = System.currentTimeMillis()
    private var lastStampAt = 0L
    private var registered = false
    private val stampHooks = CopyOnWriteArrayList<() -> Unit>()

    /** Pure: no stored stamp, or one older than [FRESH_AFTER_MS], is a fresh start. */
    fun isFreshStart(lastAliveAt: Long, now: Long): Boolean = lastAliveAt <= 0L || now - lastAliveAt > FRESH_AFTER_MS

    fun register() {
        if (registered) return
        registered = true
        val stored = handle.read()
        launchAt = System.currentTimeMillis()
        freshStart = isFreshStart(stored.lastAliveAt, launchAt)
        clockBase = stored.clockMs
        val gap = if (stored.lastAliveAt <= 0L) "never ran" else "last alive ${(launchAt - stored.lastAliveAt) / 1000}s ago"
        logger.info("Tracker sessions: ${if (freshStart) "fresh start" else "resumed"} ($gap)")
        stamp()
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { if (System.currentTimeMillis() - lastStampAt >= STAMP_INTERVAL_MS) stamp() },
        )
        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping { stamp() })
    }

    /** Milliseconds the client has been running, continued across restarts: the session clock. */
    fun clock(): Long = clockBase + (System.currentTimeMillis() - launchAt)

    /** Apply a tracker's session reset to every profile's bucket when this launch is a [freshStart]. */
    fun <T : Any> resetOnFreshStart(handle: StatsHandle<T>, reset: T.() -> T) {
        if (freshStart) handle.updateAll(reset)
    }

    /**
     * Runs at every alive stamp (each minute and on shutdown), before it is written: checkpoint
     * in-memory running time into the stats so a crash or quit loses at most a minute.
     */
    fun onStamp(hook: () -> Unit) {
        stampHooks.add(hook)
    }

    private fun stamp() {
        stampHooks.forEach { it() }
        val now = System.currentTimeMillis()
        lastStampAt = now
        val clock = clock()
        handle.update { ClientAlive(now, clock) }
    }
}
