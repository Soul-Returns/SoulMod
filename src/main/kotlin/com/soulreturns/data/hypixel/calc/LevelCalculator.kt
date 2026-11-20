package com.soulreturns.data.hypixel.calc

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw Hypixel experience into a level plus progress, against any cumulative curve in
 * [XpTables]. One implementation for Catacombs, dungeon classes, skills and slayers — the legacy
 * mod had this only for dungeons, and the shape generalises exactly.
 */
object LevelCalculator {
    /**
     * The level [experience] buys on a cumulative curve.
     *
     * @param table cumulative XP totals indexed by `level - 1` (entry 0 is the total needed for
     *   level 1). Must be non-empty.
     * @param cap the highest level reachable. Entries beyond it are ignored, which is how Master
     *   Catacombs (cap 7) and a Farming account without Jacob's perks (cap 50) reuse a longer curve.
     */
    fun level(experience: Double, table: LongArray, cap: Int): LevelInfo {
        require(table.isNotEmpty()) { "cumulative XP table must not be empty" }
        val effectiveCap = cap.coerceIn(1, table.size)
        if (experience <= 0) return LevelInfo(0, 0.0, 0L, table[0])

        var level = 0
        for (i in 0 until min(effectiveCap, table.size)) {
            if (experience >= table[i]) level = i + 1 else break
        }
        // Maxed: no next boundary exists, so progress is 1 and both bounds are the last boundary.
        // Reporting it this way (rather than a null next level) keeps every caller's arithmetic
        // total — a progress bar at the cap draws full instead of dividing by zero.
        if (level >= effectiveCap) {
            val boundary = table[effectiveCap - 1]
            return LevelInfo(effectiveCap, 1.0, boundary, boundary)
        }

        val currentBoundary = if (level == 0) 0L else table[level - 1]
        val nextBoundary = table[level]
        val span = max(1L, nextBoundary - currentBoundary)
        val into = (experience - currentBoundary).coerceAtLeast(0.0)
        return LevelInfo(level, (into / span).coerceIn(0.0, 1.0), currentBoundary, nextBoundary)
    }

    /** `1:23.45`, or `—` for a missing/zero duration. Locale-independent by design. */
    fun formatTime(ms: Long): String {
        if (ms <= 0L) return "—"
        val totalSeconds = ms / 1000.0
        val minutes = (totalSeconds / 60).toInt()
        val seconds = totalSeconds - (minutes * 60)
        return String.format(Locale.ROOT, "%d:%05.2f", minutes, seconds)
    }

    /** `1.23M` / `45.6k` / `789`. Locale-independent by design. */
    fun formatXp(experience: Double): String {
        val value = experience.toLong()
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0)
        if (value >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0)
        return value.toString()
    }
}

/**
 * A level and where inside it [progress] sits.
 *
 * @property level 0 for "no experience at all", up to the curve's cap.
 * @property progress 0.0..1.0 through the current level; exactly 1.0 at the cap.
 * @property currentLevelXp cumulative XP at which the current level started.
 * @property nextLevelXp cumulative XP at which the next level starts; equals [currentLevelXp] at
 *   the cap, where there is no next level.
 */
data class LevelInfo(
    val level: Int,
    val progress: Double,
    val currentLevelXp: Long,
    val nextLevelXp: Long,
) {
    fun progressPct(): Double = progress * 100.0

    /** True when there is no next level to climb. */
    val maxed: Boolean get() = nextLevelXp == currentLevelXp

    /** XP span of the current level; 0 at the cap. */
    val levelSpanXp: Long get() = nextLevelXp - currentLevelXp

    /** `12/50` style: how far into the current level the given total sits. */
    fun xpIntoLevel(experience: Double): Long =
        (experience - currentLevelXp).toLong().coerceIn(0L, max(0L, levelSpanXp))

    /** `Lv 34 (72.4%)`, or `Lv 50 (max)` at the cap. */
    fun describe(): String =
        if (maxed) {
            String.format(Locale.ROOT, "Lv %d (max)", level)
        } else {
            String.format(Locale.ROOT, "Lv %d (%.1f%%)", level, progressPct())
        }
}

/** Dungeon floor display names: `E`, `F1`..`F7` and `M1`..`M7`. */
fun normalFloorName(tier: Int): String = if (tier == 0) "E" else "F$tier"

fun masterFloorName(tier: Int): String = "M$tier"

/**
 * The five dungeon classes, in the order Hypixel's own class-level display uses. Keys are the
 * `member.dungeons.player_classes` keys (`berserk` on the wire); names as Hypixel's class menu shows them.
 */
object DungeonClassNames {
    val ORDER = listOf("healer", "mage", "berserk", "archer", "tank")

    /** `when (String)` is safe — the banned form is a subject `when` over an enum/sealed type. */
    fun displayName(key: String): String =
        when (key) {
            "healer" -> "Healer"
            "mage" -> "Mage"
            "berserk" -> "Berserk"
            "archer" -> "Archer"
            "tank" -> "Tank"
            else -> key.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }
}
