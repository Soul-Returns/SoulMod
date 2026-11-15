package com.soulreturns.data.items

import kotlinx.serialization.Serializable

/**
 * One row of `GET /items` — the backend's mirror of Hypixel's item catalog, plus the handful of
 * curated synthetic rows the drop graph needs that Hypixel does not ship (`COINS`, `SHARD_DRACONIC`,
 * `ENDER_DRAGON;3`, `ENDER_DRAGON;4`, `PEARLESCENT_DYE`).
 *
 * @property bazaarId overrides [id] for a bazaar price lookup **when set**. It is null on every row
 *   today and every dragon drop prices correctly without it; the hook exists because the day an item
 *   whose bazaar product id diverges from its catalog id is added, honouring a column beats shipping
 *   a mod release. See [com.soulreturns.data.drops.DropResolver.priceLookupId].
 * @property npcSellPrice backs [com.soulreturns.data.prices.PriceSource.NPC]. **Nullable, and it has
 *   to be declared that way**: the backend's column is nullable and it is null on well over half of
 *   Hypixel's catalog — most items simply have no vendor. A `Long = 0L` here does not survive that,
 *   because a kotlinx default only applies when the key is *absent*; an explicit `null` against a
 *   non-nullable property throws, and one such row fails the deserialisation of the entire response.
 *   That is exactly what happened: `/items` never loaded once, and the only symptom was NPC prices
 *   reading as zero. For **pets** this value is what George the Pet Collector pays — curated on the
 *   backend, because it exists in no API.
 * @property auctionable whether Hypixel permits the Auction House for this item. Derived backend-side
 *   from `can_auction` / `can_trade` / `soulbound`, which are negative-only flags — hence the `true`
 *   default, which is also the right answer for an id Hypixel's catalog does not carry at all (pets,
 *   rune tiers), since those are on the AH by observation.
 */
@Serializable
data class ItemCatalogEntry(
    val id: String = "",
    val bazaarId: String? = null,
    val displayName: String = "",
    val material: String? = null,
    val category: String? = null,
    val rarity: String? = null,
    val npcSellPrice: Long? = null,
    val auctionable: Boolean = true,
)

/** The whole `GET /items` body, and the exact shape of the on-disk cache. */
@Serializable
data class ItemCatalogSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val items: List<ItemCatalogEntry> = emptyList(),
)
