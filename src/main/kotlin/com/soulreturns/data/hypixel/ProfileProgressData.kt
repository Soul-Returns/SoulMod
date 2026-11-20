package com.soulreturns.data.hypixel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/*
 * Typed reads for the Profile Viewer's Collections, Bestiary and Misc sections. Wire paths were
 * checked against a live `/skyblock/profiles` dump (key names only). Every accessor tolerates
 * absence: null means "not in the response", never zero; the sections turn null into a dash or an
 * API-off notice.
 *
 * The mod bundles no collection tier or bestiary tier tables: tiers are the ones the response states
 * (`player_data.unlocked_coll_tiers`), bestiary mobs show raw kill counts.
 */

// ------------------------------------------------------------------ shared helpers

/** A numeric field as a Long, or null when absent or not a number (`last_killed_mob` is a string). */
private fun JsonObject.numberOf(key: String): Long? {
    val p = prim(key)?.takeIf { !it.isString } ?: return null
    return p.longOrNull ?: p.doubleOrNull?.toLong()
}

/**
 * `"COBBLESTONE_12"` to (`COBBLESTONE`, 12). The id may itself hold `_` and `:` (`INK_SACK:3_9`), so
 * the split is at the last `_`. Hypixel also writes `_-1` entries for a collection that was started
 * but has no tier yet, which parse as tier -1.
 */
internal fun splitTierEntry(entry: String): Pair<String, Int>? {
    val i = entry.lastIndexOf('_')
    if (i <= 0) return null
    val tier = entry.substring(i + 1).toIntOrNull() ?: return null
    return entry.substring(0, i) to tier
}

/** Highest tier per id of an array of `ID_<tier>` strings. */
private fun highestTiers(array: JsonElement?): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    for (e in array as? JsonArray ?: return out) {
        val (id, tier) = splitTierEntry((e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue) ?: continue
        val prev = out[id]
        if (prev == null || tier > prev) out[id] = tier
    }
    return out
}

private val COLOR_CODE = Regex("§.")
private val LEVEL_SUFFIX = Regex("_-?[0-9]+$")

