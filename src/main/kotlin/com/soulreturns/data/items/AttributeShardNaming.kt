package com.soulreturns.data.items

import com.soulreturns.data.skyblock.SkyblockRarity
import java.util.Locale

/**
 * Translate bazaar-format attribute-shard item ids (`SHARD_<NAME>`) back to friendly
 * display strings. Sibling to [EnchantedBookNaming] — same architectural pattern, applied
 * to attribute shards instead of enchantments.
 *
 * Hypixel's `/v2/resources/skyblock/items` ships only a single combined `ATTRIBUTE_SHARD`
 * row (the inventory wrapper); the bazaar API splits each attribute into its own product
 * (`SHARD_NAGA`, `SHARD_CHARM`, `SHARD_SALT`, `SHARD_DRACONIC`, …). Chat-driven feeders
 * such as the Mythological shard-capture detector emit the bazaar-form id; this helper is
 * the matching read side that renders them human-readable in the HUD.
 *
 * Used as a fallback inside [com.soulreturns.data.drops.DropResolver] when the item
 * catalog has no row for the id.
 */
object AttributeShardNaming {
    private const val PREFIX = "SHARD_"

    fun isShardId(id: String): Boolean = id.startsWith(PREFIX) && id.length > PREFIX.length

    /**
     * `SHARD_NAGA` → `"Naga Shard"`, `SHARD_DRACONIC` → `"Draconic Shard"`,
     * `SHARD_BLAZING_FORTUNE` → `"Blazing Fortune Shard"`. Returns null when [id] doesn't
     * fit the shape (caller falls back to the raw id).
     */
    fun displayName(id: String): String? {
        if (!isShardId(id)) return null
        val name = id.substring(PREFIX.length)
        if (name.isEmpty()) return null
        val pretty =
            name.split('_').joinToString(" ") { word ->
                if (word.isEmpty()) {
                    word
                } else {
                    word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                }
            }
        return "$pretty Shard"
    }

    /**
     * Default rarity tint for an attribute shard. Hypixel doesn't expose a per-attribute
     * tier programmatically in the bazaar feed; in-game the shard color varies by
     * attribute. Returning COMMON keeps the row legible until a curator sets a per-drop
     * `rarityOverride` or the item catalog gets a real `rarity` column for the synthetic.
     */
    fun rarity(@Suppress("UNUSED_PARAMETER") id: String): SkyblockRarity = SkyblockRarity.COMMON
}
