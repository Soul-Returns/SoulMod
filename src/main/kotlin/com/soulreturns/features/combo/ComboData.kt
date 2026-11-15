package com.soulreturns.features.combo

/**
 * SkyBlock Kill-Combo bonus tiers (Grandma Wolf Pet perk), ported verbatim from the SBCombo
 * ChatTriggers module's `data` table. Keyed by combo milestone (a multiple of 5); each holds the
 * **cumulative** bonuses granted at that milestone. Above 30 the module (and this port) caps at the
 * 30-tier totals.
 *
 * `color` is the ARGB the combo count is drawn in (matches the module's tier color code):
 * 5/10 green, 15 dark-blue, 20/25 dark-purple, 30 gold.
 */
object ComboData {
    /** ARGB colors matching the module's Minecraft color codes. */
    const val GREEN = 0xFF55FF55.toInt() // §a
    const val DARK_BLUE = 0xFF3B82F6.toInt() // §1 (brightened from pure 0000AA for legibility)
    const val DARK_PURPLE = 0xFFC030D0.toInt() // §5 (brightened to match the mockup's magenta)
    const val GOLD = 0xFFFFAA00.toInt() // §6

    // Bonus-value colors (module: §b magic find, §6 coins, §3 combat xp, §7 label, §8 plus).
    const val MAGIC_FIND = 0xFF55FFFF.toInt() // §b aqua
    const val COINS = 0xFFFFAA00.toInt() // §6 gold
    const val COMBAT_XP = 0xFF33CCCC.toInt() // §3 dark-aqua (brightened)
    const val LABEL = 0xFFAAAAAA.toInt() // §7 gray
    const val PLUS = 0xFF7A7A7A.toInt() // §8 dark-gray (brightened)
    const val TIMER = 0xFFFF5555.toInt() // §c red — combo-expiration countdown

    class Tier(
        val color: Int,
        val magicFind: Int,
        val coinsPerKill: Int,
        val combatXp: Int,
        /**
         * Seconds the combo lasts after a kill at this tier, **assuming a level-100 pet**. Only the
         * duration scales with pet level (the bonuses don't), so lower pet levels shorten these — not
         * yet modeled (see the timer toggle note).
         */
        val durationSeconds: Float,
    )

    private val tiers: Map<Int, Tier> =
        mapOf(
            5 to Tier(GREEN, 3, 0, 0, 10f),
            10 to Tier(GREEN, 3, 10, 0, 8f),
            15 to Tier(DARK_BLUE, 6, 10, 0, 6f),
            20 to Tier(DARK_PURPLE, 6, 10, 15, 5f),
            25 to Tier(DARK_PURPLE, 9, 10, 15, 4f),
            30 to Tier(GOLD, 9, 20, 15, 3f),
        )

    /** Bonuses for [combo]: floor to the nearest milestone, clamped to 5..30 (the module's cap). */
    fun tierFor(combo: Int): Tier {
        val key = (combo / 5 * 5).coerceIn(5, 30)
        return tiers.getValue(key)
    }
}
