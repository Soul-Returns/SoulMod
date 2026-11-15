package com.soulreturns.data.hypixel.calc

/**
 * Hypixel SkyBlock's published XP curves, as **cumulative** totals: entry `i` is the total
 * experience needed to reach level `i + 1`.
 *
 * ### Why these live in code
 * They are static Hypixel constants that move once every few years, they are needed before any
 * network call completes, and a backend catalog for ~250 numbers would cost an endpoint, a
 * migration and an admin page. Revisit only if Hypixel starts moving them.
 *
 * ### Provenance — every number here was copied from a source, none was typed from memory
 * - **Skills** — Hypixel's own `GET /v2/resources/skyblock/skills` (no API key needed), which
 *   publishes `levels[].totalExpRequired` per skill. Fetched 2026-08-19, data version 0.27.0.15
 *   (`lastUpdated` 1787063849586). Cross-checked: the deltas of SkyCrypt's `LEVELING_XP` sum to
 *   exactly this table for levels 1..60. Using the official resource rather than a community mirror
 *   is what caught three things a from-memory or mirrored table would have got wrong: **Foraging now
 *   caps at 57**, not 50; **Hunting exists** as a skill the legacy mod never knew; and **SkyCrypt's
 *   `RUNECRAFTING_XP` is stale**, diverging from Hypixel's own totals from level 14 upward (7360
 *   against 7325, drifting to -150 by level 25). Where the two disagree Hypixel wins — so refresh
 *   these by refetching the resource, never by copying a community table.
 * - **Slayers** — SkyCrypt's `SLAYER_XP` (`src/constants/leveling.js`), because Hypixel publishes
 *   no slayer resource (`/v2/resources/skyblock/slayers` → `Unknown resource provided`). Those
 *   values are already cumulative totals, confirmed by how SkyCrypt itself consumes them
 *   (`progress = xp / nextLevel` against the raw total, `src/stats/slayer.js`).
 * - **Catacombs / dungeon classes** — copied verbatim from the legacy mod's `DungeonsCalculator`,
 *   which held hand-verified SkyCrypt/SkyHelper deltas. Cross-checked against SkyCrypt's current
 *   `DUNGEONEERING_XP`: identical for levels 1..50, and the level-50 total comes out at the
 *   published 569,809,640.
 *
 * ### Caps
 * [XpCurve.cap] is the **maximum level the curve publishes**. Two skills have an account-specific
 * *unlock* cap below it that this layer deliberately does not model: **Farming**'s comes from Jacob's
 * Contest perks and **Taming**'s from the unique pet types sacrificed to Kat. Both formulas live on
 * the backend (`GET /player/{uuid}/skyblock-caps`, see
 * [com.soulreturns.data.hypixel.SkillCapsClient]) so that each has exactly one implementation; pass
 * what it answers to [XpCurve.levelCapped]. The table itself stays whole so nothing under-reports.
 */
object XpTables {

    // ------------------------------------------------------------------ skills

    /**
     * The curve every non-cosmetic skill shares. A skill that caps lower simply uses a prefix of
     * it — verified against the official resource, where every skill except Runecrafting and
     * Social is an exact prefix of Farming's 60-level table.
     */
    private val STANDARD = longArrayOf(
        50L, 175L, 375L, 675L, 1_175L, 1_925L,
        2_925L, 4_425L, 6_425L, 9_925L, 14_925L, 22_425L,
        32_425L, 47_425L, 67_425L, 97_425L, 147_425L, 222_425L,
        322_425L, 522_425L, 822_425L, 1_222_425L, 1_722_425L, 2_322_425L,
        3_022_425L, 3_822_425L, 4_722_425L, 5_722_425L, 6_822_425L, 8_022_425L,
        9_322_425L, 10_722_425L, 12_222_425L, 13_822_425L, 15_522_425L, 17_322_425L,
        19_222_425L, 21_222_425L, 23_322_425L, 25_522_425L, 27_822_425L, 30_222_425L,
        32_722_425L, 35_322_425L, 38_072_425L, 40_972_425L, 44_072_425L, 47_472_425L,
        51_172_425L, 55_172_425L, 59_472_425L, 64_072_425L, 68_972_425L, 74_172_425L,
        79_672_425L, 85_472_425L, 91_572_425L, 97_972_425L, 104_672_425L, 111_672_425L,
    )

