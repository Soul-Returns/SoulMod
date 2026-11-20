package com.soulreturns.features.profileviewer.gui.kit

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Number and time formatting of the mock (`/kit/util.js` `num`/`short`, `sections.js` `pct`/`dur`/`hm`/`ago`/`date`). */
object Format {
    /** `1,234,567` (American English grouping, rounded). */
    fun num(n: Double): String = String.format(Locale.US, "%,d", n.roundToLong())

    fun num(n: Long): String = String.format(Locale.US, "%,d", n)

    fun num(n: Int): String = num(n.toLong())

    /** `1.2k` / `3.4M` / `5.6B` / `7T`: one decimal, a trailing `.0` dropped. */
    fun short(n: Double): String {
        val a = abs(n)
        for ((d, s) in SUFFIXES) {
            if (a >= d) return trim(Math.round(n / d * 10.0) / 10.0) + s
        }
        return n.roundToLong().toString()
    }

    fun short(n: Long): String = short(n.toDouble())

    /** `12.5%`. */
    fun pct(v: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%.${decimals}f%%", v * 100.0)

    /** Two decimals, for averages that sit in a column (`53.60`). */
    fun avg(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

    /** `m:ss` from milliseconds (dungeon times). */
    fun dur(ms: Long): String {
        val s = Math.round(ms / 1000.0)
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** `3 h 12 min` / `48 min` from seconds. */
    fun hm(sec: Long): String {
        val h = sec / 3600
        val m = Math.round((sec % 3600) / 60.0)
        return if (h > 0) "$h h $m min" else "$m min"
    }

    /** `just now` / `5 min ago` / `3 h ago` / `2 days ago`. */
    fun ago(ms: Long): String = when {
        ms < 45_000 -> "just now"
        ms < 3_600_000 -> "${Math.round(ms / 60_000.0)} min ago"
        ms < 48 * 3_600_000L -> "${Math.round(ms / 3_600_000.0)} h ago"
        else -> "${Math.round(ms / 86_400_000.0)} days ago"
    }

    /** `Nov 22, 2021` (UTC). */
    fun date(epochMs: Long): String {
        val d = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneOffset.UTC)
        return "${MONTHS[d.monthValue - 1]} ${d.dayOfMonth}, ${d.year}"
    }

    /** `I`..`X` and beyond, for tiers. */
    fun roman(n: Int): String {
        if (n <= 0) return n.toString()
        val sb = StringBuilder()
        var v = n
        for ((value, sym) in ROMAN) {
            while (v >= value) {
                sb.append(sym)
                v -= value
            }
        }
        return sb.toString()
    }

    /** In-game SkyBlock Level color: one step per 40 levels (§7 §f §e §a §2 §b §3 §9 §d §5 §6 §c §4). */
    fun levelColor(level: Int): Int {
        val step = floor(level / 40.0).toInt().coerceIn(0, LEVEL_CODES.length - 1)
        return Rich.mcColor(LEVEL_CODES[step]) ?: KitStyle.TEXT
    }

    private fun trim(v: Double): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private const val LEVEL_CODES = "7fea2b39d56c4"
    private val SUFFIXES = listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "k")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val ROMAN = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
}
