package com.soulreturns.data.prices

import kotlinx.serialization.Serializable

/**
 * One row of `GET /prices` — everything the market says about one id, from either feed or both.
 *
 * ### Every price here is nullable, and the two kinds of null mean different things
 * - **[instantBuy] / [instantSell] null — not a bazaar product at all.** They arrive null together or
 *   set together, because bazaar membership is a property of the id rather than of one side of the
 *   book. That is what [bazaar] collapses them into: a null quote, which [ItemPrice] reads as "it
 *   will never have one" rather than "we have not fetched it".
 * - **[lowestBin] null — nothing is currently listed.** *Not* "cannot be auctioned": whether Hypixel
 *   permits the Auction House for an item is [com.soulreturns.data.items.ItemCatalogEntry.auctionable],
 *   which lives on `/items` because it describes the item's nature and not the market. An item with
 *   an empty order book right now is still auctionable, and conflating the two would have the panel
 *   claim a Hyperion is untradeable every time the AH happened to be dry.
 *
 * Declaring them nullable is not a style choice. A kotlinx default only applies when the key is
 * *absent*; an explicit `null` against a non-nullable property throws and fails the deserialisation
 * of the **entire** response. `/items` learned that the expensive way with `npcSellPrice`, and this
 * payload is ~6900 rows of which the large majority carry a null on one side or the other.
 */
@Serializable
data class PriceEntry(
    val id: String = "",
    val instantBuy: Long? = null,
    val instantSell: Long? = null,
    val lowestBin: Long? = null,
) {
    /**
     * The bazaar quote as [PriceCache.BazaarPrices], or null when this id is not a bazaar product.
     *
     * Computed, so it is not a serialised property. The `?: 0L` tolerates a half-populated row the
     * contract says cannot happen — [ItemPrice.at] already treats a non-positive price as no quote,
     * so a stray null degrades to "no quote on that side" instead of dropping the whole row.
     */
    val bazaar: PriceCache.BazaarPrices?
        get() =
            if (instantBuy == null && instantSell == null) {
                null
            } else {
                PriceCache.BazaarPrices(instantBuy = instantBuy ?: 0L, instantSell = instantSell ?: 0L)
            }
}

/**
 * The whole `GET /prices` body, and the exact shape of the on-disk cache.
 *
 * ### Three timestamps, because two upstreams can go stale independently
 * [updatedAt] is when the backend last rebuilt this payload. [bazaarUpdatedAt] and
 * [lowestBinUpdatedAt] are when each **upstream** feed was last polled successfully — Hypixel's
 * bazaar and Elite's lowest-BIN mirror respectively. They are separate because one of them failing
 * is invisible from a merged timestamp: the payload keeps rebuilding on schedule while half of it
 * quietly ages, and every affected item just reads as having no quote. `/soul dev prices` prints all
 * three so that failure has somewhere to show up.
 *
 * ### The row set is the union of both feeds, and it is wider than `/items`
 * ~6900 ids, of which ~2253 are in neither the item catalog nor anything the mod curates — pets,
 * `+100` level variants, rune tiers, pet skins. So an id present here and absent from `/items` is
 * normal, and [com.soulreturns.data.prices.PriceCache.of] falls back to `auctionable = true` for
 * exactly that case.
 */
@Serializable
data class PriceCatalogSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    /** Epoch ms of the backend's last successful Hypixel bazaar poll, or null if it never had one. */
    val bazaarUpdatedAt: Long? = null,
    /** Epoch ms of the backend's last successful lowest-BIN poll, or null if it never had one. */
    val lowestBinUpdatedAt: Long? = null,
    val prices: List<PriceEntry> = emptyList(),
)