    private val RUNECRAFTING_CURVE = longArrayOf(
        50L, 150L, 275L, 435L, 635L,
        885L, 1_200L, 1_600L, 2_100L, 2_725L,
        3_510L, 4_510L, 5_760L, 7_325L, 9_325L,
        11_825L, 14_950L, 18_950L, 23_950L, 30_200L,
        38_050L, 47_850L, 60_100L, 75_400L, 94_450L,
    )

    /** Social is co-op-wide on Hypixel's side; the curve is per-profile all the same. */
    private val SOCIAL_CURVE = longArrayOf(
        50L, 150L, 300L, 550L, 1_050L,
        1_800L, 2_800L, 4_050L, 5_550L, 7_550L,
        10_050L, 13_050L, 16_800L, 21_300L, 27_300L,
        35_300L, 45_300L, 57_800L, 72_800L, 92_800L,
        117_800L, 147_800L, 182_800L, 222_800L, 272_800L,
    )

    /**
     * Every skill Hypixel publishes, in the order the Profile Viewer shows them. Keyed by the
     * lowercase name; [SkillDef.apiField] is the key inside `member.player_data.experience`.
     */
    val skills: List<SkillDef> = listOf(
        SkillDef("farming", "Farming", "SKILL_FARMING", XpCurve(STANDARD, 60), cosmetic = false),
        SkillDef("mining", "Mining", "SKILL_MINING", XpCurve(STANDARD, 60), cosmetic = false),
        SkillDef("combat", "Combat", "SKILL_COMBAT", XpCurve(STANDARD, 60), cosmetic = false),
        SkillDef("foraging", "Foraging", "SKILL_FORAGING", XpCurve(STANDARD, 57), cosmetic = false),
        SkillDef("fishing", "Fishing", "SKILL_FISHING", XpCurve(STANDARD, 50), cosmetic = false),
        SkillDef("enchanting", "Enchanting", "SKILL_ENCHANTING", XpCurve(STANDARD, 60), cosmetic = false),
        SkillDef("alchemy", "Alchemy", "SKILL_ALCHEMY", XpCurve(STANDARD, 50), cosmetic = false),
        SkillDef("taming", "Taming", "SKILL_TAMING", XpCurve(STANDARD, 60), cosmetic = false),
        SkillDef("carpentry", "Carpentry", "SKILL_CARPENTRY", XpCurve(STANDARD, 50), cosmetic = false),
        SkillDef("hunting", "Hunting", "SKILL_HUNTING", XpCurve(STANDARD, 50), cosmetic = false),
        SkillDef("runecrafting", "Runecrafting", "SKILL_RUNECRAFTING", XpCurve(RUNECRAFTING_CURVE, 25), cosmetic = true),
        SkillDef("social", "Social", "SKILL_SOCIAL", XpCurve(SOCIAL_CURVE, 25), cosmetic = true),
    )

    /** The skills that count toward Skill Average — Runecrafting and Social do not. */
    val nonCosmeticSkills: List<SkillDef> = skills.filter { !it.cosmetic }

    fun skill(key: String): SkillDef? = skills.firstOrNull { it.key == key }

    // ------------------------------------------------------------------ slayers

    private val ZOMBIE_TABLE = longArrayOf(
        5L, 15L, 200L, 1_000L, 5_000L, 20_000L, 100_000L, 400_000L, 1_000_000L,
    )
    private val SPIDER_TABLE = longArrayOf(
        5L, 25L, 200L, 1_000L, 5_000L, 20_000L, 100_000L, 400_000L, 1_000_000L,
    )
    private val WOLF_TABLE = longArrayOf(
        10L, 30L, 250L, 1_500L, 5_000L, 20_000L, 100_000L, 400_000L, 1_000_000L,
    )
    private val ENDERMAN_TABLE = longArrayOf(
        10L, 30L, 250L, 1_500L, 5_000L, 20_000L, 100_000L, 400_000L, 1_000_000L,
    )
    private val BLAZE_TABLE = longArrayOf(
        10L, 30L, 250L, 1_500L, 5_000L, 20_000L, 100_000L, 400_000L, 1_000_000L,
    )
    private val VAMPIRE_TABLE = longArrayOf(
        20L, 75L, 240L, 840L, 2_400L,
    )

