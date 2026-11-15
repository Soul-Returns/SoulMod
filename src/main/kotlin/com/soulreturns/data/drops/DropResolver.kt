package com.soulreturns.data.drops

import com.soulreturns.data.items.AttributeShardNaming
import com.soulreturns.data.items.EnchantedBookNaming
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.skyblock.SkyblockRarity

/**
 * The catalog join: turn a `(sourceId, itemId)` reference into the name, rarity and price-lookup id a
 * consumer actually wants, by combining the drop graph ([DropCatalogClient]) with the item catalog
 * ([ItemCatalogClient]).
 *
 * ### Resolution order, and why the first step exists
 * 1. The per-drop `displayNameOverride` on the matching [DropEntry]. Hypixel's loot-stand text can
 *    diverge from the catalog per source — `"Aspect of the Dragons"` on a dragon stand against the
 *    catalog's singular `"Aspect of the Dragon"` — and only the source knows which.
 * 2. The item catalog's canonical `displayName`.
 * 3. A synthesised name for the two id families Hypixel's catalog does not enumerate
 *    (`ENCHANTMENT_*`, `SHARD_*`).
 * 4. The raw item id, so a row is never lost.
 *
 * Rarity mirrors the same order, ending at [SkyblockRarity.COMMON].
 *
 * ### The alias table is not consulted, deliberately
 * The backend used to seed a `/aliases` table; every one of its twelve rows was a dragon loot-stand
 * mapping, and every one of those mappings already existed as a `displayNameOverride` — which is what
 * this code reads. The table was deleted rather than kept as a second source of truth for the same
 * fact. A fuzzy-matching variant of [findItemIdByDisplayName] would be the place to reintroduce one;
 * exact matching does not need it.
 */
object DropResolver {
    /** Effective display name for the pair. See the class doc for the order. */
    fun displayName(
        sourceId: String,
        itemId: String,
    ): String {
        DropCatalogClient.drop(sourceId, itemId)?.displayNameOverride?.takeIf { it.isNotEmpty() }?.let { return it }
        ItemCatalogClient.byId(itemId)?.displayName?.takeIf { it.isNotEmpty() }?.let { return it }
        EnchantedBookNaming.displayName(itemId)?.let { return it }
        AttributeShardNaming.displayName(itemId)?.let { return it }
        return itemId
    }

    /** Effective rarity, resolved in the same order, falling back to [SkyblockRarity.COMMON]. */
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
     * The id to ask [com.soulreturns.data.prices.PriceCache] for. Honours
     * [com.soulreturns.data.items.ItemCatalogEntry.bazaarId] when the backend has set one, so an item
     * whose bazaar product id diverges from its catalog id prices without a mod release. It is null
     * on every row today; that is the point — the hook costs one map read and removes a whole class
     * of future emergency.
     */
    fun priceLookupId(itemId: String): String =
        ItemCatalogClient.byId(itemId)?.bazaarId?.takeIf { it.isNotEmpty() } ?: itemId

    /**
     * Reverse lookup: the item id under [sourceId] whose effective display name is exactly
     * [displayName]. This is how a loot stand's `customName` becomes an item id.
     *
     * **Case-sensitive, and scoped to one source.** Loot-stand text matches catalog text exactly
     * today, and scoping to the source means a display-name collision elsewhere in Hypixel's catalog
     * cannot reach this — the global [ItemCatalogClient.byDisplayName] index has several hundred of
     * them and is a diagnostic aid, not this.
     */
    fun findItemIdByDisplayName(
        sourceId: String,
        displayName: String,
    ): String? {
        for (drop in DropCatalogClient.dropsFrom(sourceId)) {
            val effective = drop.displayNameOverride?.takeIf { it.isNotEmpty() }
                ?: ItemCatalogClient.byId(drop.itemId)?.displayName?.takeIf { it.isNotEmpty() }
                ?: drop.itemId
            if (effective == displayName) return drop.itemId
        }
        return null
    }
}
