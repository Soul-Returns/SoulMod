package com.soulreturns.features.scoreboard.lines.events

import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.ScoreboardActivities
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** What one event takes from the sources: the sidebar lines it stands for ([indexes]) and what it draws ([lines]). */
class EventRead(val indexes: List<Int>, val lines: List<String>)

/** What an event's reader gets: the sources and whether the "Event:" prefix is wanted (Events line option). */
class EventCtx(val s: ScoreboardSnapshot, val prefix: Boolean) {
    val view = SbView(s)
    val inDungeon: Boolean get() = activity(ScoreboardActivities.DUNGEONS)
    val inKuudra: Boolean get() = activity(ScoreboardActivities.KUUDRA)

    fun activity(id: String): Boolean = ScoreboardActivities.of(id)?.matches(s) == true
}

/**
 * One of the 28 events: the catalogue row (id, name, example as the mock lists it), how its lines are
 * colored and [read], which turns the sources into lines (null = not running). Sidebar events read
 * Hypixel's own lines (stripped, trimmed text is matched, colors kept in what is drawn); a line that
 * does not look like the event is simply not taken and shows up under "New lines from Hypixel".
 */
class EventDef(
    val id: String,
    val name: String,
    val description: String,
    val example: List<String>,
    val where: String?,
    val colors: LineColors,
    /** Reads the tab list instead of the sidebar. */
    val tab: Boolean = false,
    /** The standalone line shows the "Event:" prefix (the Events line's option decides for itself). */
    val standalonePrefix: Boolean = false,
    val read: (EventCtx) -> EventRead?,
) {
    fun read(s: ScoreboardSnapshot, prefix: Boolean = standalonePrefix): EventRead? = runCatching { read(EventCtx(s, prefix)) }.getOrNull()
}

/** The sidebar as stripped, trimmed text with the index helpers every event uses. */
class SbView(val s: ScoreboardSnapshot) {
    val text: List<String> = s.sidebar.map { it.text.trim() }
    val size: Int get() = text.size

    fun legacy(i: Int): String = s.sidebar[i].legacy

    fun first(re: Regex, from: Int = 0): Int {
        for (i in from until size) if (re.matches(text[i])) return i
        return -1
    }

    fun has(re: Regex): Boolean = first(re) >= 0

    fun all(re: Regex): List<Int> = text.indices.filter { re.matches(text[it]) }

    /** Non-blank lines right under [i] (up to [max]), stopping at a gap and at Hypixel's footer. */
    fun after(i: Int, max: Int): List<Int> {
        val out = ArrayList<Int>()
        var j = i + 1
        while (j < size && out.size < max && text[j].isNotEmpty() && j != s.fields.footerIndex) out += j++
        return out
    }
}

/** Collects the lines of one event in sidebar order. */
internal class Collector(val v: SbView) {
    private val indexes = LinkedHashSet<Int>()
    private val out = ArrayList<Pair<Int, String>>()

    /** Take line [i] as it is drawn ([legacy] replaces its text). */
    fun add(i: Int, legacy: String = v.legacy(i)) {
        if (indexes.add(i)) out += i to legacy
    }

    /** Take line [i] without drawing it (explanatory text the event does not need). */
    fun claim(i: Int) {
        indexes.add(i)
    }

    /** Take every line matching [re]. */
    fun scan(re: Regex) {
        for (i in v.all(re)) add(i)
    }

    fun scanClaim(re: Regex) {
        for (i in v.all(re)) claim(i)
    }

    /** A matching line and [more] non-blank lines under it. */
    fun block(re: Regex, more: Int) {
        val i = v.first(re)
        if (i < 0) return
        add(i)
        for (j in v.after(i, more)) add(j)
    }

    fun read(extra: List<String> = emptyList()): EventRead? {
        if (indexes.isEmpty() && extra.isEmpty()) return null
        return EventRead(indexes.toList(), out.sortedBy { it.first }.map { it.second } + extra)
    }
}

/** Fixed line patterns, matched against stripped, trimmed text. */
internal fun re(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

/** The part of [legacy] after `Label:`, color codes kept, leading spaces dropped. */
internal fun afterColon(legacy: String): String {
    val plain = LegacyText.strip(legacy)
    val colon = plain.indexOf(':')
    if (colon < 0) return legacy.trim()
    // Walk the legacy string to the same visible position.
    var seen = 0
    var i = 0
    while (i < legacy.length && seen <= colon) {
        if (legacy[i] == '§' && i + 1 < legacy.length) {
            i += if (legacy[i + 1] == '#') 8 else 2
        } else {
            seen++
            i++
        }
    }
    return legacy.substring(i).trimStart()
}
