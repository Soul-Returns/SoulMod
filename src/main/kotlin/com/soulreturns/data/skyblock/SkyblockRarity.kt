package com.soulreturns.data.skyblock

/**
 * A Hypixel SkyBlock rarity tier and the colour the game tints it.
 *
 * The tints mirror SkyHanni's `LorenzRarity` (LGPL-2.1; attribution is surfaced under `/soul` →
 * About → Used Software) so a rarity reads the same in Soul as in the tooltip the player just looked
 * at. Constant names match the raw Hypixel key exactly (`COMMON`, `VERY_SPECIAL`, …), which is what
 * makes [forName] a direct lookup from JSON rather than a translation table.
 *
 * Used by the sea-creature catalog today; anything that surfaces rarity to the player should read it
 * from here rather than growing a second palette.
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

        /**
         * Look up by raw Hypixel rarity key. `null` when the key is missing or is one this build has
         * never heard of — **never** validate a rarity against this set, Hypixel adds tiers.
         */
        fun forName(name: String?): SkyblockRarity? {
            if (name.isNullOrEmpty()) return null
            return entries.firstOrNull { it.name == name }
        }

        /** Colour for a rarity key, falling back to white (or [fallback]) for anything unknown. */
        fun colorFor(
            name: String?,
            fallback: Int = DEFAULT_COLOR,
        ): Int = forName(name)?.color ?: fallback

        /**
         * The rarity a legacy color code tints a name with (`§6` → LEGENDARY), for names that carry
         * no rarity key, like the tab list's pet line. Red is ambiguous (SPECIAL / VERY_SPECIAL) and
         * reads as SPECIAL; anything else unknown is null.
         */
        fun forColorCode(code: Char): SkyblockRarity? =
            when (code.lowercaseChar()) {
                'f' -> COMMON
                'a' -> UNCOMMON
                '9' -> RARE
                '5' -> EPIC
                '6' -> LEGENDARY
                'd' -> MYTHIC
                'b' -> DIVINE
                'c' -> SPECIAL
                '4' -> ULTIMATE
                else -> null
            }
    }
}
