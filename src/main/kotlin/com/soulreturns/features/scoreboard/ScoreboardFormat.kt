package com.soulreturns.features.scoreboard

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** Number and `Label: value` formatting shared by the line types (Look › Advanced › Numbers). */
object ScoreboardFormat {
    private val UNITS = arrayOf("k", "M", "B", "T")

    /** `1,193,004,241` (long) or `1.19B` (short). Whole numbers lose their `.0`. */
    fun number(value: Double, short: Boolean): String {
        if (short && abs(value) >= 1000) {
            var v = value
            var unit = -1
            while (abs(v) >= 1000 && unit < UNITS.lastIndex) {
                v /= 1000
                unit++
            }
            val decimals = if (abs(v) < 10) 2 else 1
            return trim(String.format(Locale.ROOT, "%.${decimals}f", v)) + UNITS[unit]
        }
        return if (value == floor(value) && abs(value) < 1e15) {
            String.format(Locale.ROOT, "%,d", value.toLong())
        } else {
            trim(String.format(Locale.ROOT, "%,.1f", value))
        }
    }

    private fun trim(s: String): String = if ('.' in s) s.trimEnd('0').trimEnd('.') else s

    /**
     * A label and a value in the Look's number style: `tcn` `Purse: §61,234` (Hypixel's), `ctn` all in the
     * value color, `cnt` `§61,234 Purse`, `cnrt` `§61,234 §fPurse`. Unknown styles fall back to `tcn`.
     */
    fun labelValue(style: String, label: String, valueCode: String, value: String): String {
        val bare = label.trim().removeSuffix(":")
        return when (style) {
            "ctn" -> "$valueCode$label $value"
            "cnt" -> "$valueCode$value $bare"
            "cnrt" -> "$valueCode$value §f$bare"
            else -> "§f$label $valueCode$value"
        }
    }
}
