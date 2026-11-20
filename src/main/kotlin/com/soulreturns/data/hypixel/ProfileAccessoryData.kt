package com.soulreturns.data.hypixel

import kotlinx.serialization.json.JsonArray
import java.util.Locale

/*
 * The Accessories section's reads: the accessory bag blob and `accessory_bag_storage` (Magical Power,
 * selected power, tuning, bag upgrades). Confirmed against a real dump: `tuning.slot_0` holds the eight
 * stat keys as ints, `consumed_prism` sits in `rift.access`, `active_contacts` is a list or absent. `magicalPower` and `selectedPower` already live in
 * `ProfileOverviewData.kt`.
 */

/** `accessory_bag_storage.bag_upgrades_purchased`. */
val SkyblockProfileMember.bagUpgradesPurchased: Int?
    get() {
        val storage = raw.path("accessory_bag_storage") ?: return null
        return if (storage.containsKey("bag_upgrades_purchased")) storage.long("bag_upgrades_purchased").toInt() else null
    }

/** `rift.access.consumed_prism`: the Rift Prism was used, worth [MagicalPower.RIFT_PRISM] Magical Power. */
val SkyblockProfileMember.riftPrismConsumed: Boolean get() = raw.path("rift", "access")?.boolean("consumed_prism") == true

/** `nether_island_player_data.abiphone.active_contacts`: how many Abiphone contacts are active; null when the list is absent. */
val SkyblockProfileMember.abiphoneContacts: Int?
    get() = (raw.path("nether_island_player_data", "abiphone")?.get("active_contacts") as? JsonArray)?.size

/** One tuning stat with the points put into it. */
class TuningPoint(val stat: String, val points: Int)

/** API key to display name, in the order the Stats Tuning menu lists them. */
private val TUNING_STATS = listOf(
    "health" to "Health",
    "defense" to "Defense",
    "walk_speed" to "Speed",
    "strength" to "Strength",
    "critical_chance" to "Crit Chance",
    "critical_damage" to "Crit Damage",
    "attack_speed" to "Attack Speed",
    "intelligence" to "Intelligence",
)

/**
 * `accessory_bag_storage.tuning.slot_0`: the active Stats Tuning (slots 1 to 8 are saved templates), only
 * the stats that have points. Empty when nothing is tuned or the field is absent.
 */
val SkyblockProfileMember.activeTuning: List<TuningPoint>
    get() {
        val slot = raw.path("accessory_bag_storage", "tuning", "slot_0") ?: return emptyList()
        return TUNING_STATS.mapNotNull { (key, name) -> slot.long(key).toInt().takeIf { it > 0 }?.let { TuningPoint(name, it) } }
    }

/** `bubbly` becomes `Bubbly`, `very_special` would become `Very Special`. */
fun powerDisplayName(key: String): String =
    key.lowercase(Locale.ROOT).split('_', ' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase(Locale.ROOT) } }

/**
 * The bag card's list: the slots of [items] that [rarity] ("ALL" keeps every one) and [query] (lower case, a
 * part of the name) keep, strongest rarity first. Equal rarities stay in slot order; an unknown rarity goes last.
 */
fun filterAccessorySlots(items: List<BlobItem?>, rarity: String, query: String): List<Int> {
    val order = MagicalPower.PER_RARITY.keys.toList()
    return items.indices.filter { i ->
        val item = items[i] ?: return@filter false
        (rarity == "ALL" || item.rarity == rarity) && (query.isEmpty() || item.plainName.lowercase(Locale.ROOT).contains(query))
    }.sortedBy { i -> order.indexOf(items[i]!!.rarity).let { if (it < 0) order.size else it } }
}

/**
 * Magical Power of an accessory bag, the way the game adds it up: one value per accessory by its rarity,
 * the Hegemony Artifact counted twice, the Rift Prism and the Abiphone contacts. Per-rarity values are the
 * numbers on the SkyBlock wiki's Magical Power page (game facts, not copied code).
 *
 * Accessories sharing an id count once. The game also lets only the best of an upgrade family count (a
 * Wolf Ring line); no family table is bundled, so such families are not collapsed and the sum can exceed what
 * Hypixel reports in `highest_magical_power`.
 */
object MagicalPower {
    /** Rarity to Magical Power, strongest first (the breakdown's row order). */
    val PER_RARITY: Map<String, Int> = linkedMapOf(
        "MYTHIC" to 22, "LEGENDARY" to 16, "EPIC" to 12, "RARE" to 8,
        "UNCOMMON" to 5, "COMMON" to 3, "SPECIAL" to 3, "VERY SPECIAL" to 5,
    )

    const val RIFT_PRISM = 11
    private const val HEGEMONY = "HEGEMONY_ARTIFACT"

    /**
     * One breakdown row. [each] is the per-accessory value for a rarity row (drawn `count × each`) and null
     * for the extra rows (Hegemony Artifact, Rift Prism, Abiphone contacts).
     */
    class Row(val label: String, val rarity: String?, val count: Int, val each: Int?, val power: Int)

    class Breakdown(val rows: List<Row>, val duplicates: Int) {
        val total: Int get() = rows.sumOf { it.power }
    }

    private fun value(item: BlobItem): Int = item.rarity?.let { PER_RARITY[it] } ?: 0

    /** [items] with later copies of the same id dropped (the highest rarity of a repeated id stays). */
    fun distinct(items: List<BlobItem>): List<BlobItem> {
        val best = LinkedHashMap<String, BlobItem>()
        val anonymous = ArrayList<BlobItem>()
        for (item in items) {
            val id = item.id
            if (id == null) {
                anonymous += item
                continue
            }
            val prev = best[id]
            if (prev == null || value(item) > value(prev)) best[id] = item
        }
        return best.values.toList() + anonymous
    }

    fun breakdown(items: List<BlobItem>, prism: Boolean, abiphoneContacts: Int): Breakdown {
        val counted = distinct(items)
        val rows = ArrayList<Row>()
        for ((rarity, each) in PER_RARITY) {
            val n = counted.count { it.rarity == rarity }
            if (n > 0) rows += Row(rarityLabel(rarity), rarity, n, each, n * each)
        }
        counted.firstOrNull { it.id == HEGEMONY }?.let { rows += Row("Hegemony Artifact", null, 1, null, value(it)) }
        if (prism) rows += Row("Rift Prism", null, 1, null, RIFT_PRISM)
        if (abiphoneContacts > 0) rows += Row("Abiphone contacts", null, abiphoneContacts, null, abiphoneContacts / 2)
        return Breakdown(rows, items.size - counted.size)
    }

    /** `VERY SPECIAL` becomes `Very Special`. */
    fun rarityLabel(rarity: String): String = powerDisplayName(rarity)
}
