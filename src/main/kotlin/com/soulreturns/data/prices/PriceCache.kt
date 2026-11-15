package com.soulreturns.data.prices

import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.platform.catalog.CatalogStatus

/**
 * What every SkyBlock item is worth, and **where it can actually be sold** — the join of the market
 * ([PriceCatalogClient]) with the item's nature ([ItemCatalogClient]).
 *
 * ### The market comes from our backend now, and the old reasoning for not doing that was wrong
 * This used to poll Hypixel's bazaar and Elite's lowest-BIN mirror directly, and said so in a KDoc
 * arguing that proxying them "would add a hop of staleness to a HUD that redraws every frame".
 * That argument does not survive contact with either half of itself.
 *
 * The cost was real and large: 3.5 MB plus 124 KB every two minutes, unconditionally — title screen
 * included — is ~106 MB/hour and **~2.5 GB per client per day**, paid once per user. `GET /prices`
 * serves the same market merged, measured at ~570 KB identity and **~72 KB gzipped**: about **50×
 * less** on the wire, and one upstream poll shared by everyone rather than one per client.
 *
 * The staleness objection was a category error. Nothing in the render path has ever waited on a
 * fetch — [price] reads an in-memory map, and did before. A proxy hop cannot affect render latency;
 * it can only change how fresh that map is. Against a two-minute cadence, a backend that repolls on
 * the same schedule and publishes a `sync/catalogs` invalidate the moment it does leaves the client
 * no further behind than it already was, and usually less.
 *
 * ### Lookup is still a plain synchronous map read, from any thread
 * [price] is called from the profit panel's layout, which runs twice per frame, and from the chat
 * announcer. It must never await and never lock. [PriceCatalogClient] rebuilds its id index on
 * snapshot swap and publishes it with one volatile assignment, so every entry point below is a map
 * probe and nothing more.
 *
 * ### There is no `onSkyblock` gate, and no toggle
 * An earlier version gated the poll on being in SkyBlock, and every dev test then opened with zeros
 * because the first fetch had not fired. The catalog layer keeps that property — it loads from disk
 * at init and refreshes unconditionally — and, like every catalog, has no config switch and no
 * bundled fallback snapshot.
 *
 * ### Which entry point to use
 * **A tracker wants [sellValue] and [buyCost]** — they take the [PriceMode] the player picked and
 * resolve the buy/sell inversion for you. Everything below them is the machinery they are built from:
 * [of] is the truth (what each venue pays and **whether it applies at all**), [value] is the
 * venue-agnostic valuation with a fallback, and [price] is one specific venue with none, answering 0
 * when that venue does not apply.
 *
 * Note that **the venue is not a user setting** — an item is a bazaar item or an auction-house item
 * and never both, so there is nothing to choose. See [PriceMode] for the measured partition.
 *
 * 0 still never means "worthless": before the first refresh, or for an id in neither feed, a caller
 * renders `—`. Printing "0 coins" for a Hyperion because a fetch had not landed is a confidently
 * wrong number, which is worse than an obviously absent one.
 */
object PriceCache {
    /**
     * Per-product bazaar quote, in the player-facing sense of both words.
     *
     * `instantBuy` is the ask side (what the player pays to insta-buy, Hypixel's
     * `quick_status.buyPrice`) and `instantSell` the bid side (what they receive insta-selling,
     * `sellPrice`). The names line up with [PriceSource] directly; there is no swap anywhere in this
     * mod, and adding one is the classic way to make every profit number wrong by a spread.
     */
    data class BazaarPrices(val instantBuy: Long, val instantSell: Long)

