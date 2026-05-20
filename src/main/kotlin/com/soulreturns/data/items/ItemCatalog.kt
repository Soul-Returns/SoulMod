package com.soulreturns.data.items

/**
 * One row in the SkyBlock item catalog, matching the backend's `GET /items` response
 * shape 1:1. Mirror of the backend `Item` entity's external surface.
 *
 * **Nullability mirrors the source.** Hypixel's items endpoint leaves most non-essential
 * fields off when it doesn't have data; the backend stores them as nullable columns and
 * surfaces them as JSON `null` (or omits the key — Gson treats both the same way for
 * nullable Kotlin fields). Only [id] and [displayName] are guaranteed non-null.
 *
 * **Id format is open-ended.** Hypixel ships ids that start with digits
 * (`6_ANNIVERSARY_BARN_SKIN`), carry tier suffixes (`ENDER_DRAGON;4`), and use uncommon
 * punctuation. Don't validate beyond "non-empty string" — the backend already loosened
 * its accepted pattern after the live ingest caught the leading-digit case.
 *
 * **[bazaarId] override.** Null for most items (use [id] when querying the bazaar). Set
 * when Hypixel's bazaar `productId` differs from the NEU-format [id] — the canonical
 * example is attribute shards (`ATTRIBUTE_SHARD_DRAGON_ESSENCE;1` vs `SHARD_DRACONIC`).
 * Currently always null in the backend response (a future bazaar-verify pass populates
 * it); the field exists so the data class doesn't churn when that lands.
 */
data class ItemCatalogEntry(
    val id: String,
    val bazaarId: String? = null,
    val displayName: String,
    val material: String? = null,
    val category: String? = null,
    val rarity: String? = null,
    val npcSellPrice: Long? = null,
) {
    /** [bazaarId] when set, else [id]. Use this when calling `PriceCache.price(...)`. */
    val priceLookupId: String get() = bazaarId ?: id
}

/**
 * Immutable snapshot of the entire catalog at a point in time. Replaced atomically inside
 * [ItemCatalogClient] on every successful fetch; readers see a consistent view because
 * the volatile reference is swapped, never mutated in place.
 *
 * [updatedAt] is the backend's `ItemCatalogState.last_applied_at` at the time the
 * snapshot was produced (epoch ms). Null when the catalog has never been fetched / no
 * cache existed at startup.
 */
data class ItemCatalogSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val items: List<ItemCatalogEntry> = emptyList(),
) {
    companion object {
        val EMPTY: ItemCatalogSnapshot = ItemCatalogSnapshot()
    }
}
