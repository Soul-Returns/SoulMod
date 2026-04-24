package com.soulreturns.profileviewer.service

import com.soulreturns.profileviewer.model.DungeonsView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Hypixel SkyBlock dungeon level / class XP tables (sums of cumulative XP).
 * Sources: SkyCrypt / SkyHelper data (publicly known constants).
 */
object DungeonsCalculator {

    // Catacombs cumulative XP for levels 1..50.
    // Index i = XP required to reach level (i+1) from level 0.
    private val CATACOMBS_XP: LongArray = buildCumulative(
        // Level 1..50 deltas
        50L, 75L, 110L, 160L, 230L, 330L, 470L, 670L, 950L, 1340L,
        1890L, 2665L, 3760L, 5260L, 7380L, 10300L, 14400L, 20000L, 27600L, 38000L,
        52500L, 71500L, 97000L, 132000L, 180000L, 243000L, 328000L, 445000L, 600000L, 800000L,
        1065000L, 1410000L, 1900000L, 2500000L, 3300000L, 4300000L, 5600000L, 7200000L, 9200000L, 12000000L,
        15000000L, 19000000L, 24000000L, 30000000L, 38000000L, 48000000L, 60000000L, 75000000L, 93000000L, 116250000L,
    )

    // Class XP table (same shape as Catacombs, also caps at 50)
    private val CLASS_XP: LongArray = CATACOMBS_XP

    fun catacombsLevel(experience: Double): LevelInfo = level(experience, CATACOMBS_XP, 50)
    fun classLevel(experience: Double): LevelInfo = level(experience, CLASS_XP, 50)

    /**
     * Master Catacombs caps at 7. The XP curve is the same as Catacombs
     * for levels 1..7 in the Hypixel data, then rolls over.
     */
    fun masterCatacombsLevel(experience: Double): LevelInfo {
        val cap = 7
        val sub = LongArray(cap) { CATACOMBS_XP[it] }
        return level(experience, sub, cap)
    }

    fun formatTime(ms: Long): String {
        if (ms <= 0L) return "—"
        val totalSec = ms / 1000.0
        val minutes = (totalSec / 60).toInt()
        val seconds = totalSec - (minutes * 60)
        return String.format(Locale.ROOT, "%d:%05.2f", minutes, seconds)
    }

    fun formatXp(experience: Double): String {
        val v = experience.toLong()
        if (v >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", v / 1_000_000.0)
        if (v >= 1_000) return String.format(Locale.ROOT, "%.1fk", v / 1_000.0)
        return v.toString()
    }

    private fun buildCumulative(vararg deltas: Long): LongArray {
        val arr = LongArray(deltas.size)
        var sum = 0L
        for (i in deltas.indices) {
            sum += deltas[i]
            arr[i] = sum
        }
        return arr
    }

    /**
     * @param table cumulative XP totals indexed by (level - 1)
     * @param cap maximum reachable level
     */
    private fun level(experience: Double, table: LongArray, cap: Int): LevelInfo {
        if (experience <= 0) return LevelInfo(0, 0.0, 0L, table[0])
        var level = 0
        for (i in 0 until min(cap, table.size)) {
            if (experience >= table[i]) level = i + 1 else break
        }
        if (level >= cap) {
            return LevelInfo(cap, 1.0, table[cap - 1], table[cap - 1])
        }
        val curBoundary = if (level == 0) 0L else table[level - 1]
        val nextBoundary = table[level]
        val span = max(1L, nextBoundary - curBoundary)
        val into = (experience - curBoundary).coerceAtLeast(0.0)
        val progress = (into / span).coerceIn(0.0, 1.0)
        return LevelInfo(level, progress, curBoundary, nextBoundary)
    }
}

data class LevelInfo(
    val level: Int,
    val progress: Double,
    val currentLevelXp: Long,
    val nextLevelXp: Long,
) {
    fun progressPct(): Double = progress * 100.0
}

/** Floor display name conventions (Entrance + F1..F7, M1..M7). */
fun normalFloorName(tier: Int): String = if (tier == 0) "E" else "F$tier"
fun masterFloorName(tier: Int): String = "M$tier"

object DungeonClassNames {
    val ORDER = listOf("healer", "mage", "berserk", "archer", "tank")
    fun displayName(key: String): String = when (key) {
        "healer" -> "Healer"
        "mage" -> "Mage"
        "berserk" -> "Berserker"
        "archer" -> "Archer"
        "tank" -> "Tank"
        else -> key.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }
}