/** `END_STONE` to `End Stone`; `INK_SACK:3` to `Ink Sack 3`. The fallback for an id nothing else names. */
internal fun prettyId(id: String): String =
    id.replace('_', ' ').replace(':', ' ').trim().split(' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }

// ------------------------------------------------------------------ collections

/** One collection the viewer lists: its API id, the name the mock shows and the group it sits in. */
class CollectionDef(val id: String, val name: String, val category: String)

/**
 * The collections grouped as the mock groups them (`COLL` in `mocks/profile-viewer/data.js`), in game
 * order, with their Hypixel API ids. The response carries counts only, no names and no categories, so
 * this is the one table the section needs. Ids the table lacks still show up, under [OTHER].
 * It is our own transcription of the mock's lists, for grouping and fallback names only; a name from
 * the item catalog wins wherever the catalog knows the id.
 */
object CollectionTable {
    const val OTHER = "Other"

    val CATEGORIES = listOf("Farming", "Mining", "Combat", "Foraging", "Fishing")

    private fun defs(category: String, vararg pairs: Pair<String, String>) = pairs.map { CollectionDef(it.first, it.second, category) }

    val DEFS: List<CollectionDef> = defs(
        "Farming",
        "WHEAT" to "Wheat", "CARROT_ITEM" to "Carrot", "POTATO_ITEM" to "Potato", "PUMPKIN" to "Pumpkin", "MELON" to "Melon",
        "SEEDS" to "Seeds", "MUSHROOM_COLLECTION" to "Mushroom", "INK_SACK:3" to "Cocoa Beans", "CACTUS" to "Cactus",
        "SUGAR_CANE" to "Sugar Cane", "FEATHER" to "Feather", "LEATHER" to "Leather", "PORK" to "Raw Porkchop",
        "RAW_CHICKEN" to "Raw Chicken", "MUTTON" to "Mutton", "RABBIT" to "Raw Rabbit", "NETHER_STALK" to "Nether Wart",
    ) + defs(
        "Mining",
        "COBBLESTONE" to "Cobblestone", "COAL" to "Coal", "IRON_INGOT" to "Iron Ingot", "GOLD_INGOT" to "Gold Ingot",
        "DIAMOND" to "Diamond", "INK_SACK:4" to "Lapis Lazuli", "EMERALD" to "Emerald", "REDSTONE" to "Redstone",
        "QUARTZ" to "Nether Quartz", "OBSIDIAN" to "Obsidian", "GLOWSTONE_DUST" to "Glowstone Dust", "GRAVEL" to "Gravel",
        "ICE" to "Ice", "NETHERRACK" to "Netherrack", "SAND" to "Sand", "ENDER_STONE" to "End Stone", "MITHRIL_ORE" to "Mithril",
        "HARD_STONE" to "Hard Stone", "GEMSTONE_COLLECTION" to "Gemstone", "TITANIUM_ORE" to "Titanium",
        "UMBER_COLLECTION" to "Umber", "TUNGSTEN_COLLECTION" to "Tungsten", "GLACITE" to "Glacite", "SULPHUR_ORE" to "Sulphur",
        "MYCEL" to "Mycelium", "SAND:1" to "Red Sand",
    ) + defs(
        "Combat",
        "ROTTEN_FLESH" to "Rotten Flesh", "BONE" to "Bone", "STRING" to "String", "SPIDER_EYE" to "Spider Eye",
        "SULPHUR" to "Gunpowder", "ENDER_PEARL" to "Ender Pearl", "GHAST_TEAR" to "Ghast Tear", "SLIME_BALL" to "Slimeball",
        "BLAZE_ROD" to "Blaze Rod", "MAGMA_CREAM" to "Magma Cream", "CHILI_PEPPER" to "Chili Pepper",
    ) + defs(
        "Foraging",
        "LOG" to "Oak Wood", "LOG:1" to "Spruce Wood", "LOG:2" to "Birch Wood", "LOG_2:1" to "Dark Oak Wood",
        "LOG_2" to "Acacia Wood", "LOG:3" to "Jungle Wood", "MANGROVE_LOG" to "Mangrove Wood", "FIG_LOG" to "Fig Log",
    ) + defs(
        "Fishing",
        "RAW_FISH" to "Raw Fish", "RAW_FISH:1" to "Raw Salmon", "RAW_FISH:2" to "Clownfish", "RAW_FISH:3" to "Pufferfish",
        "PRISMARINE_SHARD" to "Prismarine Shard", "PRISMARINE_CRYSTALS" to "Prismarine Crystals", "CLAY_BALL" to "Clay",
        "WATER_LILY" to "Lily Pad", "INK_SACK" to "Ink Sac", "SPONGE" to "Sponge", "MAGMA_FISH" to "Magmafish",
    )
}

/** One collection: [count] collected, [tier] the highest unlocked tier the response states (0 = none stated). */
class CollectionProgress(val id: String, val name: String, val category: String, val count: Long, val tier: Int)

/** The collections of one category, in display order. [unlocked] = entries with a tier. */
class CollectionGroup(val category: String, val entries: List<CollectionProgress>) {
    val unlocked: Int get() = entries.count { it.tier > 0 }
}

/** False when the player switched the Collections API off: Hypixel then omits the `collection` key. */
val SkyblockProfileMember.hasCollections: Boolean get() = raw.containsKey("collection")

/**
 * Every collection grouped as [CollectionTable] groups them, or null when the Collections API is off.
 *
 * The listed collections always appear (an unstarted one has count 0 and no tier). An id in
 * `collection` that the table does not know goes to [CollectionTable.OTHER] when the response gives it
 * an unlocked-tier entry; Hypixel's `collection` also holds crafted-from counters (`ENCHANTED_DIAMOND`)
 * that are not collections and have no tier entry, so those are left out.
 *
 * [nameOf] names an id the table lacks (the item catalog, `GET /items`); when it knows nothing either
 * the id is prettified (`DOUBLE_PLANT` shows as "Double Plant").
 */
fun SkyblockProfileMember.collectionGroups(nameOf: (String) -> String? = { null }): List<CollectionGroup>? {
    val counts = raw.obj("collection") ?: return null
    val tiers = highestTiers(raw.path("player_data")?.get("unlocked_coll_tiers"))
    fun catalogName(id: String): String? = nameOf(id)?.replace(COLOR_CODE, "")?.trim()?.takeIf { it.isNotEmpty() }
    fun progress(id: String, name: String, category: String) =
        CollectionProgress(id, name, category, counts.numberOf(id) ?: 0L, (tiers[id] ?: 0).coerceAtLeast(0))

    val known = CollectionTable.DEFS.mapTo(HashSet()) { it.id }
    val groups = CollectionTable.CATEGORIES.map { cat ->
        CollectionGroup(cat, CollectionTable.DEFS.filter { it.category == cat }.map { progress(it.id, catalogName(it.id) ?: it.name, cat) })
    }
    val other = counts.keys.filter { it !in known && it in tiers }.map { id ->
        val name = catalogName(id) ?: prettyId(id)
        progress(id, name, CollectionTable.OTHER)
    }.sortedWith(compareByDescending<CollectionProgress> { it.count }.thenBy { it.name })
    return if (other.isEmpty()) groups else groups + CollectionGroup(CollectionTable.OTHER, other)
}

// ------------------------------------------------------------------ minions

/** One minion type and the highest tier of it the player crafted. */
class MinionProgress(val id: String, val name: String, val tier: Int)

/** Minion ids whose display name is not the prettified id. */
private val MINION_NAMES = mapOf(
    "ENDER_STONE" to "End Stone", "COCOA" to "Cocoa Beans", "NETHER_WARTS" to "Nether Wart", "CAVESPIDER" to "Cave Spider",
)

/** `player_data.crafted_generators` as `ID_<tier>` pairs, or null when the response has no such list. */
private val SkyblockProfileMember.craftedMinionEntries: List<Pair<String, Int>>?
    get() {
        val list = raw.path("player_data")?.get("crafted_generators") as? JsonArray ?: return null
        return list.mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.let { splitTierEntry(it.content) } }.filter { it.second > 0 }
    }

