package com.soulreturns.data.drops

/**
 * One source node in the backend drop graph, matching `GET /drops`'s `sources[]` shape 1:1.
 * Sources form a tree via [parentId] — top-level nodes (`"dragon"`, `"mythological"`) have
 * `parentId = null`; leaf nodes (`"dragon.protector"`, `"mythological.minos_hunter"`) hang
 * off them.
 *
 * **Identifier convention.** `id` is snake-case dotted (`^[a-z][a-z0-9_.]{0,127}$`). The
 * mod's profit trackers reference leaves by this string — `DragonType.PROTECTOR` maps to
 * `"dragon.protector"` via the `kind = "dragon"` + `metadata.dragon_type = "PROTECTOR"`
 * link. Mythological mobs map by `metadata.mob_name`.
 *
 * **`metadata` carries tracker-specific context** — examples:
 *  - `dragon.*` nodes carry `{ "dragon_type": "PROTECTOR" }` so the dragon tracker can look
 *    up the right source for a `<TYPE> DRAGON DOWN!` chat banner without hardcoding the
 *    id-to-enum mapping.
 *  - `mythological.<mob>` nodes carry `{ "mob_name": "Minos Hunter", "rarity": "COMMON" }`.
 *  - `mythological.treasure_burrow` carries `{ "burrow_kind": "treasure" }`.
 *
 * Schema is open-ended on purpose — new trackers add new keys without a mod-side update.
 */
data class DropSourceEntry(
    val id: String,
    val parentId: String? = null,
    val kind: String,
    val displayName: String,
    val metadata: Map<String, Any?> = emptyMap(),
) {
    /** True when this is a top-level source (no parent). */
    val isTopLevel: Boolean get() = parentId == null
}

/**
 * One drop row — a many-to-many link between a [DropSourceEntry] and an item id. Matches
 * `GET /drops`'s `drops[]` shape.
 *
 * **Resolving the item.** The mod typically joins on [itemId] against
 * [com.soulreturns.data.items.ItemCatalogClient.byId] to get the canonical display name +
 * rarity + price-lookup id. When the loot-stand or chat representation diverges from the
 * catalog's display name, [displayNameOverride] holds the per-source override and should
 * win — that's how `"Aspect of the Dragons"` (loot stand plural) maps to
 * `ASPECT_OF_THE_DRAGON` (catalog singular id). When `displayNameOverride` is null, fall
 * back to `ItemCatalogClient.byId(itemId)?.displayName`.
 *
 * [rarityOverride] follows the same per-source-wins rule — null = use the catalog rarity.
 */
data class DropEntry(
    val sourceId: String,
    val itemId: String,
    val displayNameOverride: String? = null,
    val rarityOverride: String? = null,
)

/**
 * Immutable snapshot of the entire drop graph at a point in time. Replaced atomically by
 * [DropCatalogClient] on every successful fetch.
 *
 * [updatedAt] is the backend's `MAX(updated_at)` across `DropSource` + `Drop` rows at
 * snapshot time (epoch ms). Null when the catalog has never been fetched.
 */
data class DropCatalogSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val sources: List<DropSourceEntry> = emptyList(),
    val drops: List<DropEntry> = emptyList(),
) {
    companion object {
        val EMPTY: DropCatalogSnapshot = DropCatalogSnapshot()
    }
}
