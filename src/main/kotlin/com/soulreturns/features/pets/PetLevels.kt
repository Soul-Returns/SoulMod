package com.soulreturns.features.pets

import com.soulreturns.data.skyblock.SkyblockRarity

/**
 * Pet level math, ported 1:1 from the mock kit (`tools/mockkit/data/skyblock.js`, spec `mocks/pet-hud/`).
 *
 * Table: NEU `constants/pets.json` via SkyHelper-Networth `constants/pets.js` (identical in SkyHanni and
 * Skyblocker). `LEVELS[offset + L - 1]` is the XP from level L to L + 1. Rarity offsets: COMMON 0,
 * UNCOMMON 6, RARE 11, EPIC 16, LEGENDARY/MYTHIC 20; anything else (SPECIAL is 16 in SkyHelper and 20
 * in SkyHanni, unresolved) takes 20 like the mock.
 *
 * Golden Dragon (Jade / Rose Dragon too) runs to 200: an egg until 100, 100 → 101 is free (the hatch),
 * 101 → 102 costs 5,555, every later level 1,886,700; total at 200 = 210,255,385 (Skyblocker LevelFinder,
 * SkyHanni LEVEL_200_LEGENDARY; captures bound it between 203.5M and 215.4M, docs/research/pet-hud-2026-11-09.md §5).
 */
object PetLevels {
    private val LEVELS = longArrayOf(
        100, 110, 120, 130, 145, 160, 175, 190, 210, 230, 250, 275, 300, 330, 360, 400, 440, 490, 540, 600, 660, 730, 800, 880, 960, 1050, 1150, 1260, 1380, 1510, 1650, 1800,
        1960, 2130, 2310, 2500, 2700, 2920, 3160, 3420, 3700, 4000, 4350, 4750, 5200, 5700, 6300, 7000, 7800, 8700, 9700, 10800, 12000, 13300, 14700, 16200, 17800, 19500,
        21300, 23200, 25200, 27400, 29800, 32400, 35200, 38200, 41400, 44800, 48400, 52200, 56200, 60400, 64800, 69400, 74200, 79200, 84700, 90700, 97200, 104200, 111700,
        119700, 128200, 137200, 146700, 156700, 167700, 179700, 192700, 206700, 221700, 237700, 254700, 272700, 291700, 311700, 333700, 357700, 383700, 411700, 441700,
        476700, 516700, 561700, 611700, 666700, 726700, 791700, 861700, 936700, 1016700, 1101700, 1191700, 1286700, 1386700, 1496700, 1616700, 1746700, 1886700,
    )

    private const val GD_HATCH = 5555L
    private const val GD_STEP = 1886700L

    /** Pets that run to level 200, by display name or Hypixel type id. */
    private val LEVEL_200 = setOf("golden dragon", "jade dragon", "rose dragon", "golden_dragon", "jade_dragon", "rose_dragon")

    fun offset(rarity: SkyblockRarity?): Int =
        if (rarity == SkyblockRarity.COMMON) {
            0
        } else if (rarity == SkyblockRarity.UNCOMMON) {
            6
        } else if (rarity == SkyblockRarity.RARE) {
            11
        } else if (rarity == SkyblockRarity.EPIC) {
            16
        } else {
            20
        }

    fun maxLevel(name: String): Int = if (name.lowercase() in LEVEL_200) 200 else 100

    /** XP needed to go from [level] to level + 1 (0 at max). */
    fun need(level: Int, rarity: SkyblockRarity?, name: String): Long {
        if (level >= maxLevel(name)) return 0
        if (level >= 100) return if (level == 100) 0 else if (level == 101) GD_HATCH else GD_STEP
        val i = (offset(rarity) + level - 1).coerceIn(0, LEVELS.size - 1)
        return LEVELS[i]
    }

    /** Total XP at the start of [level]. Legendary 100 = 25,353,230; Golden Dragon 200 = 210,255,385. */
    fun levelXp(level: Int, rarity: SkyblockRarity?, name: String): Long {
        var t = 0L
        for (l in 1 until level) t += need(l, rarity, name)
        return t
    }

    /** Where [total] XP puts a pet. */
    data class Progress(val level: Int, val into: Double, val need: Long, val maxed: Boolean, val overflow: Double)

    /** Level from total XP: into/need within the level, overflow past max. */
    fun levelOf(total: Double, rarity: SkyblockRarity?, name: String): Progress {
        val max = maxLevel(name)
        var level = 1
        var rest = total
        while (level < max) {
            val n = need(level, rarity, name)
            if (rest < n) return Progress(level, rest, n, false, 0.0)
            rest -= n
            level++
        }
        return Progress(max, 0.0, 0, true, rest)
    }

    /** Golden Dragon is an egg below 100 (Skyblocker hardcodes "Hatches at level 100"). */
    fun isEgg(name: String, level: Int): Boolean = maxLevel(name) == 200 && level < 100
}
