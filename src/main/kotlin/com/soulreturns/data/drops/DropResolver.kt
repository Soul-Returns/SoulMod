package com.soulreturns.data.drops

import com.soulreturns.data.items.AttributeShardNaming
import com.soulreturns.data.items.EnchantedBookNaming
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.skyblock.SkyblockRarity

/**
 * Catalog-join helpers — turn a `(sourceId, itemId)` reference into the resolved display
 * name + rarity + price-lookup id by combining the backend drop catalog
 * ([DropCatalogClient]) with the item catalog ([ItemCatalogClient]).
 *
 * **Resolution order** for both display name and rarity:
 *  1. The per-source override on the matching [DropEntry] (when one exists). This wins
 *     because Hypixel's loot-stand text can diverge per source — `"Aspect of the Dragons"`
 *     on a dragon stand vs the catalog's singular `"Aspect of the Dragon"`.
 *  2. The canonical value from the matching [com.soulreturns.data.items.ItemCatalogEntry].
 *  3. A sensible fallback (the raw item id for displayName, [SkyblockRarity.COMMON] for rarity).
 *
 * **Reverse-lookup** — `findItemIdByDisplayName` walks every drop under a source, builds the
 * effective display name for each (override-or-catalog), and returns the matching item id.
 * Used by [com.soulreturns.features.profit.dragon.DragonLootScanner] to map a loot-stand
 * `customName` back to its catalog id.
 */
object DropResolver {
    /**
     * Effective display name for the `(sourceId, itemId)` pair. Resolution order:
     *  1. Per-drop `displayNameOverride` (admin curated)
     *  2. Item catalog `displayName`
     *  3. Synthesised name for bazaar enchantment ids (`ENCHANTMENT_<NAME>_<LEVEL>`) —
     *     Hypixel's item catalog ships only one `ENCHANTED_BOOK` row, so per-enchant
     *     books fall through to here.
     *  4. Raw [itemId] as a last-ditch.
     */
    fun displayName(
        sourceId: String,
        itemId: String,
    ): String {
        DropCatalogClient.drop(sourceId, itemId)?.displayNameOverride?.let { return it }
        ItemCatalogClient.byId(itemId)?.displayName?.takeIf { it.isNotEmpty() }?.let { return it }
        EnchantedBookNaming.displayName(itemId)?.let { return it }
        AttributeShardNaming.displayName(itemId)?.let { return it }
        return itemId
    }

    /**
     * Effective rarity. Resolution order mirrors [displayName]: per-drop override →
     * item catalog → enchantment heuristic → COMMON.
     */
    fun rarity(
        sourceId: String,
        itemId: String,
    ): SkyblockRarity {
        DropCatalogClient.drop(sourceId, itemId)?.rarityOverride?.let { override ->
            SkyblockRarity.forName(override)?.let { return it }
        }
        SkyblockRarity.forName(ItemCatalogClient.byId(itemId)?.rarity)?.let { return it }
        if (EnchantedBookNaming.isEnchantmentId(itemId)) return EnchantedBookNaming.rarity(itemId)
        if (AttributeShardNaming.isShardId(itemId)) return AttributeShardNaming.rarity(itemId)
        return SkyblockRarity.COMMON
    }

    /**
     * Item id to query [com.soulreturns.data.prices.PriceCache] with. Honors
     * [com.soulreturns.data.items.ItemCatalogEntry.bazaarId] (set when Hypixel's bazaar
     * product id differs from the NEU id — the attribute-shard case). Falls back to [itemId]
     * when no override exists.
     */
    fun priceLookupId(itemId: String): String = ItemCatalogClient.byId(itemId)?.bazaarId?.takeIf { it.isNotEmpty() } ?: itemId

    /**
     * Reverse-lookup: find the item id under [sourceId] whose effective display name matches
     * [displayName]. Returns null when nothing matches. Case-sensitive — loot-stand text
     * matches catalog text exactly today, and a future fuzzy variant is the alias table's
     * job, not this helper's.
     */
    fun findItemIdByDisplayName(
        sourceId: String,
        displayName: String,
    ): String? {
        for (drop in DropCatalogClient.dropsFrom(sourceId)) {
            val effective =
                drop.displayNameOverride
                    ?: ItemCatalogClient.byId(drop.itemId)?.displayName
                    ?: drop.itemId
            if (effective == displayName) return drop.itemId
        }
        return null
    }
}
