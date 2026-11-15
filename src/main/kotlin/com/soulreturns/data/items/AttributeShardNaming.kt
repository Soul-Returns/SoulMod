package com.soulreturns.data.items

import com.soulreturns.data.skyblock.SkyblockRarity
import java.util.Locale

/**
 * The attribute-shard sibling of [EnchantedBookNaming], and the same gap in Hypixel's data: the item
 * catalog ships one combined `ATTRIBUTE_SHARD` row (the inventory wrapper) while the bazaar splits
 * every attribute into its own product — `SHARD_NAGA`, `SHARD_DRACONIC`, `SHARD_BLAZING_FORTUNE`.
 *
 * Note that the one shard this mod actually cares about, `SHARD_DRACONIC`, does have a curated
 * backend row (`DropCatalogSeeder::SYNTHETIC_ITEMS`) **and** a per-drop `displayNameOverride`, so it
 * never reaches this heuristic. This is the fallback for every other shard id that might appear in a
 * drop graph later, which is exactly why it is thirty lines and not a table.
 */
object AttributeShardNaming {
    private const val PREFIX = "SHARD_"

    fun isShardId(id: String): Boolean = id.startsWith(PREFIX) && id.length > PREFIX.length

    /** `SHARD_NAGA` → `"Naga Shard"`, `SHARD_BLAZING_FORTUNE` → `"Blazing Fortune Shard"`. */
    fun displayName(id: String): String? {
        if (!isShardId(id)) return null
        val name = id.substring(PREFIX.length)
        if (name.isEmpty()) return null
        val pretty = name.split('_').joinToString(" ") { word ->
            if (word.isEmpty()) {
                word
            } else {
                word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }
        return "$pretty Shard"
    }

    /**
     * COMMON, always. In game a shard's colour varies by attribute and Hypixel exposes no per-shard
     * tier anywhere programmatic, so any other answer would be an invention. A curator sets a per-drop
     * `rarityOverride` when it matters.
     */
    @Suppress("UNUSED_PARAMETER")
    fun rarity(id: String): SkyblockRarity = SkyblockRarity.COMMON
}
