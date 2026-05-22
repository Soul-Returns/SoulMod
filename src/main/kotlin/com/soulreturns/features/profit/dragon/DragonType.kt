package com.soulreturns.features.profit.dragon

/**
 * Hypixel SkyBlock End-Island dragons. Order matches Hypixel's dragon-rotation order in
 * The End so the bucket dropdown reads consistently with the in-game experience.
 *
 * [displayName] is what the player sees in the filter dropdown + chip labels. [color] is
 * the rarity-tier tint used to colorize the row label inside the per-dragon section
 * (matches Hypixel's in-game dragon-name color codes — Strong/Superior are LEGENDARY-gold,
 * the rest follow their tier).
 */
enum class DragonType(
    val displayName: String,
    val color: Int,
) {
    PROTECTOR("Protector", 0xFFFF55FF.toInt()), // §d light purple (MYTHIC tint)
    OLD("Old", 0xFFFFFFFF.toInt()), // §f white
    WISE("Wise", 0xFF55FFFF.toInt()), // §b aqua (DIVINE tint)
    UNSTABLE("Unstable", 0xFFAA00AA.toInt()), // §5 dark purple
    YOUNG("Young", 0xFF55FF55.toInt()), // §a green (UNCOMMON tint)
    STRONG("Strong", 0xFFFF5555.toInt()), // §c red
    SUPERIOR("Superior", 0xFFFFAA00.toInt()), // §6 gold (LEGENDARY)
    ;

    /**
     * Canonical drop-catalog source id for this dragon — `dragon.<lowercase enum name>`.
     * Matches the seed data shipped by the backend's `app:drops:seed` command (see
     * `prompts/profit-tracker/01-drop-catalog.md`). Used by every consumer that wants to
     * pull the dragon's drop list out of [com.soulreturns.data.drops.DropCatalogClient].
     */
    val sourceId: String get() = "dragon.${name.lowercase()}"

    companion object {
        fun byName(name: String): DragonType? = entries.firstOrNull { it.name == name }
    }
}