    /**
     * Everything known about [itemId]: what each venue pays, and **which venues apply at all**.
     *
     * Prefer this over [price] whenever the answer is shown to the player, because it distinguishes
     * "no NPC buys this" from "we have not fetched a price yet" — see [ItemPrice]. [value] remains
     * the right call for valuing a drop, where any venue's number is a reasonable proxy.
     *
     * ### The join is the whole of this method, and it spans two endpoints on purpose
     * The market half comes from `/prices` (2 min TTL) and the capability half from `/items` (1 h).
     * `auctionable` and `npcSellPrice` describe what an item *is* and change when Hypixel rebalances
     * or an admin curates; `instantBuy` / `lowestBin` describe what the market is doing right now.
     * Putting them on one endpoint would mean re-sending 6900 rows of unchanging item nature every
     * two minutes, or serving stale capability for an hour — so they stay apart and meet here.
     *
     * Two plain map probes, so it is safe from a render path.
     */
    fun of(itemId: String): ItemPrice {
        val entry = ItemCatalogClient.byId(itemId)
        val market = PriceCatalogClient.byId(itemId)
        return ItemPrice(
            itemId = itemId,
            bazaar = market?.bazaar,
            lowestBin = market?.lowestBin,
            // An id the item catalog has never heard of — a pet, a rune tier, a damage-suffixed
            // variant — is auctionable by observation: those are exactly the things traded on the AH.
            // 2253 of the price catalog's ids are in that bucket, so this is the common case, not an
            // edge one.
            auctionable = entry?.auctionable ?: true,
            npcSell = entry?.npcSellPrice,
        )
    }

    /**
     * Whether the backend's **bazaar** half has ever landed, so a caller can tell an empty market
     * from an unfetched one.
     *
     * It reads the payload's own `bazaarUpdatedAt` rather than a local fetch timestamp, which makes
     * it strictly more informative than the flag it replaces: a catalog that loaded fine while the
     * backend's upstream bazaar poll has never succeeded now reports false, which is exactly the
     * condition the caveat exists for. Before, only *our* fetch failing could say so.
     */
    fun bazaarLoaded(): Boolean = PriceCatalogClient.snapshot.bazaarUpdatedAt != null

    /** As [bazaarLoaded], for the lowest-BIN half. */
    fun lowestBinLoaded(): Boolean = PriceCatalogClient.snapshot.lowestBinUpdatedAt != null

    /**
     * Force a refetch now, bypassing the HTTP cache as every catalog refresh does. For
     * `/soul dev prices refresh`.
     */
    suspend fun refreshNow(): String = PriceCatalogClient.refresh()

    /**
     * What **this venue** pays for one unit of [itemId], or **0** when the venue does not apply to it
     * or has no quote yet.
     *
     * ### It does not fall back across venues
     * It used to: a bazaar source fell through to lowest-BIN, and NPC fell through to the bazaar. That
     * made it answer a question nobody asked — `price("SUMMONING_EYE", NPC)` returned a *bazaar* price
     * even though no NPC buys a Summoning Eye, and the chain was internally inconsistent anyway (NPC
     * reached the bazaar but never the auction house, so a Hyperion read 0 while a Hyperion's BIN
     * price sat right there).
     *
     * Now that [ItemPrice] can express "this venue does not apply", the fallback has moved to where it
     * belongs. **Use [value] to price a drop** — that is the question every tracker is actually asking,
     * and it still never returns 0 while some venue has a quote. Use this one only when you mean a
     * specific venue, which in practice means diagnostics.
     */
    fun price(
        itemId: String,
        source: PriceSource = PriceSource.BAZAAR_INSTANT_BUY,
    ): Long = of(itemId).at(source) ?: 0L

    /**
     * What one unit of [itemId] is roughly **worth**, preferring [preferred] and falling back to any
     * venue that has a quote. 0 only when nothing anywhere does.
     *
     * This is the valuation question, and it is the one a profit tracker means: choosing a price
     * source should change which number is preferred, never silently zero half a loot table because
     * the chosen venue does not carry that item. An Ender Dragon pet has no bazaar listing and a
     * Summoning Eye has no NPC buyer, and both still need a value.
     */
    fun value(
        itemId: String,
        preferred: PriceSource = PriceSource.BAZAAR_INSTANT_BUY,
    ): Long = of(itemId).best(preferred) ?: 0L

    /**
     * [value] at [primarySource], raised to the NPC price when [useNpcFloor] and the NPC price is
     * higher.
     *
     * The global "use the NPC price if it is higher" toggle, implemented once so no tracker
     * reimplements the comparison. Passing `primarySource = NPC` short-circuits it — you are already
     * there. An item no NPC buys has no floor to apply, which falls out of [ItemPrice.npcSell] being
     * null rather than needing a branch.
     */
    fun priceWithNpcFloor(
        itemId: String,
        primarySource: PriceSource,
        useNpcFloor: Boolean,
    ): Long {
        val snapshot = of(itemId)
        val primary = snapshot.best(primarySource) ?: 0L
        if (!useNpcFloor || primarySource == PriceSource.NPC) return primary
        val npc = snapshot.npcSell ?: return primary
        return if (npc > primary) npc else primary
    }

