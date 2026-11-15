package com.soulreturns.platform.catalog

import com.soulreturns.core.events.Events
import com.soulreturns.platform.realtime.SyncInvalidate
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every [BackendCatalog] in the mod, and the one subscription that keeps them fresh.
 *
 * Doc 06's sea-creature client owned its own `SyncInvalidate` subscription and its own staleness
 * tick. With four catalogs that would be four subscriptions each rejecting three quarters of what
 * they see, and four tick handlers doing the same arithmetic. So the routing lives here: one
 * subscription, one tick, and a lookup by kind.
 *
 * The staleness *interval* stayed with the catalog even so — see [DEFAULT_STALE_AFTER_MS]. Sharing
 * the tick is deduplication; sharing the deadline would have been an assumption that every catalog
 * ages at the same rate, which prices disproved.
 *
 * ### Topic first, then kind
 * `sync/catalogs` is the only topic carrying `sync-invalidate` today — settings sync was withdrawn on
 * 2026-08-20 and the per-player `user/<uuid>` variant went with it — so matching on kind alone would
 * happen to work. That is exactly what the legacy sync engine did, and it logged "unknown kind" for
 * every event belonging to another vocabulary. Another topic's kinds are not errors; they are
 * someone else's mail, dropped without a word.
 *
 * ### And a kind nobody claimed is also silence
 * The backend can publish a catalog this build has never heard of. Ignoring it is what lets the
 * backend ship a new catalog before the mod release that consumes it.
 */
object CatalogRegistry {
    private val logger = SoulLogger("Soul/Catalog")

    /** The topic the backend publishes catalog invalidates on. Checked before the kind. */
    private const val CATALOG_TOPIC = "sync/catalogs"

    /** Five rapid admin edits should cost one refetch, not five. */
    internal const val INVALIDATE_DEBOUNCE_MS = 1_000L

    /**
     * The default staleness backstop — double the hour-long TTL an admin-curated catalog carries.
     * The Mercure hub has no event replay, so an invalidate published during a reconnect gap is
     * simply lost; without a backstop a client would sit on a stale catalog until the next restart.
     *
     * **A catalog may override it, and one does.** This interval is right when the data changes
     * because a human edited it, and wrong when the backend regenerates it on a timer — prices are
     * rebuilt every two minutes, so they pass their own five in
     * [com.soulreturns.data.prices.PriceCatalogClient]. The tick below reads each catalog's own
     * value; only the default lives here.
     */
    internal const val DEFAULT_STALE_AFTER_MS = 2L * 60 * 60 * 1000

    /** How often the staleness backstop is evaluated, in client ticks (20/s) — once a minute. */
    private const val STALE_CHECK_TICKS = 20 * 60

    private val catalogs = CopyOnWriteArrayList<BackendCatalog<*>>()

    private var initialized = false

    private var ticksSinceStaleCheck = 0

    /** Declare a catalog. Call before [init]; catalogs registered afterwards are initialised at once. */
    fun register(catalog: BackendCatalog<*>) {
        catalogs.add(catalog)
        if (initialized) catalog.init()
    }

    /**
     * Load every registered catalog from disk, subscribe to invalidates, wire the staleness backstop,
     * then refresh them all. Called once from `Soul.onInitializeClient`.
     */
    fun init() {
        if (initialized) return
        initialized = true
        Events.subscribe<SyncInvalidate> { event -> onInvalidate(event) }
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick {
                if (++ticksSinceStaleCheck < STALE_CHECK_TICKS) return@EndTick
                ticksSinceStaleCheck = 0
                val now = System.currentTimeMillis()
                for (catalog in catalogs) {
                    val since = catalog.lastSuccessAtMs
                    if (since == 0L || now - since > catalog.staleAfterMs) {
                        catalog.scheduleRefresh("staleness backstop")
                    }
                }
            },
        )
        for (catalog in catalogs) catalog.init()
    }

    /** Diagnostics for `/soul dev catalogs`, in registration order. */
    fun statuses(): List<CatalogStatus> = catalogs.map { it.status() }

    fun byKind(kind: String): BackendCatalog<*>? = catalogs.firstOrNull { it.kind == kind }

    private fun onInvalidate(event: SyncInvalidate) {
        if (event.topic != CATALOG_TOPIC) return
        val catalog = catalogs.firstOrNull { it.kind == event.kind } ?: return
        logger.debug("Invalidate for '${event.kind}' on ${event.topic}")
        catalog.scheduleRefresh("realtime invalidate")
    }
}
