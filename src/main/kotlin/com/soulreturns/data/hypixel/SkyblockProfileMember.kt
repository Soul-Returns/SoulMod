package com.soulreturns.data.hypixel

import com.soulreturns.data.hypixel.calc.DungeonClassNames
import com.soulreturns.data.hypixel.calc.LevelInfo
import com.soulreturns.data.hypixel.calc.SkillDef
import com.soulreturns.data.hypixel.calc.SlayerDef
import com.soulreturns.data.hypixel.calc.XpTables
import com.soulreturns.data.hypixel.calc.masterFloorName
import com.soulreturns.data.hypixel.calc.normalFloorName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A typed **view** over one `members[<undashed-uuid>]` object — the largest and most volatile part of
 * the Hypixel response, kept raw and read through accessors rather than modelled field by field (see
 * [hypixelJson] for why).
 *
 * Every accessor tolerates absence, because absence is normal here:
 * - a player who has never entered a dungeon has no `dungeons` key at all;
 * - a player who switched **API access off in game** has no `inventory.inv_contents`, which is a
 *   normal state and not an error — and `inventory.sacks_counts` frequently survives it, so the two
 *   are checked separately ([apiAccessDisabled] vs [sacks]);
 * - a brand-new profile has no `player_data.experience` and therefore no skills.
 *
 * Wire paths were verified against SkyCrypt's live parser rather than assumed: skills live at
 * `player_data.experience.SKILL_*` (not the pre-2023 `experience_skill_*`) and slayer XP at
 * `slayer.slayer_bosses.<name>.xp`.
 */
class SkyblockProfileMember(val raw: JsonObject) {

    private companion object {
        /** SkyBlock Level is flat: one level per 100 XP, no curve to look up. */
        const val XP_PER_SKYBLOCK_LEVEL = 100.0

        /**
         * The sibling maps under `dungeon_types.<branch>` that are keyed by tier. Used to discover
         * which floors a profile has data for, so the table is not limited to the tiers this build
         * happened to know about.
         */
        val PER_TIER_MAPS = listOf(
            "tier_completions",
            "best_score",
            "fastest_time",
            "fastest_time_s",
            "fastest_time_s_plus",
            "times_played",
        )
    }

    // ------------------------------------------------------------------ API access

    /**
     * True when this member's inventory is hidden because they turned API access off in game.
     *
     * Deliberately *not* a general "no data" flag: `sacks_counts` is a separate switch on Hypixel's
     * side and is often still present. Render a note, then show everything else that did arrive.
     */
    val apiAccessDisabled: Boolean
        get() = raw.path("inventory")?.containsKey("inv_contents") != true

    // ------------------------------------------------------------------ SkyBlock Level

    /**
     * **SkyBlock Level** — `leveling.experience` at a flat 100 XP per level, no curve.
     *
     * This is the number players actually quote for "how far along is this account": it moves with
     * every part of the game at once, unlike Skill Average (skills only) or Catacombs (dungeons only),
     * which is why it leads the Profile Viewer.
     *
     * Null when `leveling` is absent — a profile untouched since before the system existed, or one
     * whose owner has API access off. Zero would claim level 0, which is a real level.
     */
    val skyblockLevel: LevelInfo?
        get() {
            val xp = raw.path("leveling")?.prim("experience")?.doubleOrNull?.coerceAtLeast(0.0) ?: return null
            val level = (xp / XP_PER_SKYBLOCK_LEVEL).toInt()
            return LevelInfo(
                level = level,
                progress = ((xp % XP_PER_SKYBLOCK_LEVEL) / XP_PER_SKYBLOCK_LEVEL).coerceIn(0.0, 1.0),
                currentLevelXp = (level * XP_PER_SKYBLOCK_LEVEL).toLong(),
                nextLevelXp = ((level + 1) * XP_PER_SKYBLOCK_LEVEL).toLong(),
            )
        }

    /** Raw SkyBlock XP behind [skyblockLevel]. */
    val skyblockExperience: Double
        get() = raw.path("leveling")?.double("experience") ?: 0.0

