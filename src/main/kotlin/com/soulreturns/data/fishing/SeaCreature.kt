package com.soulreturns.data.fishing

import com.soulreturns.data.skyblock.SkyblockRarity

/**
 * A single Hypixel SkyBlock sea creature. Built from [SeaCreatureCatalog]'s bundled
 * `assets/soul/sea_creatures.json` (snapshot of SkyHanni's `SeaCreatures.json` repo data).
 *
 * `variant` is the top-level grouping key from the JSON (`WATER`, `SHARK`, `LAVA_CRIMSON_ISLE`,
 * …) — useful for festival-only categorisation (sharks are `SHARK`) and future per-area stats.
 */
data class SeaCreature(
    val name: String,
    val variant: String,
    val rarity: String,
    val rare: Boolean,
    val fishingExperience: Int,
) {
    /** Display color for this creature's rarity — delegates to the shared [SkyblockRarity] table. */
    fun rarityColor(): Int = SkyblockRarity.colorFor(rarity)
}