/** Distinct (type, tier) crafts: the "unique minions" count. Null without `player_data.crafted_generators`. */
val SkyblockProfileMember.uniqueMinionCrafts: Int? get() = craftedMinionEntries?.distinct()?.size

/** The highest crafted tier per minion type, highest tier first. Null without the crafted list. */
val SkyblockProfileMember.craftedMinions: List<MinionProgress>?
    get() {
        val entries = craftedMinionEntries ?: return null
        val best = LinkedHashMap<String, Int>()
        for ((id, tier) in entries) if (tier > (best[id] ?: 0)) best[id] = tier
        return best.map { (id, tier) -> MinionProgress(id, MINION_NAMES[id] ?: prettyId(id), tier) }
            .sortedWith(compareByDescending<MinionProgress> { it.tier }.thenBy { it.name })
    }

// ------------------------------------------------------------------ bestiary

/** Kills of one mob, every variant (`lapis_zombie_7`) of the same name added together. */
class BestiaryMob(val name: String, val kills: Long)

/** True when the response has a `bestiary.kills` object. */
val SkyblockProfileMember.hasBestiary: Boolean get() = raw.path("bestiary")?.obj("kills") != null

/**
 * `bestiary.kills`, one row per mob, most kills first. Keys are `<mob>_<level>`: the trailing number is
 * dropped from the name and rows of the same name are summed. Zero counts and the `last_killed_mob`
 * string are skipped.
 */
val SkyblockProfileMember.bestiaryMobs: List<BestiaryMob>
    get() {
        val kills = raw.path("bestiary")?.obj("kills") ?: return emptyList()
        val sums = LinkedHashMap<String, Long>()
        for (key in kills.keys) {
            val n = kills.numberOf(key)?.takeIf { it > 0 } ?: continue
            val base = key.replace(LEVEL_SUFFIX, "")
            sums[base] = (sums[base] ?: 0L) + n
        }
        return sums.map { (base, n) -> BestiaryMob(prettyId(base), n) }
            .sortedWith(compareByDescending<BestiaryMob> { it.kills }.thenBy { it.name })
    }

/** `bestiary.deaths` summed over every mob; null when the object is absent. */
val SkyblockProfileMember.bestiaryDeaths: Long?
    get() {
        val deaths = raw.path("bestiary")?.obj("deaths") ?: return null
        return deaths.keys.sumOf { deaths.numberOf(it) ?: 0L }
    }

// ------------------------------------------------------------------ misc: essence

/** The eight essence types in the order the mock lists them. */
val ESSENCE_TYPES = listOf("WITHER", "DRAGON", "SPIDER", "UNDEAD", "DIAMOND", "GOLD", "ICE", "CRIMSON")

/** [type] is the API key (`WITHER`), [amount] `currencies.essence.<TYPE>.current`. */
class EssenceAmount(val type: String, val amount: Long) {
    val name: String get() = prettyId(type)
}

