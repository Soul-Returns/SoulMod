package com.soulreturns.data.prices

import com.soulreturns.platform.catalog.BackendCatalog
import com.soulreturns.platform.catalog.CatalogContents
import com.soulreturns.platform.catalog.CatalogRegistry
import com.soulreturns.platform.catalog.CatalogStatus
import com.soulreturns.util.SoulLogger

/**
 * The client mirror of `GET /prices` — the merged bazaar + lowest-BIN market, served from our
 * backend as one gzipped payload instead of two upstream feeds polled per client.
 *
 * ### Why this is a catalog and not two direct polls
 * It used to be two direct polls, and [PriceCache]'s own KDoc argued for keeping them that way. That
 * argument does not survive the numbers: Hypixel's bazaar is **3.5 MB** and Elite's mirror **124 KB**,
 * fetched every two minutes unconditionally — title screen included — which is ~106 MB/hour, about
 * **2.5 GB per client per day**, once per user. The same market from our backend measures **~570 KB
 * identity and ~72 KB gzipped** (measured 2026-08-23, 6887 rows): roughly **50× less** on the wire,
 * with one upstream poll shared by everyone instead of one per client. That last factor depends on
 * [com.soulreturns.platform.http.SoulHttp] asking for gzip, which the JDK does not do by itself.
 *
 * The staleness objection was simply misplaced. A lookup reads an in-memory map either way, so a
 * proxy hop cannot touch render latency — it can only change how fresh that map is, and against a
 * two-minute poll interval a backend that repolls on the same cadence and pushes an invalidate the
 * moment it does is, if anything, ahead.
 *
 * ### Five minutes, not two hours
 * This is the one catalog that overrides [BackendCatalog.staleAfterMs]. The default two hours is
 * double an admin-curated endpoint's TTL and assumes the data changes when a human edits it. Prices
 * are rebuilt every two minutes by a machine, so a client whose realtime connection has dropped
 * would spend two hours quoting a dead market while every diagnostic reported a healthy catalog.
 * Five minutes is a little over double this endpoint's own 2 min TTL — the same "double the TTL"
 * rule, applied to the right TTL.
 *
 * ### The index is rebuilt on snapshot swap
 * [byId] is probed from the profit panel's layout, twice per frame, and from the chat announcer.
 * Walking ~6900 rows per lookup is not an option, so the map is built once per swap into a local and
 * published with a single volatile assignment — a reader never observes it half-built.
 *
 * Like every catalog it has **no config toggle and no bundled fallback snapshot**, and any failure
 * leaves the previous snapshot in place. A price feed a user can switch off is only a way to make
 * the profit panel look broken.
 */
object PriceCatalogClient {
    private val logger = SoulLogger("Soul/Catalog")

    /** The `sync-invalidate` kind the backend publishes after each of its own upstream polls. */
    const val KIND: String = "prices"

    /** Comfortably over double the endpoint's 2 min TTL. See the class doc. */
    private const val STALE_AFTER_MS = 5L * 60 * 1000

    private val catalog = BackendCatalog(
        kind = KIND,
        endpoint = "/prices",
        cacheFile = "price_catalog.json",
        serializer = PriceCatalogSnapshot.serializer(),
        empty = PriceCatalogSnapshot(),
        describe = { CatalogContents(it.prices.size, it.updatedAt) },
        staleAfterMs = STALE_AFTER_MS,
    )

    @Volatile private var byId: Map<String, PriceEntry> = emptyMap()

    /** How many rows carry a bazaar quote at all. Diagnostics — it is how you see one feed missing. */
    @Volatile private var bazaarRows: Int = 0

    /** How many rows have something currently listed on the auction house. Diagnostics. */
    @Volatile private var lowestBinRows: Int = 0

    val snapshot: PriceCatalogSnapshot get() = catalog.snapshot

    val bazaarQuotes: Int get() = bazaarRows

    val lowestBinQuotes: Int get() = lowestBinRows

    /** Declare the catalog. [CatalogRegistry.init] is what loads and refreshes it. */
    fun init() {
        rebuild(catalog.snapshot)
        catalog.addListener(::rebuild)
        CatalogRegistry.register(catalog)
    }

    suspend fun refresh(): String = catalog.refresh()

    fun status(): CatalogStatus = catalog.status()

    /**
     * The market row for [id], or null when this build has never seen the id.
     *
     * A plain map read, safe from any thread including a render path — that property is load-bearing
     * for every caller in [PriceCache] and must not acquire a lock or suspend.
     */
    fun byId(id: String): PriceEntry? = byId[id]

    fun isEmpty(): Boolean = byId.isEmpty()

    /** The in-game name the backend saw for a market [key] (runes only, [PriceCatalogSnapshot.names]); a plain map read. */
    fun name(key: String): String? = catalog.snapshot.names[key]

    private fun rebuild(fresh: PriceCatalogSnapshot) {
        val ids = HashMap<String, PriceEntry>(fresh.prices.size)
        var bazaar = 0
        var bin = 0
        for (row in fresh.prices) {
            if (row.id.isEmpty()) continue
            ids[row.id] = row
            if (row.bazaar != null) bazaar++
            if (row.lowestBin != null) bin++
        }
        byId = ids
        bazaarRows = bazaar
        lowestBinRows = bin
        logger.info("Indexed ${ids.size} prices ($bazaar bazaar quotes, $bin listed on the AH)")
    }
}
