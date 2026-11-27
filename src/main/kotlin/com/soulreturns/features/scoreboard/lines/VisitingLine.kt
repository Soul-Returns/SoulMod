package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** ` ✌ (1/6)`: guests on the island, drawn by Hypixel on a personal island. */
object VisitingLine : LineType(
    "visiting", ScoreboardCatalog.TIME_PLACE, "Visiting",
    "Guests on the island you are visiting.",
    listOf(" §a✌ §7(§a1§7/6)"),
) {
    private val SHAPE = Regex("✌\\s*\\(\\s*\\d+\\s*/\\s*\\d+\\s*\\)")

    override val where = "Someone's island"

    override val colors = LineColors.Text(0x55FF55)

    fun index(s: ScoreboardSnapshot): Int = s.sidebar.indexOfFirst { SHAPE.containsMatchIn(it.text) }

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = index(sources).let { if (it < 0) emptyList() else listOf(it) }

    override fun lines(ctx: LineContext): List<String> = listOfNotNull(ctx.sidebar(index(ctx.sources))?.legacy)

    /** `(guests, limit)` of the line, or null. */
    fun counts(s: ScoreboardSnapshot): Pair<Int, Int>? =
        s.sidebar.getOrNull(index(s))?.text?.let { Regex("\\((\\d+)\\s*/\\s*(\\d+)\\)").find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
}
