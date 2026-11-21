package com.soulreturns.features.contests.gui

import com.soulreturns.features.contests.model.ContestTypeDescriptor
import com.soulreturns.features.profileviewer.gui.kit.Format
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Text helpers of the contests mock (`mocks/contests/mock.js` helpers + `data.js` clock), plus every
 * descriptor-driven label: the screens name a contest type's unit and metric only through these.
 */
object ContestsFormat {
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DAY_HM = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)
    private val DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** Overridable for tests (fixed zone). */
    @JvmStatic var zone: ZoneId = ZoneId.systemDefault()

    /** `coins()`: the en dash for none, short above 10k, grouped below. */
    fun coins(v: Long?): String = if (v == null) "–" else if (abs(v) >= 10_000) Format.short(v) else Format.num(v)

    /** `signed()`: `+742.3k` / `-1,200`. */
    fun signed(v: Long): String = (if (v >= 0) "+" else "-") + coins(abs(v))

    fun ord(n: Int): String {
        val m = n % 100
        val suffix = if (m in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /** `mmss()`: `3:16`, the en dash for none. */
    fun mmss(ms: Long?): String {
        if (ms == null) return "–"
        val s = (ms + 500) / 1000
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    fun clock(at: Long?): String = if (at == null) "–" else HM.format(Instant.ofEpochMilli(at).atZone(zone))

    fun dayClock(at: Long): String = DAY_HM.format(Instant.ofEpochMilli(at).atZone(zone))

    fun date(at: Long): String = DATE.format(Instant.ofEpochMilli(at).atZone(zone))

    /** `agoMin()`: `just now` / `12 min ago` / `1 h 5 min ago`. */
    fun ago(at: Long, now: Long): String {
        val d = ((now - at) / 60_000).toInt()
        return if (d < 1) "just now" else if (d < 60) "$d min ago" else "${d / 60} h ${d % 60} min ago"
    }

    /** A span in words: `3 h 22 min`, `14 min`, `40 s`. */
    fun span(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        if (s < 60) return "$s s"
        val m = s / 60
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    /** `npText()`: the owner's wording for drops without a price. */
    fun noPrice(n: Int): String = "$n item${if (n == 1) "" else "s"} without a price"

    const val NO_PRICE_TIP = "These drops have no price on Soul's server. They count 0 coins and are listed here, never hidden. Powder has no coin value and is not listed."

    /** A metric value in the descriptor's format (`coins`, `count`, `duration`; unknown formats as plain numbers). */
    fun metric(t: ContestTypeDescriptor, v: Long?): String {
        if (v == null) return "–"
        return when (t.metric.format) {
            "coins" -> coins(v)
            "duration" -> mmss(v)
            else -> Format.num(v)
        }
    }

    fun signedMetric(t: ContestTypeDescriptor, v: Long): String =
        if (t.metric.format == "coins") signed(v) else (if (v >= 0) "+" else "-") + metric(t, abs(v))

    /** `50 runs` / `1 run`. */
    fun units(t: ContestTypeDescriptor, n: Int): String = "$n ${if (n == 1) t.unit.singular else t.unit.plural}"

    fun unitPlural(t: ContestTypeDescriptor): String = t.unit.plural

    fun unitSingular(t: ContestTypeDescriptor): String = t.unit.singular

    /** `Run` with a capital, for column heads and labels. */
    fun unitTitle(t: ContestTypeDescriptor): String = t.unit.singular.replaceFirstChar { it.titlecase(Locale.ROOT) }

    fun unitsTitle(t: ContestTypeDescriptor): String = t.unit.plural.replaceFirstChar { it.titlecase(Locale.ROOT) }

    fun capitalize(s: String): String = s.replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** The player's name with a trailing `'s` (`MossyMira's`). */
    fun possessive(name: String): String = if (name.endsWith("s")) "$name'" else "$name's"
}