    /**
     * What one unit of [itemId] is **worth to the player**, under the mode they picked. The valuation
     * path every profit tracker should take.
     *
     * ### NPC mode does not fall back to the market, and that is the point
     * [value] falls back across venues so that choosing a source never silently zeroes half a loot
     * table. That is right when the mode names a *market side*, because an auction-house item still
     * has a real BIN price whichever bazaar side was asked for. It is **wrong** for [PriceMode.NPC],
     * which is not a venue preference but a statement about the player: an Ironman cannot use the
     * auction house, so an AH-only drop is worth exactly nothing to them. Falling back would quote
     * them a price they can never realise, which is worse than a dash.
     *
     * So NPC mode reads [price] (this venue, no fallback) and every other mode reads
     * [priceWithNpcFloor]. 0 still means "no price", never "worthless" — callers render `—`.
     *
     * @param useNpcFloor the global "use the NPC price if it is higher" toggle. Ignored under
     *   [PriceMode.NPC], where it is already the primary.
     */
    fun sellValue(
        itemId: String,
        mode: PriceMode,
        useNpcFloor: Boolean,
    ): Long = if (mode == PriceMode.NPC) {
        price(itemId, PriceSource.NPC)
    } else {
        priceWithNpcFloor(itemId, mode.sourceFor(selling = true), useNpcFloor)
    }

    /**
     * What acquiring one unit of [itemId] **costs** the player, under the mode they picked — the buy
     * side of [sellValue].
     *
     * ### NPC mode still charges a market price here, deliberately
     * No NPC sells a Summoning Eye, so [PriceMode.NPC] has no buy side to read. Answering 0 would
     * overstate every summoned kill's profit by the entire cost of the eyes. An Ironman holding eyes
     * still paid something to get them, and "what did this cost me" is a *valuation* question rather
     * than a "which venue" one — which is exactly the distinction [value] exists for. So NPC mode
     * falls back to bazaar instant-buy, the price of acquiring one right now.
     *
     * **The NPC floor is never applied on this side.** That toggle values what you *sell*; an eye is
     * something you bought, so flooring it could only inflate the subtraction.
     */
    fun buyCost(
        itemId: String,
        mode: PriceMode,
    ): Long = if (mode == PriceMode.NPC) {
        value(itemId, PriceSource.BAZAAR_INSTANT_BUY)
    } else {
        value(itemId, mode.sourceFor(selling = false))
    }

    /**
     * Live counts and timestamps, for `/soul dev prices`.
     *
     * The two per-feed timestamps are the reason this is not just [PriceCatalogClient.status]. A
     * healthy-looking catalog whose bazaar half is hours old is a real and otherwise invisible state:
     * the payload keeps rebuilding on schedule, [catalog] reports a recent success, and the only
     * symptom is that a few thousand items quietly have no quote.
     *
     * @property bazaarQuotes rows carrying a bazaar quote; @property lowestBinQuotes rows with
     *   something currently listed on the auction house. Both are counted at index build.
     */
    data class Status(
        val catalog: CatalogStatus,
        val bazaarUpdatedAt: Long?,
        val lowestBinUpdatedAt: Long?,
        val bazaarQuotes: Int,
        val lowestBinQuotes: Int,
    ) {
        /** Total rows in the merged catalog — the union of both feeds. */
        val entries: Int get() = catalog.entries

        /** When the backend last rebuilt the payload, epoch ms. */
        val updatedAt: Long? get() = catalog.updatedAt
    }

    fun status(): Status {
        val snapshot = PriceCatalogClient.snapshot
        return Status(
            catalog = PriceCatalogClient.status(),
            bazaarUpdatedAt = snapshot.bazaarUpdatedAt,
            lowestBinUpdatedAt = snapshot.lowestBinUpdatedAt,
            bazaarQuotes = PriceCatalogClient.bazaarQuotes,
            lowestBinQuotes = PriceCatalogClient.lowestBinQuotes,
        )
    }
}