    // ------------------------------------------------------------------ skills

    /**
     * Every skill this member has XP in, in [XpTables.skills] order. A skill Hypixel has no entry
     * for is omitted rather than reported as level 0 — "not played" and "played to level 0" look
     * identical in the response, and claiming the second would be inventing data.
     */
    val skills: List<SkillProgress> get() = skillsCapped(null)

    /**
     * [skills], with the account-specific ceilings applied — see [effectiveCap].
     *
     * @param caps what `GET /player/{uuid}/skyblock-caps` answered, or null when it could not be
     *   asked. Null is not an error: every skill then uses the cap it can be given from this response
     *   alone, which is what the mod did before that endpoint existed.
     */
    fun skillsCapped(caps: SkillCaps?): List<SkillProgress> {
        val experience = raw.path("player_data", "experience") ?: return emptyList()
        return XpTables.skills.mapNotNull { def ->
            val xp = experience.prim(def.apiField)?.doubleOrNull ?: return@mapNotNull null
            SkillProgress(def, xp, def.curve.levelCapped(xp, effectiveCap(def, caps)))
        }
    }

    /**
     * The ceiling to level [def] against: whatever [caps] says, else the curve's published cap.
     *
     * Two skills cap below their curve — **Farming** (Jacob's Contest perks) and **Taming** (unique pet
     * types sacrificed to Kat) — and **this class deliberately computes neither**. Both formulas live
     * on the backend, behind `GET /player/{uuid}/skyblock-caps`, so there is exactly one implementation
     * of each and a wrong one can be corrected without shipping a mod. That is not hypothetical: the
     * Taming rule was first specified against an achievement key that does not exist.
     *
     * With no [caps] the skill levels against its published cap, which over-states an account holding
     * the XP without the unlock. That is the pre-endpoint behaviour and the safe direction to fail in.
     */
    fun effectiveCap(def: SkillDef, caps: SkillCaps? = null): Int =
        (caps?.capFor(def.key) ?: def.curve.cap).coerceIn(1, def.curve.cap)

    /**
     * Skill Average over the non-cosmetic skills **that are present**, progress included (the way
     * SkyCrypt computes it). Null when no skill data arrived at all.
     *
     * Averaging only over present skills rather than over a fixed divisor means a profile missing a
     * skill Hypixel added recently reads slightly high rather than wrong-and-silent; [skillCount]
     * is exposed so a renderer can say what the average was taken over.
     */
    val skillAverage: Double? get() = skillAverageCapped(null)

    /** [skillAverage], with the account-specific ceilings applied — see [effectiveCap]. */
    fun skillAverageCapped(caps: SkillCaps?): Double? {
        val counted = skillsCapped(caps).filter { !it.def.cosmetic }
        if (counted.isEmpty()) return null
        return counted.sumOf { it.level.level + it.level.progress } / counted.size
    }

    /** How many non-cosmetic skills [skillAverage] was taken over. */
    val skillCount: Int get() = skills.count { !it.def.cosmetic }

    // ------------------------------------------------------------------ slayers

    /** Every slayer this member has XP in, in [XpTables.slayers] order. */
    val slayers: List<SlayerProgress>
        get() {
            val bosses = raw.path("slayer", "slayer_bosses") ?: return emptyList()
            return XpTables.slayers.mapNotNull { def ->
                val boss = bosses.obj(def.key) ?: return@mapNotNull null
                val xp = boss.double("xp", fallback = 0.0)
                // Tiers are `boss_kills_tier_0`..`_4` (displayed I..V) and a tier never killed has
                // no key, so 0 here means "none", not "no data".
                val kills = (0..4).map { boss.long("boss_kills_tier_$it").toInt() }
                SlayerProgress(def, xp, def.curve.level(xp), kills)
            }
        }

    /** Total slayer XP across every boss — the number players quote as "slayer XP". */
    val totalSlayerXp: Double get() = slayers.sumOf { it.experience }