    /** Every slayer Hypixel has, keyed by the name used in `member.slayer.slayer_bosses`. */
    val slayers: List<SlayerDef> = listOf(
        SlayerDef("zombie", "Revenant Horror", XpCurve(ZOMBIE_TABLE, 9)),
        SlayerDef("spider", "Tarantula Broodfather", XpCurve(SPIDER_TABLE, 9)),
        SlayerDef("wolf", "Sven Packmaster", XpCurve(WOLF_TABLE, 9)),
        SlayerDef("enderman", "Voidgloom Seraph", XpCurve(ENDERMAN_TABLE, 9)),
        SlayerDef("blaze", "Inferno Demonlord", XpCurve(BLAZE_TABLE, 9)),
        SlayerDef("vampire", "Riftstalker Bloodfiend", XpCurve(VAMPIRE_TABLE, 5)),
    )

    fun slayer(key: String): SlayerDef? = slayers.firstOrNull { it.key == key }

    // ------------------------------------------------------------------ dungeons

    /** Catacombs, levels 1..50. Dungeon classes use the same curve. */
    val CATACOMBS = XpCurve(
        longArrayOf(
            50L, 125L, 235L, 395L, 625L,
            955L, 1_425L, 2_095L, 3_045L, 4_385L,
            6_275L, 8_940L, 12_700L, 17_960L, 25_340L,
            35_640L, 50_040L, 70_040L, 97_640L, 135_640L,
            188_140L, 259_640L, 356_640L, 488_640L, 668_640L,
            911_640L, 1_239_640L, 1_684_640L, 2_284_640L, 3_084_640L,
            4_149_640L, 5_559_640L, 7_459_640L, 9_959_640L, 13_259_640L,
            17_559_640L, 23_159_640L, 30_359_640L, 39_559_640L, 51_559_640L,
            66_559_640L, 85_559_640L, 109_559_640L, 139_559_640L, 177_559_640L,
            225_559_640L, 285_559_640L, 360_559_640L, 453_559_640L, 569_809_640L,
        ),
        cap = 50,
    )

    /** Dungeon classes (Healer, Mage, Berserker, Archer, Tank) — the Catacombs curve, cap 50. */
    val DUNGEON_CLASS = CATACOMBS

    /** Master Catacombs caps at 7 and reuses the Catacombs curve for levels 1..7. */
    val MASTER_CATACOMBS = XpCurve(CATACOMBS.cumulative, cap = 7)
}

/**
 * One XP curve: cumulative totals plus the highest level it publishes.
 *
 * Not a `data class` — it holds a `LongArray`, whose generated `equals`/`hashCode` would compare by
 * identity and silently make two equal curves unequal.
 */
class XpCurve(internal val cumulative: LongArray, val cap: Int) {
    /** The level [experience] buys on this curve. */
    fun level(experience: Double): LevelInfo = LevelCalculator.level(experience, cumulative, cap)

    /**
     * As [level], but with a lower ceiling — for a skill whose account-specific unlock cap is below
     * what the curve publishes (Farming without Jacob's perks, Taming without the achievement).
     */
    fun levelCapped(experience: Double, cap: Int): LevelInfo =
        LevelCalculator.level(experience, cumulative, cap.coerceIn(1, this.cap))
}

/**
 * A Hypixel skill. [apiField] is its key inside `member.player_data.experience`
 * (`SKILL_FARMING`, …); [cosmetic] marks the two that do not count toward Skill Average.
 */
data class SkillDef(
    val key: String,
    val displayName: String,
    val apiField: String,
    val curve: XpCurve,
    val cosmetic: Boolean,
)

/**
 * A slayer. [key] is the key inside `member.slayer.slayer_bosses`; [bossName] is the boss's
 * in-game name, which is what players actually call it ("Revenant Horror", not "zombie").
 */
data class SlayerDef(
    val key: String,
    val bossName: String,
    val curve: XpCurve,
)
