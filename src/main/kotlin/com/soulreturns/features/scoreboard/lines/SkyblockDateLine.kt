package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** ` Late Winter 10th`: season and day of the SkyBlock calendar. */
object SkyblockDateLine : LineType(
    "date", ScoreboardCatalog.TIME_PLACE, "SkyBlock date",
    "Season and day of the SkyBlock calendar.",
    listOf(" Late Winter 10th"),
) {
    const val MONTH = "month"

    private val SEASON = Regex("((?:Early |Late )?(Spring|Summer|Autumn|Winter))(.*)")

    override val options = listOf(LineSwitch(MONTH, "Colored month", false), LineOptions.indent())

    override val colors = LineColors.Text(0xFFFFFF)

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = listOfNotNull(sources.fields.skyblockDateIndex.takeIf { it >= 0 })

    override fun lines(ctx: LineContext): List<String> {
        val line = ctx.sidebar(ctx.fields.skyblockDateIndex) ?: return emptyList()
        if (!ctx.options.bool(MONTH)) return listOf(indented(ctx, line.legacy))
        val text = line.text.trim()
        val m = SEASON.matchEntire(text) ?: return listOf(indented(ctx, line.legacy))
        val color = when (m.groupValues[2]) {
            "Spring" -> "§a"
            "Summer" -> "§e"
            "Autumn" -> "§6"
            else -> "§b"
        }
        val lead = if (ctx.options.bool(INDENT)) " " else ""
        return listOf(lead + color + m.groupValues[1] + "§f" + LegacyText.strip(m.groupValues[3]))
    }
}
