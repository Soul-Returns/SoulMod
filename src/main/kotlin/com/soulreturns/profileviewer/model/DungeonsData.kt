package com.soulreturns.profileviewer.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Pulls the `dungeons` block out of a Hypixel SkyBlock member object.
 *
 * Layout (Hypixel v2):
 * member.dungeons = {
 *   dungeon_journal: { ... },
 *   dungeon_types: {
 *     catacombs: {
 *       experience: <double>,
 *       tier_completions: { "0": n, "1": n, ... },
 *       fastest_time: { ... }, fastest_time_s: { ... }, fastest_time_s_plus: { ... },
 *       best_score: { ... },
 *       times_played: { ... },
 *       milestone_completions: { ... },
 *     },
 *     master_catacombs: { ... same shape, tiers 1-7 ... }
 *   },
 *   player_classes: {
 *     healer: { experience }, mage: {...}, berserk: {...}, archer: {...}, tank: {...}
 *   },
 *   selected_dungeon_class: "berserk" | "mage" | ...,
 *   secrets: <int> // Some accounts only — total secrets via /v2/player on Hypixel.
 * }
 */
class DungeonsView(val raw: JsonObject) {

    val catacombsExperience: Double get() = doublePath("dungeon_types/catacombs/experience")
    val masterCatacombsExperience: Double get() = doublePath("dungeon_types/master_catacombs/experience")

    val selectedClass: String? get() = raw["selected_dungeon_class"]?.takeIf { !it.isJsonNull }?.asString

    val classExperience: Map<String, Double>
        get() {
            val pc = raw.getAsJsonObject("player_classes") ?: return emptyMap()
            return pc.entrySet().associate { (k, v) ->
                val xp = (v as? JsonObject)?.get("experience")?.let { e ->
                    if (e.isJsonPrimitive) e.asDouble else 0.0
                } ?: 0.0
                k to xp
            }
        }

    fun normalFloor(tier: Int): FloorStats = floorStats("catacombs", tier)
    fun masterFloor(tier: Int): FloorStats = floorStats("master_catacombs", tier)

    private fun floorStats(branch: String, tier: Int): FloorStats {
        val node = raw.getAsJsonObject("dungeon_types")?.getAsJsonObject(branch)
            ?: return FloorStats.EMPTY
        val key = tier.toString()
        return FloorStats(
            completions = intFrom(node.getAsJsonObject("tier_completions"), key),
            bestScore = intFrom(node.getAsJsonObject("best_score"), key),
            fastestTimeMs = longFrom(node.getAsJsonObject("fastest_time"), key),
            fastestSMs = longFrom(node.getAsJsonObject("fastest_time_s"), key),
            fastestSPlusMs = longFrom(node.getAsJsonObject("fastest_time_s_plus"), key),
        )
    }

    /** Total catacombs runs across all tiers (entrance through F7). */
    val totalCatacombsCompletions: Int
        get() = (0..7).sumOf { normalFloor(it).completions } +
                (1..7).sumOf { masterFloor(it).completions }

    /**
     * Total secrets found, when surfaced on the profile object.
     * Hypixel returns this as `dungeons.secrets` only on some accounts —
     * the canonical total lives on /v2/player. Returns null if unavailable.
     */
    val totalSecrets: Int?
        get() = raw["secrets"]?.takeIf { it.isJsonPrimitive }?.asInt

    private fun doublePath(path: String): Double {
        var cur: JsonElement? = raw
        for (segment in path.split('/')) {
            val obj = cur as? JsonObject ?: return 0.0
            cur = obj[segment] ?: return 0.0
        }
        return if (cur != null && cur.isJsonPrimitive && cur.asJsonPrimitive.isNumber) cur.asDouble else 0.0
    }

    private fun intFrom(obj: JsonObject?, key: String): Int {
        val v = obj?.get(key) ?: return 0
        return if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) v.asInt else 0
    }

    private fun longFrom(obj: JsonObject?, key: String): Long {
        val v = obj?.get(key) ?: return 0L
        return if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) v.asLong else 0L
    }
}

data class FloorStats(
    val completions: Int,
    val bestScore: Int,
    val fastestTimeMs: Long,
    val fastestSMs: Long,
    val fastestSPlusMs: Long,
) {
    companion object {
        val EMPTY = FloorStats(0, 0, 0, 0, 0)
    }

    fun isEmpty() = completions == 0 && bestScore == 0 && fastestTimeMs == 0L
}