    // ------------------------------------------------------------------ dungeons

    /** True when this member has any dungeon data at all. */
    val hasDungeons: Boolean get() = raw.path("dungeons") != null

    val catacombsExperience: Double
        get() = raw.path("dungeons", "dungeon_types", "catacombs")?.double("experience") ?: 0.0

    val catacombsLevel: LevelInfo get() = XpTables.CATACOMBS.level(catacombsExperience)

    val masterCatacombsExperience: Double
        get() = raw.path("dungeons", "dungeon_types", "master_catacombs")?.double("experience") ?: 0.0

    /**
     * The level [masterCatacombsExperience] would buy on its own curve.
     *
     * **Do not show this to a player.** Hypixel tracks the XP, but Master Mode has no separate level
     * in game — it shares the ordinary Catacombs level, so a "Master Catacombs Lv 0" line next to
     * "Catacombs Lv 50" is not a fact about the account, it is an artifact of the response. Kept only
     * because the field is real and a future comparison might want it; the per-floor Master tables
     * ([masterCatacombsFloors]) are the Master data that *is* meaningful.
     */
    val masterCatacombsLevel: LevelInfo get() = XpTables.MASTER_CATACOMBS.level(masterCatacombsExperience)

    /** The class Hypixel says they are playing (`healer`, `mage`, …), or null. */
    val selectedDungeonClass: String?
        get() = raw.path("dungeons")?.string("selected_dungeon_class")?.takeIf { it.isNotBlank() }

    /**
     * The five dungeon classes in [DungeonClassNames.ORDER], always all five — unlike skills, a
     * class with no XP is a meaningful "level 0", and a Dungeons tab with gaps in it would read as
     * broken rather than as unplayed.
     */
    val dungeonClasses: List<DungeonClassProgress>
        get() {
            val classes = raw.path("dungeons", "player_classes")
            return DungeonClassNames.ORDER.map { key ->
                val xp = classes?.obj(key)?.double("experience") ?: 0.0
                DungeonClassProgress(
                    key = key,
                    displayName = DungeonClassNames.displayName(key),
                    experience = xp,
                    level = XpTables.DUNGEON_CLASS.level(xp),
                    selected = key == selectedDungeonClass,
                )
            }
        }

    /**
     * Class Average — the mean of the five class levels, which is what dungeon parties actually
     * gate on.
     */
    val classAverage: Double
        get() = dungeonClasses.sumOf { it.level.level + it.level.progress } / DungeonClassNames.ORDER.size

    /**
     * Per-floor Catacombs stats — Entrance through F7 at least, plus any tier the response knows
     * about that this build does not (see [floorTiers]). An unplayed floor is present and empty, so
     * the tiers read as a scale rather than as a list with holes.
     */
    val catacombsFloors: List<DungeonFloorProgress>
        get() = floorTiers("catacombs", 0..7).map { floor("catacombs", it, normalFloorName(it)) }

    /** Per-floor Master Catacombs stats, M1 through M7 (plus any tier the response adds). */
    val masterCatacombsFloors: List<DungeonFloorProgress>
        get() = floorTiers("master_catacombs", 1..7).map { floor("master_catacombs", it, masterFloorName(it)) }

    /**
     * Which tiers to show for a branch: [known] as the floor, unioned with every numeric key actually
     * present in the branch's per-tier maps.
     *
     * Reading the tier set out of the data rather than hardcoding a range means the day Hypixel adds
     * F8 it appears by itself, and — more importantly — a floor whose key we did not expect can never
     * be silently dropped. The `total` key some accounts carry is skipped, being a sum and not a tier.
     */
    private fun floorTiers(branch: String, known: IntRange): List<Int> {
        val node = raw.path("dungeons", "dungeon_types", branch)
        val tiers = known.toMutableSet()
        if (node != null) {
            for (mapKey in PER_TIER_MAPS) {
                node.obj(mapKey)?.keys?.forEach { key -> key.toIntOrNull()?.let(tiers::add) }
            }
        }
        return tiers.sorted()
    }

