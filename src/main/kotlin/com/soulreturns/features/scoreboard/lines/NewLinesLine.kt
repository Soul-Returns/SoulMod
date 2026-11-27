package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot
import com.soulreturns.features.scoreboard.SourceLine

/**
 * Sidebar lines no line type stands for ([LineType.claims]): a new event after a Hypixel update, or a
 * line whose type is not built yet. Shown as Hypixel draws them, in Hypixel's order.
 */
object NewLinesLine : LineType(
    "extra", ScoreboardCatalog.BASICS, "New lines from Hypixel",
    "Lines Soul does not know yet, like a new event after an update.",
    listOf("§7(a line Hypixel added)"),
) {
    /** Option: a chat note the first time a line of a new shape shows ([com.soulreturns.features.scoreboard.Scoreboard]). */
    const val NOTE = "unknownWarn"

    override val where = "After a Hypixel update"

    override val options = listOf(LineSwitch(NOTE, "Tell me in chat when one shows up", true))

    @Volatile
    private var cache: Pair<ScoreboardSnapshot, List<Int>>? = null

    /** Indexes of the unclaimed, non-blank sidebar lines of [sources], top first (cached per snapshot). */
    fun unknownIndexes(sources: ScoreboardSnapshot): List<Int> {
        cache?.let { (s, lines) -> if (s === sources) return lines }
        val claimed = ScoreboardCatalog.claimed(sources)
        val lines = sources.sidebar.indices.filter { i -> i !in claimed && sources.sidebar[i].text.isNotBlank() }
        cache = sources to lines
        return lines
    }

    fun unknown(sources: ScoreboardSnapshot): List<SourceLine> = unknownIndexes(sources).map { sources.sidebar[it] }

    /** The new lines, with a gap where Hypixel had a blank line between two of them. */
    override fun lines(ctx: LineContext): List<String> {
        val sidebar = ctx.sources.sidebar
        val out = ArrayList<String>()
        var prev = -1
        for (i in unknownIndexes(ctx.sources)) {
            if (prev >= 0 && (prev + 1 until i).any { sidebar[it].text.isBlank() }) out += ""
            out += sidebar[i].legacy
            prev = i
        }
        return out
    }
}
