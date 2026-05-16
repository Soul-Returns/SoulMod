package com.soulreturns.data.skyblock

/**
 * Canonical Hypixel SkyBlock rarity tier + its display color.
 *
 * Values mirror SkyHanni's `LorenzRarity` enum (LGPL-2.1; attribution surfaced in
 * `/soul config` → About → Used Software) so users see the same rarity tint they
 * already recognise from Hypixel tooltips. Used wherever the mod surfaces rarity to the
 * player — sea creature lists today, item lore/tooltip features tomorrow.
 *
 * The string [name] of each enum constant matches the raw Hypixel rarity key
 * (`COMMON`, `LEGENDARY`, `MYTHIC`, …) so [forName] is a direct lookup from JSON / NBT.
 */
enum class SkyblockRarity(val color: Int) {
    COMMON(0xFFFFFFFF.toInt()), // §f white
    UNCOMMON(0xFF55FF55.toInt()), // §a green
    RARE(0xFF5555FF.toInt()), // §9 blue
    EPIC(0xFFAA00AA.toInt()), // §5 dark purple
    LEGENDARY(0xFFFFAA00.toInt()), // §6 gold
    MYTHIC(0xFFFF55FF.toInt()), // §d light purple
    DIVINE(0xFF55FFFF.toInt()), // §b aqua
    SPECIAL(0xFFFF5555.toInt()), // §c red
    VERY_SPECIAL(0xFFFF5555.toInt()), // §c red
    ULTIMATE(0xFFAA0000.toInt()), // §4 dark red
    ;

    companion object {
        private const val DEFAULT_COLOR: Int = 0xFFFFFFFF.toInt()

        /** Look up by raw Hypixel rarity key (case-sensitive). `null` when unknown. */
        fun forName(name: String?): SkyblockRarity? {
            if (name.isNullOrEmpty()) return null
            return entries.firstOrNull { it.name == name }
        }

        /**
         * Color for a rarity key, falling back to [fallback] (white by default) when the
         * key is missing or doesn't match any known tier. Use this from any feature that
         * displays rarity text — keeps the lookup table in one place.
         */
        fun colorFor(
            name: String?,
            fallback: Int = DEFAULT_COLOR,
        ): Int = forName(name)?.color ?: fallback
    }
}
