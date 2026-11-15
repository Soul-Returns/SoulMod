package com.soulreturns.data.drops

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One source node in the backend drop graph, matching `GET /drops`'s `sources[]` shape 1:1. Sources
 * form a tree via [parentId] — the `dragon` root has none, `dragon.protector` hangs off it.
 *
 * ### `id` is the contract with the backend seed
 * The mod derives a dragon's source id as `"dragon.${DragonType.name.lowercase()}"` and the backend's
 * `app:drops:seed` writes exactly those strings. Neither side may rename independently.
 *
 * ### `metadata` is an open bag, and it arrives two ways
 * `dragon.*` nodes carry `{"dragon_type": "PROTECTOR"}`. The backend coerces an empty map to `{}`
 * because PHP encodes an empty array as `[]`, which a map-typed deserialiser rejects — so this is
 * typed as [JsonElement] and read through [metadataString], which tolerates both shapes rather than
 * failing the whole catalog over a punctuation difference in one row.
 */
@Serializable
data class DropSourceEntry(
    val id: String = "",
    val parentId: String? = null,
    val kind: String = "",
    val displayName: String = "",
    val metadata: JsonElement? = null,
) {
    /** True when this is a top-level source (no parent). */
    val isTopLevel: Boolean get() = parentId == null

    /** A string-valued metadata key, or null when absent — or when `metadata` arrived as `[]`. */
    fun metadataString(key: String): String? {
        val obj = metadata as? JsonObject ?: return null
        return (obj[key] as? JsonPrimitive)?.contentOrNull
    }
}

/**
 * One drop row — a many-to-many link between a [DropSourceEntry] and an item id, matching
 * `GET /drops`'s `drops[]` shape.
 *
 * [displayNameOverride] is per-source and wins over the item catalog's own name. That is what makes a
 * loot-stand's text resolvable: Hypixel writes `"Aspect of the Dragons"` (plural) on the stand while
 * the catalog id is `ASPECT_OF_THE_DRAGON` (singular), and `"Travel Scroll to Dragon's Nest"` for
 * `DRAGON_NEST_TRAVEL_SCROLL`. It is the first step of [DropResolver]'s resolution order, and the
 * reason this mod needs no alias table.
 *
 * [rarityOverride] follows the same per-source-wins rule — null means use the item catalog's rarity.
 */
@Serializable
data class DropEntry(
    val sourceId: String = "",
    val itemId: String = "",
    val displayNameOverride: String? = null,
    val rarityOverride: String? = null,
)

/**
 * The whole `GET /drops` body, and the exact shape of the on-disk cache.
 *
 * [updatedAt] is the backend's `MAX(updated_at)` across its source and drop rows at snapshot time
 * (epoch ms), or null when the catalog has never been fetched.
 */
@Serializable
data class DropCatalogSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val sources: List<DropSourceEntry> = emptyList(),
    val drops: List<DropEntry> = emptyList(),
)
