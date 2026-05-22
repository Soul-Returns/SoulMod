package com.soulreturns.data.items

import com.soulreturns.data.skyblock.SkyblockRarity
import java.util.Locale

/**
 * Translate synthetic bazaar-format enchantment item ids back to friendly display strings
 * + a heuristic rarity tier.
 *
 * Hypixel's `/v2/resources/skyblock/items` catalog ships a single `ENCHANTED_BOOK` row
 * regardless of enchantment; the bazaar API splits each (enchant, level) pair into its
 * own product (`ENCHANTMENT_ULTIMATE_CHIMERA_1`, `ENCHANTMENT_SHARPNESS_7`, …). The mod's
 * inventory watcher synthesises ids in the bazaar form via
 * [com.soulreturns.util.SkyblockItemUtils.getPriceLookupId]; this helper is the matching
 * read side that renders them human-readable in the HUD.
 *
 * Used as the final fallback inside [com.soulreturns.data.drops.DropResolver] when the
 * item catalog has no row for the id (the catalog gap that motivates this whole helper).
 */
object EnchantedBookNaming {
    private const val PREFIX = "ENCHANTMENT_"

    /** Roman numerals 1-10 — covers every SkyBlock enchantment cap in practice. */
    private val ROMAN: List<String> = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

    /** True if [id] looks like a bazaar enchantment product id (`ENCHANTMENT_<NAME>_<LEVEL>`). */
    fun isEnchantmentId(id: String): Boolean = id.startsWith(PREFIX) && parse(id) != null

    /**
     * `ENCHANTMENT_ULTIMATE_CHIMERA_1` → `"Ultimate Chimera I"`. Returns null when [id]
     * doesn't fit the shape (caller falls back to the raw id).
     */
    fun displayName(id: String): String? {
        val (name, level) = parse(id) ?: return null
        val pretty = name.split('_').joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        val levelText = ROMAN.getOrNull(level - 1) ?: level.toString()
        return "$pretty $levelText"
    }

    /**
     * Heuristic rarity tint for an enchantment id. SkyBlock's Ultimate enchants show as
     * MYTHIC (light purple) in-game; everything else is COMMON in the book wrapper. Not
     * perfect — some special enchants (Aiming, Smarty Pants) have their own colors — but
     * a sane default until the drop catalog admin sets per-drop `rarityOverride`s.
     */
    fun rarity(id: String): SkyblockRarity {
        val (name, _) = parse(id) ?: return SkyblockRarity.COMMON
        return if (name.startsWith("ULTIMATE_")) SkyblockRarity.MYTHIC else SkyblockRarity.COMMON
    }

    /** `(rawName, level)` from a valid id, or null. */
    private fun parse(id: String): Pair<String, Int>? {
        if (!id.startsWith(PREFIX)) return null
        val tail = id.substring(PREFIX.length)
        val lastUnderscore = tail.lastIndexOf('_')
        if (lastUnderscore <= 0 || lastUnderscore == tail.length - 1) return null
        val name = tail.substring(0, lastUnderscore)
        val level = tail.substring(lastUnderscore + 1).toIntOrNull() ?: return null
        if (level <= 0) return null
        return name to level
    }
}
