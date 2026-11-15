package com.soulreturns.data.fishing

import com.soulreturns.data.skyblock.SkyblockRarity

/**
 * One Hypixel SkyBlock sea creature, as the backend's `/sea-creatures` catalog describes it.
 *
 * This is the consumer-facing shape: the wire shape is [SeaCreatureEntry], and
 * [SeaCreatureCatalog] is what turns one into the other. The split matters because the wire type is
 * additive-only and carries fields the game does not (`id`, `sortOrder`, every alternate chat line),
 * while this one carries only what a tracker or a HUD actually reads.
 *
 * @property variant the catalog's grouping key (`WATER`, `SHARK`, `LAVA_CRIMSON_ISLE`, …). Open-ended
 *   — Hypixel adds variants, so never match it against a closed set.
 * @property rare orthogonal to [rarity]: Hypixel flags a handful of creatures as rare catches
 *   independently of their tier.
 * @property fishingExperience 0 when the catalog does not know it, which is a real state for a
 *   creature an admin added before the XP was published.
 */
data class SeaCreature(
    val name: String,
    val variant: String,
    val rarity: String,
    val rare: Boolean,
    val fishingExperience: Int,
) {
    /** Display colour for this creature's rarity — see [SkyblockRarity]. */
    fun rarityColor(): Int = SkyblockRarity.colorFor(rarity)

    /** Sort key for rarity; higher is rarer. -1 for a tier this build does not know. */
    fun rarityOrdinal(): Int = SkyblockRarity.forName(rarity)?.ordinal ?: -1
}
