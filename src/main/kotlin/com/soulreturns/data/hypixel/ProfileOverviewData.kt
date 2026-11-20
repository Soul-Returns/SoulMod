package com.soulreturns.data.hypixel

import kotlinx.serialization.json.longOrNull

/*
 * Typed reads the Profile Viewer's Overview (and its headline tiles and highlights) needs beyond the
 * shared accessors in SkyblockProfileMember. Wire paths checked against a live `/skyblock/profiles`
 * response. Every accessor tolerates absence: null means "not in the response", never zero.
 */

/** `currencies.coin_purse`. */
val SkyblockProfileMember.purse: Double?
    get() = raw.obj("currencies")?.prim("coin_purse")?.let { it.content.toDoubleOrNull() }

/** `profile.bank_account`: the personal bank (co-op profiles), null without the Banking API. */
val SkyblockProfileMember.personalBank: Double?
    get() = raw.path("profile")?.prim("bank_account")?.content?.toDoubleOrNull()

/** `profile.first_join` (epoch ms). */
val SkyblockProfileMember.firstJoinMs: Long?
    get() = raw.path("profile")?.prim("first_join")?.longOrNull

/** `fairy_soul.total_collected`. */
val SkyblockProfileMember.fairySouls: Int?
    get() = raw.path("fairy_soul")?.prim("total_collected")?.longOrNull?.toInt()

/** `bestiary.milestone.last_claimed_milestone`. */
val SkyblockProfileMember.bestiaryMilestone: Int?
    get() = raw.path("bestiary", "milestone")?.prim("last_claimed_milestone")?.longOrNull?.toInt()

/** `accessory_bag_storage.highest_magical_power`; null when the bag storage is absent. */
val SkyblockProfileMember.magicalPower: Int?
    get() = raw.path("accessory_bag_storage")?.prim("highest_magical_power")?.longOrNull?.toInt()

/** `accessory_bag_storage.selected_power` ("sighted"), null when none is selected. */
val SkyblockProfileMember.selectedPower: String?
    get() = raw.path("accessory_bag_storage")?.string("selected_power")

/** `inventory.bag_contents.sacks_bag.data`: the sacks the player carries (priced as items by the Market value). */
val SkyblockProfileMember.sacksBagBlob: String? get() = dataBlob("inventory", "bag_contents", "sacks_bag")

/**
 * `nether_island_player_data.kuudra_completed_tiers`: completions per tier key (`none`, `hot`,
 * `burning`, `fiery`, `infernal`), only tiers with a count. `highest_wave_*` keys are dropped.
 */
val SkyblockProfileMember.kuudraCompletions: Map<String, Int>
    get() {
        val tiers = raw.path("nether_island_player_data", "kuudra_completed_tiers") ?: return emptyMap()
        return KUUDRA_TIERS.mapNotNull { key -> tiers.prim(key)?.longOrNull?.let { key to it.toInt() } }.toMap()
    }

/** Kuudra tier keys in ascending order, with their in-game names. */
val KUUDRA_TIERS = listOf("none", "hot", "burning", "fiery", "infernal")
val KUUDRA_TIER_NAMES = mapOf("none" to "Basic", "hot" to "Hot", "burning" to "Burning", "fiery" to "Fiery", "infernal" to "Infernal")

/** `dungeons.treasures`: (recent runs listed, chests in them); null when Hypixel sent none. */
val SkyblockProfileMember.dungeonTreasures: Pair<Int, Int>?
    get() {
        val t = raw.path("dungeons", "treasures") ?: return null
        val runs = (t["runs"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        val chests = (t["chests"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        return if (runs == 0 && chests == 0) null else runs to chests
    }

/** `banking.balance` of the profile: the co-op bank, null without the Banking API. */
val SkyblockProfile.bankBalance: Double?
    get() = raw.path("banking")?.prim("balance")?.content?.toDoubleOrNull()

/** Members still on this profile: Hypixel keeps players who left, marked `profile.deletion_notice`. */
val SkyblockProfile.activeMemberCount: Int
    get() {
        val members = raw.path("members") ?: return 0
        return members.keys.count { members.obj(it)?.path("profile")?.containsKey("deletion_notice") != true }
    }