    /** Every completed run on this profile, normal and master floors together. */
    val totalDungeonRuns: Int
        get() = catacombsFloors.sumOf { it.completions } + masterCatacombsFloors.sumOf { it.completions }

    /**
     * `dungeons.secrets` — lifetime secrets found.
     *
     * Null rather than zero when absent, and absent it often is: the canonical total lives on
     * `/v2/player`, which this mod never fetches, and Hypixel only mirrors it onto the profile for
     * some accounts. Zero would claim a player has found none.
     */
    val dungeonSecrets: Int?
        get() = raw.path("dungeons")?.prim("secrets")?.longOrNull?.toInt()

    /**
     * One floor's row. Hypixel keys each of these sibling maps by the tier as a **string**
     * (`tier_completions: {"0": 12, "1": 40, …}`), and omits the key entirely for a floor never
     * finished — so every field here is "0 means never" and a renderer shows a dash.
     */
    private fun floor(branch: String, tier: Int, displayName: String): DungeonFloorProgress {
        val node = raw.path("dungeons", "dungeon_types", branch)
        val key = tier.toString()
        return DungeonFloorProgress(
            tier = tier,
            displayName = displayName,
            completions = node?.obj("tier_completions")?.long(key)?.toInt() ?: 0,
            bestScore = node?.obj("best_score")?.long(key)?.toInt() ?: 0,
            fastestTimeMs = node?.obj("fastest_time")?.long(key) ?: 0L,
            fastestSTimeMs = node?.obj("fastest_time_s")?.long(key) ?: 0L,
            fastestSPlusTimeMs = node?.obj("fastest_time_s_plus")?.long(key) ?: 0L,
        )
    }

    // ------------------------------------------------------------------ inventory

    /**
     * `inventory.sacks_counts` — a flat `skyblockItemId → count` map, typically 100–600 entries.
     * Empty when absent; present even for many players who disabled `inv_contents`.
     *
     * This is the one inventory field this lane exposes. A future sacks feature reads it from here
     * (via [com.soulreturns.data.skyblock.SkyblockSelfProfile]) instead of re-fetching.
     */
    val sacks: Map<String, Long>
        get() {
            val counts = raw.path("inventory", "sacks_counts") ?: return emptyMap()
            val out = LinkedHashMap<String, Long>(counts.size)
            for ((key, value) in counts) {
                (value as? JsonPrimitive)?.longOrNull?.let { out[key] = it }
            }
            return out
        }
}

/** A skill plus where its XP puts it. */
data class SkillProgress(val def: SkillDef, val experience: Double, val level: LevelInfo)

/**
 * A slayer plus where its XP puts it, and how many bosses of each tier were killed.
 *
 * @property bossKills five counts, tier I through tier V. Riftstalker Bloodfiend only has I..V worth
 *   of curve but the same five keys, so the shape is uniform and a renderer drops the empty tail.
 */
data class SlayerProgress(
    val def: SlayerDef,
    val experience: Double,
    val level: LevelInfo,
    val bossKills: List<Int> = emptyList(),
) {
    /** Every boss of any tier. */
    val totalKills: Int get() = bossKills.sum()
}

/** One dungeon class plus where its XP puts it. [selected] marks the class currently being played. */
data class DungeonClassProgress(
    val key: String,
    val displayName: String,
    val experience: Double,
    val level: LevelInfo,
    val selected: Boolean = false,
)

/**
 * One dungeon floor's record. A zero in any field means the floor was never finished (or never
 * finished to that grade) — Hypixel simply omits the key, so there is no separate "no data" state.
 */
data class DungeonFloorProgress(
    val tier: Int,
    val displayName: String,
    val completions: Int,
    val bestScore: Int,
    val fastestTimeMs: Long,
    val fastestSTimeMs: Long,
    val fastestSPlusTimeMs: Long,
) {
    val played: Boolean get() = completions > 0 || bestScore > 0 || fastestTimeMs > 0L
}
