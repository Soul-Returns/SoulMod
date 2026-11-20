package com.soulreturns.data.hypixel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Typed reads for the Profile Viewer's Crimson Isle, Fishing, Farming and Rift sections. Wire paths
 * checked against a real `/skyblock/profiles` response. Absent keys mean null (or an empty list), never
 * zero. Kuudra completions live in ProfileOverviewData (`kuudraCompletions`).
 */

private fun JsonObject.optLong(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.arraySize(key: String): Int = (this[key] as? JsonArray)?.size ?: 0

// ------------------------------------------------------------------ Crimson Isle

/** One Dojo test: its in-game name ("Force") and the best points, null when never attempted. */
data class DojoTestScore(val key: String, val name: String, val points: Int?)

/**
 * `nether_island_player_data` (everything but Kuudra tiers).
 *
 * @property faction `selected_faction`: `mages` or `barbarians`, null before a faction was chosen.
 * @property magesReputation `mages_reputation`, can be negative.
 * @property matriarchPearls `matriarch.pearls_collected`.
 * @property abiphoneContacts size of `abiphone.active_contacts`.
 */
class CrimsonIsleProgress(
    val faction: String?,
    val magesReputation: Int?,
    val barbariansReputation: Int?,
    val matriarchPearls: Long?,
    val abiphoneContacts: Int?,
    val dojo: List<DojoTestScore>,
)

/** `dojo_points_<key>` to the test's name, in the order the Dojo lists them. */
val DOJO_TESTS: List<Pair<String, String>> = listOf(
    "mob_kb" to "Force",
    "wall_jump" to "Stamina",
    "archer" to "Mastery",
    "sword_swap" to "Discipline",
    "snake" to "Swiftness",
    "lock_head" to "Control",
    "fireball" to "Tenacity",
)

/** Null when the member never opened the Crimson Isle data. */
val SkyblockProfileMember.crimsonIsle: CrimsonIsleProgress?
    get() {
        val n = raw.path("nether_island_player_data") ?: return null
        val dojo = n.path("dojo")
        val abiphone = n.path("abiphone")
        return CrimsonIsleProgress(
            faction = n.string("selected_faction")?.lowercase(),
            magesReputation = n.optLong("mages_reputation")?.toInt(),
            barbariansReputation = n.optLong("barbarians_reputation")?.toInt(),
            matriarchPearls = n.path("matriarch")?.optLong("pearls_collected"),
            abiphoneContacts = abiphone?.takeIf { it.containsKey("active_contacts") }?.arraySize("active_contacts"),
            dojo = DOJO_TESTS.map { (key, name) -> DojoTestScore(key, name, dojo?.optLong("dojo_points_$key")?.toInt()) },
        )
    }

// ------------------------------------------------------------------ Fishing

/** Trophy fish tier names, lowest first; indexes match [TrophyFishEntry.tiers]. */
val TROPHY_TIERS: List<String> = listOf("bronze", "silver", "gold", "diamond")

/** Catches of one trophy fish per tier; [rarity] is the raw SkyBlock rarity key, null for a fish this build does not know. */
class TrophyFishEntry(val key: String, val name: String, val rarity: String?, val tiers: List<Int>) {
    val total: Int get() = tiers.sum()

    /** Highest tier caught (0 bronze .. 3 diamond), -1 when none. */
    val bestTier: Int get() = tiers.indexOfLast { it > 0 }
}

class TrophyFishing(val fish: List<TrophyFishEntry>) {
    /** Highest tier caught of any fish, -1 when none. */
    val bestTier: Int get() = fish.maxOfOrNull { it.bestTier } ?: -1
}

/** `trophy_fish` key to (name, rarity). The API lists only fish that were caught; the section shows all of these. */
private val KNOWN_TROPHY_FISH: List<Triple<String, String, String>> = listOf(
    Triple("blobfish", "Blobfish", "COMMON"),
    Triple("flyfish", "Flyfish", "UNCOMMON"),
    Triple("golden_fish", "Golden Fish", "LEGENDARY"),
    Triple("gusher", "Gusher", "COMMON"),
    Triple("karate_fish", "Karate Fish", "EPIC"),
    Triple("lava_horse", "Lava Horse", "RARE"),
    Triple("mana_ray", "Mana Ray", "RARE"),
    Triple("moldfin", "Moldfin", "EPIC"),
    Triple("obfuscated_fish_1", "Obfuscated 1", "COMMON"),
    Triple("obfuscated_fish_2", "Obfuscated 2", "UNCOMMON"),
    Triple("obfuscated_fish_3", "Obfuscated 3", "RARE"),
    Triple("skeleton_fish", "Skeleton Fish", "EPIC"),
    Triple("slugfish", "Slugfish", "UNCOMMON"),
    Triple("soul_fish", "Soul Fish", "EPIC"),
    Triple("steaming_hot_flounder", "Steaming-Hot Flounder", "COMMON"),
    Triple("sulphur_skitter", "Sulphur Skitter", "COMMON"),
    Triple("vanille", "Vanille", "RARE"),
    Triple("volcanic_stonefish", "Volcanic Stonefish", "UNCOMMON"),
)

private val TROPHY_META_KEYS = setOf("total_caught", "rewards", "last_caught")

private fun humanize(key: String): String =
    key.split('_').filter { it.isNotEmpty() }.joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

/**
 * `trophy_fish`: per fish `<fish>` (all tiers), `<fish>_bronze|silver|gold|diamond`. Every known fish is
 * listed (zeros included), plus any fish the API names that this build does not. Trophy frogs are not
 * read: no captured profile confirms where they live (see `mocks/profile-viewer/DATA.md`). Null when
 * `trophy_fish` is absent. Sorted by name.
 */
val SkyblockProfileMember.trophyFishing: TrophyFishing?
    get() {
        val t = raw.path("trophy_fish") ?: return null
        val unknown = LinkedHashSet<String>()
        for (key in t.keys) {
            if (key in TROPHY_META_KEYS || "frog" in key) continue
            val tier = TROPHY_TIERS.firstOrNull { key.endsWith("_$it") }
            val base = if (tier != null) key.removeSuffix("_$tier") else key
            if (KNOWN_TROPHY_FISH.none { it.first == base }) unknown += base
        }
        val entries = KNOWN_TROPHY_FISH.map { (key, name, rarity) -> trophyEntry(t, key, name, rarity) } +
            unknown.map { trophyEntry(t, it, humanize(it), null) }
        return TrophyFishing(entries.sortedBy { it.name })
    }

private fun trophyEntry(t: JsonObject, key: String, name: String, rarity: String?) =
    TrophyFishEntry(key, name, rarity, TROPHY_TIERS.map { t.optLong("${key}_$it")?.toInt() ?: 0 })

/**
 * Fishing counters. [seaCreaturesKilled] is the pet milestone count (`player_stats.pets.milestone.sea_creatures_killed`),
 * [itemsFished] `player_stats.items_fished.total`, [treasure] `treasure` plus `large_treasure` of the same object.
 */
class FishingCounts(val seaCreaturesKilled: Long?, val itemsFished: Long?, val treasure: Long?)

val SkyblockProfileMember.fishingCounts: FishingCounts
    get() {
        val fished = raw.path("player_stats", "items_fished")
        val treasure = fished?.optLong("treasure")
        val large = fished?.optLong("large_treasure")
        return FishingCounts(
            seaCreaturesKilled = raw.path("player_stats", "pets", "milestone")?.optLong("sea_creatures_killed"),
            itemsFished = fished?.optLong("total"),
            treasure = if (treasure == null && large == null) null else (treasure ?: 0L) + (large ?: 0L),
        )
    }

// ------------------------------------------------------------------ Farming

/** Jacob's Contest brackets, lowest first. */
val JACOB_BRACKETS: List<String> = listOf("bronze", "silver", "gold", "platinum", "diamond")

/** Contest crops: API id (`personal_bests` / `unique_brackets` entries) to name, in display order. */
private val JACOB_CROPS: List<Pair<String, String>> = listOf(
    "WHEAT" to "Wheat",
    "CARROT_ITEM" to "Carrot",
    "POTATO_ITEM" to "Potato",
    "PUMPKIN" to "Pumpkin",
    "MELON" to "Melon",
    "SUGAR_CANE" to "Sugar Cane",
    "CACTUS" to "Cactus",
    "INK_SACK:3" to "Cocoa Beans",
    "MUSHROOM_COLLECTION" to "Mushroom",
    "NETHER_STALK" to "Nether Wart",
    "DOUBLE_PLANT" to "Sunflower",
    "MOONFLOWER" to "Moonflower",
    "WILD_ROSE" to "Wild Rose",
)

/** The first ten crops are always listed; later ones only when the profile has data for them. */
private const val JACOB_ALWAYS_LISTED = 10

/** One crop: best bracket reached (null = none) and the personal best collected in a contest (null = never entered). */
class JacobCrop(val id: String, val name: String, val bracket: String?, val personalBest: Long?)

/**
 * `jacobs_contest`: [medalsGold]/[medalsSilver]/[medalsBronze] are the medals still to spend
 * (`medals_inv`), [doubleDrops] and [farmingLevelCapPerk] Anita's perk levels (`perks`), [contests] the
 * number of entries in `contests`.
 */
class JacobsContestProgress(
    val medalsGold: Int,
    val medalsSilver: Int,
    val medalsBronze: Int,
    val doubleDrops: Int,
    val farmingLevelCapPerk: Int,
    val contests: Int,
    val crops: List<JacobCrop>,
)

/** Null when `jacobs_contest` is absent. */
val SkyblockProfileMember.jacobsContest: JacobsContestProgress?
    get() {
        val j = raw.path("jacobs_contest") ?: return null
        val medals = j.path("medals_inv")
        val perks = j.path("perks")
        val brackets = j.path("unique_brackets")
        val bests = j.path("personal_bests")
        val crops = JACOB_CROPS.withIndex().mapNotNull { (i, crop) ->
            val (id, name) = crop
            val bracket = JACOB_BRACKETS.lastOrNull { b -> (brackets?.get(b) as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == id } == true }
            val best = bests?.optLong(id)
            if (i >= JACOB_ALWAYS_LISTED && bracket == null && best == null) null else JacobCrop(id, name, bracket, best)
        }
        return JacobsContestProgress(
            medalsGold = medals?.optLong("gold")?.toInt() ?: 0,
            medalsSilver = medals?.optLong("silver")?.toInt() ?: 0,
            medalsBronze = medals?.optLong("bronze")?.toInt() ?: 0,
            doubleDrops = perks?.optLong("double_drops")?.toInt() ?: 0,
            farmingLevelCapPerk = perks?.optLong("farming_level_cap")?.toInt() ?: 0,
            contests = j.path("contests")?.size ?: 0,
            crops = crops,
        )
    }

/** `garden_player_data`: [copper] and [larvaConsumed], each null when absent; the whole object null without it. */
class GardenPlayerProgress(val copper: Long?, val larvaConsumed: Long?)

val SkyblockProfileMember.gardenPlayer: GardenPlayerProgress?
    get() {
        val g = raw.path("garden_player_data") ?: return null
        return GardenPlayerProgress(g.optLong("copper"), g.optLong("larva_consumed"))
    }

// ------------------------------------------------------------------ Rift

/** The four item blobs under `rift.inventory.*.data` (base64), null for each the Inventory API hid. */
class RiftItems(val inventory: String?, val armor: String?, val equipment: String?, val enderChest: String?) {
    val any: Boolean get() = inventory != null || armor != null || equipment != null || enderChest != null
}

/**
 * The Rift. [motes] is `currencies.motes_purse`, [lifetimeMotes] `player_stats.rift.lifetime_motes_earned`,
 * [enigmaSouls] `rift.enigma.found_souls`, [catsFound] `rift.dead_cats.found_cats`, [timecharms] the distinct
 * `rift.gallery.secured_trophies[].type`, [witherCageEyes] `rift.wither_cage.killed_eyes`, [prismUsed]
 * `rift.access.consumed_prism`.
 */
class RiftProgress(
    val motes: Double?,
    val lifetimeMotes: Long?,
    val enigmaSouls: Int,
    val catsFound: Int,
    val timecharms: Int,
    val witherCageEyes: Int,
    val prismUsed: Boolean,
    val items: RiftItems,
)

/** Null when the member never entered the Rift (no `rift` object). */
val SkyblockProfileMember.riftProgress: RiftProgress?
    get() {
        val r = raw.path("rift") ?: return null
        val trophies = (r.path("gallery")?.get("secured_trophies") as? JsonArray).orEmpty()
        fun blob(key: String): String? = r.path("inventory", key)?.string("data")
        return RiftProgress(
            motes = raw.path("currencies")?.prim("motes_purse")?.doubleOrNull,
            lifetimeMotes = raw.path("player_stats", "rift")?.optLong("lifetime_motes_earned"),
            enigmaSouls = r.path("enigma")?.arraySize("found_souls") ?: 0,
            catsFound = r.path("dead_cats")?.arraySize("found_cats") ?: 0,
            timecharms = trophies.mapNotNull { (it as? JsonObject)?.string("type") }.toSet().size,
            witherCageEyes = r.path("wither_cage")?.arraySize("killed_eyes") ?: 0,
            prismUsed = r.path("access")?.boolean("consumed_prism") == true,
            items = RiftItems(blob("inv_contents"), blob("inv_armor"), blob("equipment_contents"), blob("ender_chest_contents")),
        )
    }
