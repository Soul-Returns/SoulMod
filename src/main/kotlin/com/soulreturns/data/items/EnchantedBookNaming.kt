package com.soulreturns.data.items

import com.soulreturns.data.skyblock.SkyblockRarity
import java.util.Locale

/**
 * Turn a bazaar-format enchantment id back into a readable name and a heuristic rarity.
 *
 * Hypixel's item catalog ships **one** `ENCHANTED_BOOK` row regardless of enchantment, while the
 * bazaar splits every (enchant, level) pair into its own product — `ENCHANTMENT_ULTIMATE_CHIMERA_1`,
 * `ENCHANTMENT_SHARPNESS_7`. So an id the price feed prices perfectly well has no catalog row to take
 * a name from, and would otherwise render as its raw id.
 *
 * Used only as a fallback inside [com.soulreturns.data.drops.DropResolver], after the per-drop
 * override and the item catalog have both come up empty. That is the whole scope: a small heuristic
 * that closes a known gap in Hypixel's own data, not a subsystem.
 */
object EnchantedBookNaming {
    private const val PREFIX = "ENCHANTMENT_"

    /** Roman numerals 1-10 — covers every SkyBlock enchantment cap in practice. */
    private val ROMAN: List<String> = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

    /** True if [id] looks like a bazaar enchantment product id (`ENCHANTMENT_<NAME>_<LEVEL>`). */
    fun isEnchantmentId(id: String): Boolean = id.startsWith(PREFIX) && parse(id) != null

    /**
     * `ENCHANTMENT_ULTIMATE_CHIMERA_1` → `"Ultimate Chimera I"`. Null when [id] does not fit the
     * shape, so the caller falls through to the next step of its resolution order.
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
     * Heuristic tint. SkyBlock renders Ultimate enchants as MYTHIC in game and everything else in the
     * book wrapper's own colour. Imperfect — a few special enchants have their own — but it is a
     * default an admin can override per drop, not a claim.
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
