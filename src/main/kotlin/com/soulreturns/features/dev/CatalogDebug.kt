package com.soulreturns.features.dev

import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceSource
import com.soulreturns.data.prices.MarketItemKey
import com.soulreturns.data.prices.MarketValue
import com.soulreturns.data.prices.MarketValues
import com.soulreturns.platform.realtime.RealtimeClient
import net.minecraft.client.Minecraft
import com.soulreturns.platform.catalog.CatalogRegistry
import com.soulreturns.platform.concurrent.SoulScope
import kotlinx.coroutines.launch
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * `/soul dev catalogs` and `/soul dev prices` — the state of everything this client mirrors.
 *
 * All four catalogs fail the same way: silently, leaving the last good copy in place. That is the
 * right behaviour — a stale catalog beats an empty one — but it means a feature quietly working from
 * six-hour-old data is indistinguishable from one working correctly until something says so.
 *
 * Prices are a catalog like the other three as of this lane; `/soul dev prices` is a second view over
 * the same one, because a merged market has a question the generic dump cannot ask — which of its two
 * upstream feeds is stale.
 */
object CatalogDebug {
    fun catalogs() {
        DevOutput.begin("/soul dev catalogs")
        DevOutput.line("§7Every catalog mirrors one backend endpoint, keeps its own snapshot on disk, and")
        DevOutput.line("§7refreshes on a realtime invalidate — with a per-catalog backstop, because the hub has")
        DevOutput.line("§7no replay. Two hours for an admin-curated catalog; five minutes for prices, which the")
        DevOutput.line("§7backend rebuilds every two.")
        DevOutput.line("")
        val statuses = CatalogRegistry.statuses()
        if (statuses.isEmpty()) {
            DevOutput.line("§cNo catalogs registered. That is a wiring bug, not a network problem.")
            return
        }
        for (status in statuses) {
            DevOutput.line("§b${status.kind} §8(GET ${status.endpoint})")
            DevOutput.line("  entries=§f${status.entries}§r source=§f${status.source}")
            DevOutput.line("  updatedAt=§f${status.updatedAt?.let { Instant.ofEpochMilli(it) } ?: "-"}")
            DevOutput.line("  lastRefresh=§f${ago(status.lastRefreshAt)}§r lastSuccess=§f${ago(status.lastSuccessAt)}")
            DevOutput.line("  staleAfter=§f${status.staleAfterMs / 1000}s")
            DevOutput.line("  lastError=§f${status.lastError ?: "-"}")
            // A body that will not parse is the one failure that never fixes itself, and it presents
            // as an ordinary empty catalog. Name it rather than leaving it to be inferred.
            if (status.lastError?.contains("malformed body") == true) {
                DevOutput.line("  §cThis body does not deserialize, so the catalog will stay empty until the mod or")
                DevOutput.line("  §cthe backend changes — retrying cannot fix it. The path in the message is the field:")
                DevOutput.line("  §ca null against a non-nullable property, or a type that moved.")
            } else if (status.entries == 0) {
                DevOutput.line("  §8Empty. Whatever consumes this catalog will match nothing at all — that is the")
                DevOutput.line("  §8designed failure mode, not a separate bug to chase.")
            }
            DevOutput.line("")
        }
        DevOutput.line("§bDisk caches §8(${FabricLoader.getInstance().configDir.resolve("soul")})")
        for (name in CACHE_FILES) {
            val path = FabricLoader.getInstance().configDir.resolve("soul").resolve(name)
            val size = if (Files.isRegularFile(path)) "${Files.size(path)} bytes" else "§8absent"
            DevOutput.line("  $name: §f$size")
        }
        DevOutput.line("")
        DevOutput.line("§8Item display-name collisions: ${ItemCatalogClient.displayNameCollisions} — expected, in the hundreds.")
        DevOutput.line("§8Hypixel ships cosmetic variants and tiered duplicates sharing a name; the shortest id wins.")
    }

    /** Force every catalog to refetch, bypassing the HTTP cache as a refresh always does. */
    fun refreshCatalogs() {
        DevOutput.begin("/soul dev catalogs refresh")
        val statuses = CatalogRegistry.statuses()
        for (status in statuses) {
            val catalog = CatalogRegistry.byKind(status.kind) ?: continue
            DevOutput.line("§7Refetching §f${catalog.endpoint}§7 …")
            SoulScope.io.launch {
                val outcome = catalog.refresh()
                DevOutput.line("§7${catalog.kind}: $outcome")
            }
        }
    }

