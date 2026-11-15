package com.soulreturns.features.mining.nucleus

import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceMode
import java.util.Locale

/**
 * Loot and kit names (as Hypixel prints them in the nucleus chat) to SkyBlock ids, for prices.
 *
 * The backend drop graph has no nucleus source, so names go through the `/items` display-name index.
 * Every name seen in 50 captured bundles resolves to exactly one id there (checked against the cached
 * catalog, 2026-11-07); books and essences have no catalog row and are synthesized from their shape.
 */
object NucleusItems {
    private val BOOK = Regex("""^Enchanted Book \((.+) ([IVXLC]+)\)$""")
    private val ESSENCE = Regex("""^(\w+) Essence$""")
    private val ROMAN = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100)

    /** Pure: the id a book or essence name stands for, or null for anything else. */
    fun syntheticId(name: String): String? {
        BOOK.matchEntire(name)?.let { m ->
            val level = roman(m.groupValues[2]) ?: return null
            return "ENCHANTMENT_" + m.groupValues[1].uppercase(Locale.ROOT).replace(' ', '_').replace("'", "") + "_" + level
        }
        ESSENCE.matchEntire(name)?.let { return "ESSENCE_" + it.groupValues[1].uppercase(Locale.ROOT) }
        return null
    }

    private val PART_IDS: Map<String, String> =
        NucleusLines.ROBOT_PARTS.associateWith { it.uppercase(Locale.ROOT).replace(' ', '_') }

    /** Kit item name to id: the egg kinds and robot parts have colored or missing catalog names, so they are explicit. */
    fun kitId(name: String): String? =
        GoblinEggs.KINDS.entries.firstOrNull { it.value == name }?.key ?: PART_IDS[name]

    fun idFor(name: String): String? =
        kitId(name) ?: syntheticId(name) ?: ItemCatalogClient.byDisplayName(name)?.id?.takeIf { it.isNotEmpty() }

    fun isPowder(name: String): Boolean = name.endsWith(" Powder")

    /** Sell value of one unit; 0 = no quote (or powder). */
    fun unitValue(name: String, mode: PriceMode): Long {
        if (isPowder(name)) return 0L
        val id = idFor(name) ?: return 0L
        return PriceCache.sellValue(id, mode, useNpcFloor = false)
    }

    /** What one unit of a kit item costs to buy; 0 = no quote. */
    fun unitCost(name: String, mode: PriceMode): Long {
        val id = idFor(name) ?: return 0L
        return PriceCache.buyCost(id, mode)
    }

    private fun roman(s: String): Int? {
        var total = 0
        var prev = 0
        for (c in s.reversed()) {
            val v = ROMAN[c] ?: return null
            total += if (v < prev) -v else v
            prev = maxOf(prev, v)
        }
        return total.takeIf { it > 0 }
    }
}
