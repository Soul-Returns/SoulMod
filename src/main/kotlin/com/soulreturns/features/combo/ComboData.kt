package com.soulreturns.features.combo

/**
 * Colors and the fallback combo durations of the SkyBlock Kill Combo (Grandma Wolf pet perk).
 *
 * The bonuses are **not** stored here: since SkyBlock 0.27.3 their kind and size depend on the pet
 * (Combat Wisdom or coins per kill, scaled by level and rarity), so [ComboRun] takes them from each
 * milestone's chat line. The duration table below follows the SBCombo tier table (credited in
 * `UsedSoftware`); the pet's own values are learned from its lore ([ComboDurations]).
 *
 * The count color is the tier color of the module: 5/10 green, 15 dark-blue, 20/25 dark-purple, 30 gold.
 */
object ComboData {
    /** ARGB colors matching the module's Minecraft color codes. */
    const val GREEN = 0xFF55FF55.toInt() // §a
    const val DARK_BLUE = 0xFF3B82F6.toInt() // §1 (brightened from pure 0000AA for legibility)
    const val DARK_PURPLE = 0xFFC030D0.toInt() // §5 (brightened to match the mockup's magenta)
    const val GOLD = 0xFFFFAA00.toInt() // §6

    // Bonus colors as Hypixel prints them: §3 Combat Wisdom, §6 coins, §7 label, §8 plus.
    const val COMBAT_WISDOM = 0xFF33CCCC.toInt() // §3 dark-aqua (brightened)
    const val COINS = 0xFFFFAA00.toInt() // §6 gold
    const val LABEL = 0xFFAAAAAA.toInt() // §7 gray
    const val PLUS = 0xFF7A7A7A.toInt() // §8 dark-gray (brightened)
    const val TIMER = 0xFFFF5555.toInt() // §c red, combo-expiration countdown

    /** Highest milestone the pet's table lists; a longer combo keeps this tier's color and duration. */
    const val LAST_MILESTONE = 30

    /** Count color of [combo]: its milestone (a multiple of 5) clamped to 5..30. */
    fun colorFor(combo: Int): Int = colors.getValue(milestoneOf(combo))

    /** The milestone [combo] sits on or just past, clamped to 5..[LAST_MILESTONE]. */
    fun milestoneOf(combo: Int): Int = (combo / 5 * 5).coerceIn(5, LAST_MILESTONE)

    /**
     * Seconds the combo lasts after a kill at each milestone for a **level-100** pet, used until the
     * pet's own lore has been seen ([ComboDurations]). Level 76 EPIC measured 9.5 / 7.5 / 5.5 / 4.5 /
     * 3.7 / 2.7 on 2026-11-17, so the table is at most half a second long for mid-level pets.
     */
    val defaultDurations: Map<Int, Float> = mapOf(5 to 10f, 10 to 8f, 15 to 6f, 20 to 5f, 25 to 4f, 30 to 3f)

    private val colors: Map<Int, Int> =
        mapOf(5 to GREEN, 10 to GREEN, 15 to DARK_BLUE, 20 to DARK_PURPLE, 25 to DARK_PURPLE, 30 to GOLD)
}