    fun prices() {
        DevOutput.begin("/soul dev prices")
        val status = PriceCache.status()
        DevOutput.line("§7One backend catalog now, not two upstream polls: GET /prices serves the bazaar and the")
        DevOutput.line("§7lowest-BIN mirror merged: ~570 KB identity, ~72 KB gzipped, against 3.6 MB of upstream")
        DevOutput.line("§7feeds every two minutes per client.")
        DevOutput.line("§7Capability still comes from /items and is joined in client-side — see the note below.")
        DevOutput.line("")
        DevOutput.line("§bCatalog §8(GET ${status.catalog.endpoint}, kind '${status.catalog.kind}')")
        DevOutput.line("  entries=§f${status.entries}§r source=§f${status.catalog.source}")
        DevOutput.line("  backend rebuilt=§f${at(status.updatedAt)}")
        DevOutput.line("  lastRefresh=§f${ago(status.catalog.lastRefreshAt)}§r lastSuccess=§f${ago(status.catalog.lastSuccessAt)}")
        DevOutput.line("  staleAfter=§f${status.catalog.staleAfterMs / 1000}s§r lastError=§f${status.catalog.lastError ?: "-"}")
        DevOutput.line("")
        // The whole reason this command still exists alongside /soul dev catalogs: the payload
        // rebuilds on schedule even when one of the backend's two upstream polls has been failing for
        // hours, and the only symptom is that a few thousand items silently have no quote.
        DevOutput.line("§bUpstream halves §8(the backend's own polls — either can be stale on a healthy catalog)")
        DevOutput.line("  bazaar: §f${status.bazaarQuotes}§r quotes, polled §f${at(status.bazaarUpdatedAt)}")
        DevOutput.line("  lowestBIN: §f${status.lowestBinQuotes}§r listed, polled §f${at(status.lowestBinUpdatedAt)}")
        DevOutput.line("")
        DevOutput.line("§bProbes §8(where each item can be sold, and what that venue pays)")
        DevOutput.line("§8'not sellable' is a fact about the item; '—' means the venue applies but has no quote.")
        for (id in PROBES) {
            val p = PriceCache.of(id)
            val venues = p.venues()
            DevOutput.line("  §f$id §8— sellable at: ${if (venues.isEmpty()) "§cnowhere" else venues.joinToString(", ") { it.displayName }}")
            for (source in PriceSource.entries) {
                val label = source.displayName
                val text = if (!p.sellableAt(source)) {
                    "§8not sellable"
                } else {
                    p.at(source)?.let { "§f$it" } ?: "§e— §8(no quote yet)"
                }
                DevOutput.line("    $label: $text")
            }
            DevOutput.line("    §8valued at: §f${p.best(PriceSource.BAZAAR_INSTANT_BUY) ?: "—"}")
        }
        DevOutput.line("")
        DevOutput.line("§8Capability comes from three places, and /prices carries only the first: bazaar")
        DevOutput.line("§8membership (is it a product), then the item catalog's auctionable flag (Hypixel's")
        DevOutput.line("§8can_auction / can_trade / soulbound) and its npcSellPrice — both of which describe the")
        DevOutput.line("§8item's nature, not the market, so they stay on /items at its 1 h TTL. For pets that last")
        DevOutput.line("§8one is George the Pet Collector, curated on the backend because no API carries it — and")
        DevOutput.line("§8he pays at most 50M coins per day.")
        DevOutput.line("§8A null lowestBin means nothing is listed right now, which is NOT 'cannot be auctioned'.")
        if (status.entries == 0) {
            DevOutput.line("§cThe catalog is empty, so every venue above reads as absent. Check /soul dev backend:")
            DevOutput.line("§cbefore the first authenticated session this is the normal state, not a bug.")
        } else if (!PriceCache.bazaarLoaded() || !PriceCache.lowestBinLoaded()) {
            DevOutput.line("§cAn upstream half has never been polled, so 'not sellable' above may just mean 'not")
            DevOutput.line("§cfetched' — and that failure is the backend's, not this client's.")
        }
    }

    fun refreshPrices() {
        DevOutput.begin("/soul dev prices refresh")
        DevOutput.line("§7Refetching GET /prices, bypassing the HTTP cache as every catalog refresh does …")
        SoulScope.io.launch { DevOutput.line("§7${PriceCache.refreshNow()}") }
    }

