package com.soulreturns.data.hypixel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// The Mining section's reads of a member (Profile Viewer). Absent means null, never zero.
// Paths checked against real `/skyblock/profiles` member dumps (tier 6, 9 and 10 trees).

private fun JsonObject.longAt(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.doubleAt(key: String): Double? = prim(key)?.doubleOrNull

/** The member's HOTM half of `skill_tree`, null when the response has no `skill_tree.nodes.mining`. */
class HotmData(
    /** `skill_tree.nodes.mining.<id>` levels, `toggle_*` keys excluded. */
    val levels: Map<String, Int>,
    /** Ids with `toggle_<id>: false`. */
    val disabled: Set<String>,
    /** `skill_tree.selected_ability.mining`. */
    val ability: String?,
    /** `skill_tree.experience.mining` (0 when absent but the tree exists). */
    val xp: Double,
    /** `skill_tree.tokens_spent.mountain`. */
    val tokensSpent: Int?,
)

val SkyblockProfileMember.hotm: HotmData?
    get() {
        val tree = raw.obj("skill_tree") ?: return null
        val mining = tree.path("nodes", "mining") ?: return null
        val levels = LinkedHashMap<String, Int>()
        val disabled = HashSet<String>()
        for ((key, value) in mining) {
            val p = value as? JsonPrimitive ?: continue
            if (key.startsWith(TOGGLE)) {
                if (p.booleanOrNull == false) disabled += key.removePrefix(TOGGLE)
            } else {
                (p.longOrNull ?: p.doubleOrNull?.toLong())?.let { levels[key] = it.toInt() }
            }
        }
        return HotmData(
            levels = levels,
            disabled = disabled,
            ability = tree.path("selected_ability")?.string("mining"),
            xp = tree.path("experience")?.doubleAt("mining") ?: 0.0,
            tokensSpent = tree.path("tokens_spent")?.longAt("mountain")?.toInt(),
        )
    }

private const val TOGGLE = "toggle_"

/** One powder: what the player holds and what went into the tree. */
class PowderAmount(val current: Long, val spent: Long) {
    val total: Long get() = current + spent
}

/** `mining_core.powder_<id>` and `powder_spent_<id>`; null when neither is in the response. */
fun SkyblockProfileMember.powder(id: String): PowderAmount? {
    val core = raw.obj("mining_core") ?: return null
    val current = core.longAt("powder_$id")
    val spent = core.longAt("powder_spent_$id")
    if (current == null && spent == null) return null
    return PowderAmount(current ?: 0, spent ?: 0)
}

/** One Crystal Nucleus crystal: `mining_core.crystals.<gem>_crystal` `{state, total_found, total_placed}`. */
class NucleusCrystal(val gem: String, val state: String?, val found: Long?, val placed: Long?)

/** The five Crystal Nucleus slots, in the nucleus's order. */
val NUCLEUS_GEMS: List<String> = listOf("jade", "amber", "amethyst", "sapphire", "topaz")

/** The Nucleus crystals in [NUCLEUS_GEMS] order; null when `mining_core.crystals` is absent. */
val SkyblockProfileMember.nucleusCrystals: List<NucleusCrystal>?
    get() {
        val crystals = raw.path("mining_core", "crystals") ?: return null
        return NUCLEUS_GEMS.map { gem ->
            val o = crystals.obj("${gem}_crystal")
            NucleusCrystal(gem, o?.string("state"), o?.longAt("total_found"), o?.longAt("total_placed"))
        }
    }

/** `glacite_player_data`: mineshafts entered, corpses looted per type, fossils donated, fossil dust. */
class GlaciteData(
    val mineshaftsEntered: Long?,
    /** Type (`lapis`, `umber`, …, as the API names them) to count, in the API's order. */
    val corpsesLooted: Map<String, Long>,
    /** `fossils_donated` is the list of donated fossil ids. */
    val fossilsDonated: Int?,
    val fossilDust: Double?,
)

val SkyblockProfileMember.glacite: GlaciteData?
    get() {
        val g = raw.obj("glacite_player_data") ?: return null
        val corpses = LinkedHashMap<String, Long>()
        g.obj("corpses_looted")?.forEach { (type, v) ->
            (v as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }?.let { corpses[type] = it }
        }
        return GlaciteData(
            mineshaftsEntered = g.longAt("mineshafts_entered"),
            corpsesLooted = corpses,
            fossilsDonated = (g["fossils_donated"] as? JsonArray)?.count { (it as? JsonPrimitive)?.contentOrNull != null },
            fossilDust = g.doubleAt("fossil_dust"),
        )
    }
