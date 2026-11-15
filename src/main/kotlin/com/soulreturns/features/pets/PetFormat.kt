package com.soulreturns.features.pets

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** The Pet HUD's text formats, 1:1 from `mocks/pet-hud/mock.js` (and the kit's `num`/`short`). */
object PetFormat {
    /** `1.2k` / `3.4M` / `5.6B`, SkyBlock style: one decimal, dropped when it is `.0` (`2M`). */
    fun short(n: Double): String {
        val a = abs(n)
        for ((d, s) in UNITS) {
            if (a >= d) return trim(Math.round(n / d * 10).toDouble() / 10) + s
        }
        return Math.round(n).toString()
    }

    /** `196,209`. */
    fun full(n: Double): String = String.format(Locale.ROOT, "%,d", Math.round(n))

    /** XP in the chosen number format (short default, full = 1). */
    fun xp(n: Double, format: Int): String = if (format == PetHudFormats.FULL) full(n) else short(n)

    /** `18.9%`: floored to a tenth, like the tab's own percent. */
    fun percent(f: Double): String = String.format(Locale.ROOT, "%.1f%%", floor(f * 1000) / 10)

    /** Hours → `45s`, `26m`, `1h 17m`, `3d 4h`. */
    fun duration(hours: Double): String {
        val s = (hours * 3600).roundToLong()
        if (s < 60) return "${s}s"
        val m = (s / 60.0).roundToLong()
        if (m < 60) return "${m}m"
        if (m < 60 * 24) return "${m / 60}h ${m % 60}m"
        return "${m / 1440}d ${(m % 1440) / 60}h"
    }

    private val UNITS = listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "k")

    private fun trim(v: Double): String = if (v == floor(v)) v.toLong().toString() else v.toString()
}

/** Int values of [com.soulreturns.config.PetHudConfig]'s cycle settings (never `when(enum)`). */
object PetHudFormats {
    const val CARD = 0
    const val COMPACT = 1

    const val SHOW_NO_PET = 0
    const val HIDE_NO_PET = 1

    const val SHORT = 0
    const val FULL = 1

    const val BAR_FIXED = 0
    const val BAR_RARITY = 1
}