    /** `/soul dev market`: the `/market/values` map behind price tooltips, plus the held item's key. */
    fun market() {
        DevOutput.begin("/soul dev market")
        val s = MarketValues.status()
        DevOutput.line("§7GET ${MarketValues.ENDPOINT}: full at start, deltas (?since=<generation>) on realtime")
        DevOutput.line("§7'market' invalidates, a backstop after ${s.staleAfterMs / 1000}s without a fetch.")
        DevOutput.line("")
        DevOutput.line("§bMap")
        DevOutput.line("  keys=§f${s.keys}§r source=§f${s.source}§r generation=§f${s.generation}§r disk=§f${s.diskGeneration}")
        DevOutput.line("  lastAttempt=§f${atMs(s.lastAttemptMs)}§r lastSuccess=§f${atMs(s.lastSuccessMs)}")
        DevOutput.line("  lastFull=§f${atMs(s.lastFullMs)}§r lastDelta=§f${atMs(s.lastDeltaMs)}§r (${s.lastDeltaKeys} keys)")
        DevOutput.line("  deltas=§f${s.deltas}§r market events=§f${s.events}§r lastEvent=§f${atMs(s.lastEventMs)}")
        DevOutput.line("  persisted=§f${atMs(s.lastPersistMs)}§r lastError=§f${s.lastError ?: "-"}")
        DevOutput.line("  realtime connected=§f${RealtimeClient.status().connected}")
        DevOutput.line("")
        DevOutput.line("§bProbes")
        for (key in MARKET_PROBES) DevOutput.line("  §f$key§r ${describe(MarketValues.get(key))}")
        val held = Minecraft.getInstance().player?.mainHandItem
        if (held != null && !held.isEmpty) {
            val key = MarketItemKey.of(held)
            DevOutput.line("")
            DevOutput.line("§bHeld item§r key=§f${key ?: "§cnone"}")
            if (key != null) DevOutput.line("  ${describe(MarketValues.get(key))}")
        }
    }

    fun refreshMarket() {
        DevOutput.begin("/soul dev market refresh")
        DevOutput.line("§7Full refetch of ${MarketValues.ENDPOINT} …")
        SoulScope.io.launch { DevOutput.line("§7${MarketValues.refreshNow(full = true)}") }
    }

    private fun describe(v: MarketValue?): String =
        if (v == null) {
            "§8no entry"
        } else {
            "§8fair=§f${plain(v.fair)}§8 buy=§f${plain(v.buy)}§8 sell=§f${plain(v.sell)}§8 lbin=§f${plain(v.lbin)}§8 " +
                "trend24h=§f${v.trend24h}§8 liquidity=§f${plain(v.liquidity)}§8 tts50=§f${v.tts50}"
        }

    /** `999000000`, not `9.99E8`. */
    private fun plain(value: Double?): String = value?.let { java.math.BigDecimal.valueOf(it).toPlainString() } ?: "null"

    private val MARKET_PROBES = listOf("HYPERION", "ENCHANTED_DIAMOND", "ENDER_DRAGON;4+100", "ENCHANTMENT_ULTIMATE_WISE_5")

    private fun atMs(epochMs: Long): String =
        if (epochMs <= 0L) "§cnever" else "${(System.currentTimeMillis() - epochMs) / 1000}s ago"

    /**
     * One item per feed shape: a weapon that only the auction house carries, an eye and a shard that
     * only the bazaar does, and a pet whose id form (`;4`) is the one most likely to be typo'd.
     */
    /** Every catalog's on-disk cache, in registration order, plus the market map. */
    private val CACHE_FILES = listOf(
        "sea_creature_catalog.json",
        "drop_catalog.json",
        "item_catalog.json",
        "price_catalog.json",
        MarketValues.CACHE_FILE,
    )

    private val PROBES = listOf("HYPERION", "SUMMONING_EYE", "SHARD_DRACONIC", "ENDER_DRAGON;4")

    private fun ago(at: Instant?): String =
        if (at == null) "§cnever" else "§f${Duration.between(at, Instant.now()).seconds}s ago"

    /** An epoch-ms timestamp as an age, or a loud "never" — a null here is the finding, not a gap. */
    private fun at(epochMs: Long?): String =
        if (epochMs == null) {
            "§cnever"
        } else {
            "${(System.currentTimeMillis() - epochMs) / 1000}s ago §8(${Instant.ofEpochMilli(epochMs)})"
        }
}
