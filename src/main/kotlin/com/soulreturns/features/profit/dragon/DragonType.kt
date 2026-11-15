package com.soulreturns.features.profit.dragon

/**
 * The seven End-island dragons, in Hypixel's own rotation order so the filter dropdown reads the way
 * the game does.
 *
 * [color] is the in-game name tint, which is what makes a row recognisable at a glance in the profit
 * panel — Strong and Superior are gold, the rest follow their tier.
 */
enum class DragonType(
    val displayName: String,
    val color: Int,
) {
    PROTECTOR("Protector", 0xFFFF55FF.toInt()), // §d light purple
    OLD("Old", 0xFFFFFFFF.toInt()), // §f white
    WISE("Wise", 0xFF55FFFF.toInt()), // §b aqua
    UNSTABLE("Unstable", 0xFFAA00AA.toInt()), // §5 dark purple
    YOUNG("Young", 0xFF55FF55.toInt()), // §a green
    STRONG("Strong", 0xFFFF5555.toInt()), // §c red
    SUPERIOR("Superior", 0xFFFFAA00.toInt()), // §6 gold
    ;

    /**
     * This dragon's node in the backend drop graph — `dragon.protector`, `dragon.old`, and so on.
     *
     * **This string is the contract with the backend seed.** `app:drops:seed` writes exactly these
     * ids, and [com.soulreturns.data.drops.DropResolver] joins on them to turn a loot stand's text
     * into an item id. Renaming either side alone silently resolves nothing — the lookup returns null,
     * the scanner logs "unrecognised loot stand", and the tracker counts kills with no loot.
     */
    val sourceId: String get() = "dragon.${name.lowercase()}"

    companion object {
        fun byName(name: String): DragonType? = entries.firstOrNull { it.name == name }

        fun byDisplayName(displayName: String): DragonType? = entries.firstOrNull { it.displayName == displayName }
    }
}
