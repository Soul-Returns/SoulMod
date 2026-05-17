package com.soulreturns.data.prices

import com.google.gson.JsonParser
import com.soulreturns.platform.http.SoulHttp
import com.soulreturns.util.SoulLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory cache of Hypixel SkyBlock item prices, refreshed in the background from the
 * Hypixel public Bazaar API + Elite's lowest-BIN mirror.
 *
 * **Polling cadence:** 2 minutes for both feeds (matching SkyHanni's rhythm — long enough
 * to be a polite API citizen, short enough that profit displays don't feel stale). The
 * loop runs on a dedicated daemon thread (`soul-prices`) — **never on `SoulExecutor`**, for
 * the same reason `SyncEngine` and `RealtimeClient` use their own threads: the JDK
 * `HttpClient` shares `SoulExecutor` for selector dispatch on the rest of the codebase, and
 * blocking on `client.send()` from inside that 2-thread pool would starve it.
 *
 * **No isOnSkyblock gate.** The Hypixel bazaar + Elite endpoints are tiny public APIs and
 * an idle player isn't worth a 2-minute polling skip — earlier versions of this file did
 * gate on `SkyblockApi.isOnSkyblock`, which made dev testing painful (open the game, run
 * `/soul dev grantDragonDrop` immediately, see zeros because the first fetch hadn't fired
 * yet). The cost of polling unconditionally is negligible.
 *
 * **Lookup is lock-free.** Both maps are `ConcurrentHashMap`. Callers (profit trackers,
 * HUD composables) just call [price] from any thread.
 *
 * Endpoint sources (mirror what SkyHanni uses):
 *  - `https://api.hypixel.net/v2/skyblock/bazaar` — public, no API key required.
 *  - `https://api.eliteskyblock.com/resources/auctions/neu` — Elite's scrape of the AH API
 *    rebroadcast as `{<NEU_INTERNAL_NAME>: <lowestBin>}`.
 */
object PriceCache {
    private val logger = SoulLogger("Soul/PriceCache")

    private const val BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar"
    private const val LOWEST_BIN_URL = "https://api.eliteskyblock.com/resources/auctions/neu"
    private const val POLL_INTERVAL_MS = 2 * 60 * 1000L

    /** Bazaar product id (e.g. `"SUMMONING_EYE"`, `"ENCHANTED_ENDER_PEARL"`) → `(buy, sell)`. */
    private val bazaarMap: ConcurrentHashMap<String, BazaarPrices> = ConcurrentHashMap()
    private val lowestBinMap: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    private val lastBazaarRefresh = AtomicLong(0)
    private val lastLowestBinRefresh = AtomicLong(0)

    @Volatile private var thread: Thread? = null

    @Volatile private var running: Boolean = false

    /**
     * Per-product bazaar quote. [instantBuy] = what the player gets if they sell at the top
     * buy order (instasell). [instantSell] = what the player pays if they buy at the top
     * sell order (instabuy). Both are computed by Hypixel and exposed under each product's
     * `quick_status` block.
     */
    data class BazaarPrices(val instantBuy: Long, val instantSell: Long)

    /**
     * Start the polling daemon. Idempotent — subsequent calls are no-ops. Called from
     * `Soul.onInitializeClient()`.
     */
    fun start() {
        if (running) return
        running = true
        val t =
            Thread({ runLoop() }, "soul-prices").apply {
                isDaemon = true
            }
        thread = t
        t.start()
    }

    /**
     * Force an immediate synchronous fetch on the caller's thread — used by `/soul dev
     * refreshPrices`. Returns a status string `"Bazaar: ... · Lowest BIN: ..."` so the dev
     * command can echo what actually happened (loaded counts, HTTP statuses, errors).
     * Resets the rate limiters so the polling loop also picks up the change.
     *
     * **Synchronous on purpose** — previously this only poked the polling thread, and the
     * dev command's followup snapshot read stale `lastRefresh` timestamps. Doing the work
     * here is fine because `SoulHttp.client.send()` uses its own internal executor (see
     * the kdoc on `SoulHttp.client`); we're not blocking anything important.
     */
    fun refreshNow(): String {
        lastBazaarRefresh.set(0)
        lastLowestBinRefresh.set(0)
        val bazaar = fetchBazaar()
        val bin = fetchLowestBin()
        // Wake the polling thread too so its rate limiters reset on the right schedule.
        thread?.interrupt()
        return "Bazaar: $bazaar · Lowest BIN: $bin"
    }

    /**
     * Coin value of one unit of [itemId] under [source]. Returns 0 when the price isn't
     * cached yet (e.g. before the first poll completes, or for items not present in either
     * feed). Callers display "—" / skip the value when this returns 0 rather than printing
     * a confidently-wrong "0 coins".
     *
     * **Fallback order:** the [source] is consulted first; if it returns 0 the cache falls
     * back across feeds in the SkyHanni-style chain (bazaar → lowest-BIN). For items that
     * exist only in the bazaar (enchanted blocks, raw materials), [LOWEST_BIN] will miss and
     * the fallback gives bazaar. For items only on the AH (pets, weapons, armor), the
     * bazaar misses and the fallback gives BIN.
     */
    fun price(
        itemId: String,
        source: PriceSource = PriceSource.BAZAAR_INSTANT_BUY,
    ): Long {
        val primary =
            when (source) {
                PriceSource.BAZAAR_INSTANT_BUY -> bazaarMap[itemId]?.instantBuy ?: 0L
                PriceSource.BAZAAR_INSTANT_SELL -> bazaarMap[itemId]?.instantSell ?: 0L
                PriceSource.LOWEST_BIN -> lowestBinMap[itemId] ?: 0L
            }
        if (primary > 0L) return primary
        // Fallback chain — see kdoc above.
        return when (source) {
            PriceSource.BAZAAR_INSTANT_BUY, PriceSource.BAZAAR_INSTANT_SELL ->
                lowestBinMap[itemId] ?: 0L
            PriceSource.LOWEST_BIN ->
                bazaarMap[itemId]?.instantBuy ?: 0L
        }
    }

    /** Diagnostic snapshot used by `/soul dev refreshPrices`. */
    fun snapshot(): String {
        val bz = bazaarMap.size
        val bin = lowestBinMap.size
        val bzAge = (System.currentTimeMillis() - lastBazaarRefresh.get()) / 1000
        val binAge = (System.currentTimeMillis() - lastLowestBinRefresh.get()) / 1000
        return "Bazaar: $bz products (${bzAge}s ago) · Lowest BIN: $bin items (${binAge}s ago)"
    }

    private fun runLoop() {
        // No leading sleep — fire the first fetch immediately on thread start so a player
        // who joins, opens the dragon HUD and uses the dev grant command within seconds
        // sees real prices instead of zeros. Subsequent loops respect POLL_INTERVAL_MS via
        // the rate-limiter inside fetchBazaar / fetchLowestBin.
        while (running) {
            try {
                fetchBazaar()
                fetchLowestBin()
                sleepInterruptibly(SLEEP_TICK_MS)
            } catch (_: InterruptedException) {
                // Either shutdown (running=false) or refreshNow() poked us — re-enter the
                // loop unconditionally; the rate limiters decide whether work happens.
            } catch (t: Throwable) {
                logger.warn("PriceCache loop error: ${t.message}", t)
                sleepInterruptibly(SLEEP_TICK_MS)
            }
        }
    }

    /**
     * Fetch the bazaar feed if the rate-limit window has elapsed. Returns a short status
     * string suitable for the `/soul dev refreshPrices` echo: `"<N> products"` on success,
     * `"skipped (Xs ago)"` when called inside the rate-limit window, or `"HTTP nnn"` /
     * `"error: <msg>"` on failure.
     */
    private fun fetchBazaar(): String {
        val now = System.currentTimeMillis()
        val sinceLast = now - lastBazaarRefresh.get()
        if (sinceLast < POLL_INTERVAL_MS) return "skipped (${sinceLast / 1000}s ago, $POLL_INTERVAL_MS ms window)"
        return try {
            val response = SoulHttp.get(BAZAAR_URL)
            if (response.statusCode() / 100 != 2) {
                logger.warn("Bazaar fetch failed: HTTP ${response.statusCode()}")
                return "HTTP ${response.statusCode()}"
            }
            val parsed = parseBazaar(response.body())
            bazaarMap.clear()
            bazaarMap.putAll(parsed)
            lastBazaarRefresh.set(now)
            logger.info("Bazaar refreshed: ${parsed.size} products")
            "${parsed.size} products"
        } catch (t: Throwable) {
            logger.warn("Bazaar fetch error: ${t.message}", t)
            "error: ${t.message}"
        }
    }

    /** Lowest-BIN counterpart to [fetchBazaar]. Same return-string conventions. */
    private fun fetchLowestBin(): String {
        val now = System.currentTimeMillis()
        val sinceLast = now - lastLowestBinRefresh.get()
        if (sinceLast < POLL_INTERVAL_MS) return "skipped (${sinceLast / 1000}s ago)"
        return try {
            val response = SoulHttp.get(LOWEST_BIN_URL)
            if (response.statusCode() / 100 != 2) {
                logger.warn("Lowest-BIN fetch failed: HTTP ${response.statusCode()}")
                return "HTTP ${response.statusCode()}"
            }
            val parsed = parseLowestBin(response.body())
            lowestBinMap.clear()
            lowestBinMap.putAll(parsed)
            lastLowestBinRefresh.set(now)
            logger.info("Lowest BIN refreshed: ${parsed.size} items")
            "${parsed.size} items"
        } catch (t: Throwable) {
            logger.warn("Lowest-BIN fetch error: ${t.message}", t)
            "error: ${t.message}"
        }
    }

    private fun parseBazaar(body: String): Map<String, BazaarPrices> {
        val root = JsonParser.parseString(body).asJsonObject
        if (!root.has("success") || !root.get("success").asBoolean) {
            logger.warn("Bazaar response success=false")
            return emptyMap()
        }
        val products = root.getAsJsonObject("products") ?: return emptyMap()
        val out = HashMap<String, BazaarPrices>(products.size())
        for ((id, element) in products.entrySet()) {
            val obj = element.asJsonObject
            val quick = obj.getAsJsonObject("quick_status") ?: continue
            val buyPrice = quick.get("buyPrice")?.asDouble?.toLong() ?: 0L
            val sellPrice = quick.get("sellPrice")?.asDouble?.toLong() ?: 0L
            // Hypixel `buyPrice` = the ask side (what an insta-buy hits, i.e. what the
            // PLAYER pays); `sellPrice` = the bid side (what an insta-sell hits, i.e. what
            // the PLAYER receives). Names match our player-facing PriceSource semantics
            // directly — no swap needed.
            out[id] = BazaarPrices(instantBuy = buyPrice, instantSell = sellPrice)
        }
        return out
    }

    private fun parseLowestBin(body: String): Map<String, Long> {
        val root = JsonParser.parseString(body).asJsonObject
        val out = HashMap<String, Long>(root.size())
        for ((id, element) in root.entrySet()) {
            try {
                out[id] = element.asLong
            } catch (_: Throwable) {
                // Skip entries with non-numeric values — defensive against Elite schema
                // changes.
            }
        }
        return out
    }

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            throw e
        }
    }

    /** Loop tick: long enough to be cheap, short enough that startup + manual refresh feel responsive. */
    private const val SLEEP_TICK_MS = 5 * 1000L
}
