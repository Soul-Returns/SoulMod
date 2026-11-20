package com.soulreturns.data.hypixel

import com.soulreturns.data.skyblock.SkyblockRarity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Reads for the Profile Viewer's Pets section beyond `PetState.apiPets` (which already decodes
 * `pets_data.pets[]`). Paths: DATA.md; `pet_care.pet_types_sacrificed` is also what the backend's caps
 * endpoint reads for the Taming cap. Verified against a real dump: `autopet.rules` is an array of objects
 * (`id`, `name`, `uuid`, `uniqueId`, `exceptions`, `disabled`); only its length is read.
 */

/** `leveling.highest_pet_score`; null when `leveling` has none. */
val SkyblockProfileMember.petScore: Long?
    get() = raw.path("leveling")?.prim("highest_pet_score")?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

/** Unique pet types sacrificed to Kat: the length of `pets_data.pet_care.pet_types_sacrificed`, 0 when absent. */
val SkyblockProfileMember.katTypesSacrificed: Int
    get() = (raw.path("pets_data", "pet_care")?.get("pet_types_sacrificed") as? JsonArray)?.size ?: 0

/** Number of rules in `pets_data.autopet.rules`, 0 when absent. */
val SkyblockProfileMember.autopetRuleCount: Int
    get() = (raw.path("pets_data", "autopet")?.get("rules") as? JsonArray)?.size ?: 0

/**
 * The market key of a pet (`TYPE;tierIndex`, `+100` from level 100, `+200` at 200): the *base* tier, since a
 * Tier Boost is a removable held item. Same convention as `MarketItemKey`. Null for an unknown tier.
 */
fun petMarketKey(type: String, tier: String?, level: Int): String? {
    val index = SkyblockRarity.forName(tier)?.ordinal ?: return null
    val suffix = if (level >= 200) "+200" else if (level >= 100) "+100" else ""
    return "${type.uppercase()};$index$suffix"
}