/**
 * All eight essence types in order (a type never obtained is 0), then any type Hypixel adds. Null when
 * the member has no `currencies` object at all.
 */
val SkyblockProfileMember.essenceAmounts: List<EssenceAmount>?
    get() {
        val currencies = raw.obj("currencies") ?: return null
        val essence = currencies.obj("essence")
        fun amount(type: String) = essence?.obj(type)?.numberOf("current") ?: 0L
        val extra = essence?.keys?.filter { it !in ESSENCE_TYPES }.orEmpty()
        return (ESSENCE_TYPES + extra).map { EssenceAmount(it, amount(it)) }
    }

// ------------------------------------------------------------------ misc: stats

/** `player_stats.kills.total`. */
val SkyblockProfileMember.lifetimeKills: Long? get() = raw.path("player_stats", "kills")?.numberOf("total")

/** `player_stats.deaths.total`. */
val SkyblockProfileMember.lifetimeDeaths: Long? get() = raw.path("player_stats", "deaths")?.numberOf("total")

/** `player_stats.highest_critical_damage`. */
val SkyblockProfileMember.highestCriticalHit: Double?
    get() = raw.obj("player_stats")?.prim("highest_critical_damage")?.takeIf { !it.isString }?.doubleOrNull

/** `player_stats.mythos.kills`: Mythological Ritual kills. */
val SkyblockProfileMember.mythologicalKills: Long? get() = raw.path("player_stats", "mythos")?.numberOf("kills")

/** `player_stats.auctions`: lifetime Auction House numbers. */
class AuctionStats(val won: Long, val created: Long, val coinsSpent: Long, val coinsEarned: Long)

val SkyblockProfileMember.auctionStats: AuctionStats?
    get() {
        val a = raw.path("player_stats", "auctions") ?: return null
        return AuctionStats(a.long("won"), a.long("created"), a.long("gold_spent"), a.long("gold_earned"))
    }

/** `player_stats.gifts`: (given, received); null without the object. */
val SkyblockProfileMember.giftStats: Pair<Long, Long>?
    get() {
        val g = raw.path("player_stats", "gifts") ?: return null
        return g.long("total_given") to g.long("total_received")
    }

/**
 * `item_data.soulflow`. [INFERENCE] the path comes from the Skyblocker model; none of the dumps we
 * hold has an `item_data` object, so it is unverified against a real response.
 */
val SkyblockProfileMember.soulflowAmount: Long? get() = raw.path("item_data")?.numberOf("soulflow")

// ------------------------------------------------------------------ misc: Experimentation Table

/** Experimentation Table tiers by their index in `best_score_<n>` / `claims_<n>`. */
val EXPERIMENT_TIERS = listOf("Beginner", "High", "Grand", "Supreme", "Transcendent", "Metaphysical")

/**
 * The Experimentation Table bests. `experimentation.pairings` is Superpairs, `simon` Chronomatron and
 * `numbers` Ultrasequencer [INFERENCE: the game names are matched by their scores in a dump, not by
 * a documented mapping].
 *
 * @property superpairsTier the highest tier with a claim or a score, null when never played.
 * @property chronomatronBest / [ultrasequencerBest] the best score (rounds) over every tier, null when never played.
 */
class ExperimentBests(val superpairsTier: Int?, val chronomatronBest: Int?, val ultrasequencerBest: Int?) {
    val superpairsName: String? get() = superpairsTier?.let { EXPERIMENT_TIERS.getOrNull(it) ?: "Tier ${it + 1}" }
}

/** Null without an `experimentation` object. */
val SkyblockProfileMember.experimentBests: ExperimentBests?
    get() {
        val e = raw.obj("experimentation") ?: return null
        fun scores(game: String, prefix: String): List<Pair<Int, Long>> =
            e.obj(game)?.let { g -> g.keys.mapNotNull { k -> k.removePrefix(prefix).takeIf { k.startsWith(prefix) }?.toIntOrNull()?.let { it to g.long(k) } } }.orEmpty()
        val pairs = e.obj("pairings")
        val playedTiers = scores("pairings", "best_score_").filter { it.second > 0 }.map { it.first } +
            scores("pairings", "claims_").filter { it.second > 0 }.map { it.first }
        fun best(game: String) = scores(game, "best_score_").maxOfOrNull { it.second }?.takeIf { it > 0 }?.toInt()
        return ExperimentBests(if (pairs == null) null else playedTiers.maxOrNull(), best("simon"), best("numbers"))
    }
