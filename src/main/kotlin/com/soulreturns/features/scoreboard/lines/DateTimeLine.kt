package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.ColorPart
import com.soulreturns.features.scoreboard.ColorText
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * `Late Autumn 11th 2:00am ☽`: the SkyBlock date and time on one line, date first (or time first), one
 * space between. Built from [SkyblockDateLine] and [TimeLine], so their options behave the same.
 */
object DateTimeLine : LineType(
    "datetime", ScoreboardCatalog.TIME_PLACE, "SkyBlock date & time",
    "Season, day and in-game time on one line.",
    listOf(" Late Winter 10th §72:20pm §e☀"),
) {
    const val TIME_FIRST = "timeFirst"

    override val options =
        listOf(
            LineSwitch(SkyblockDateLine.MONTH, "Colored month", false),
            LineSwitch(TimeLine.H24, "24-hour clock", false),
            LineSwitch(TimeLine.EXACT, "Exact minutes", false, "Not just every 10 minutes"),
            LineSwitch(TIME_FIRST, "Time first", false),
            LineOptions.indent(),
        )

    override val colors =
        LineColors.Own(
            listOf(
                ColorPart("d", "Date", 0xFFFFFF, chromaTarget = "values"),
                ColorPart("t", "Time", 0xAAAAAA, chromaTarget = "values"),
            ),
        )

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> =
        listOfNotNull(sources.fields.skyblockDateIndex.takeIf { it >= 0 }, sources.fields.timeIndex.takeIf { it >= 0 })

    private val OVERRIDDEN = Regex("§[0-9a-f](?=§[0-9a-fz#])")
    private val LEAD = Regex("^((?:§(?:#[0-9a-fA-F]{6}|.))*) +")

    /** Hypixel's leading space, which sits after the first color codes. */
    private fun unindent(s: String) = LEAD.replaceFirst(s, "$1").replace(OVERRIDDEN, "")

    /** The date part without Hypixel's leading space, colored by the picked Date color. */
    fun date(ctx: LineContext): String? =
        SkyblockDateLine.lines(ctx).firstOrNull()?.let(::unindent)?.let { part -> ctx.color("d", "values")?.let { ColorText.replaceMain(part, it) } ?: part }

    /** The time part (clock and icon) without the leading space, colored by the picked Time color. */
    fun time(ctx: LineContext): String? =
        TimeLine.lines(ctx).firstOrNull()?.let(::unindent)?.let { part -> ctx.color("t", "values")?.let { ColorText.replaceMain(part, it) } ?: part }

    override fun lines(ctx: LineContext): List<String> {
        val d = date(ctx)
        val t = time(ctx)
        val parts = (if (ctx.options.bool(TIME_FIRST)) listOf(t, d) else listOf(d, t)).filterNotNull()
        if (parts.isEmpty()) return emptyList()
        return listOf((if (ctx.options.bool(INDENT)) " " else "") + parts.joinToString(" §r"))
    }
}
