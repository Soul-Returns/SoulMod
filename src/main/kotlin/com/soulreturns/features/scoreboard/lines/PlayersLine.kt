package com.soulreturns.features.scoreboard.lines

import com.soulreturns.data.skyblock.TabWidgets
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * `Players: 69/80`: players on this server. The count is the tab list's `Players (N)` plus `Guests (N)`;
 * the limit is only known where it is certain (mega servers and the Hub 80, a visited island's guest limit).
 */
object PlayersLine : LineType(
    "players", ScoreboardCatalog.TIME_PLACE, "Player count",
    "Players on this server and its limit.",
    listOf("Players: §a69§7/§a80"),
) {
    const val MAX = "playersMax"
    private const val LABEL = "Players:"
    private val COUNT = Regex("(Players|Guests) \\((\\d+)\\)")

    override val where = "Hubs and islands with a limit"

    override val options = listOf(LineSwitch(MAX, "Show the maximum", true), LineOptions.label(LABEL))

    override val colors = LineColors.LabelValue(0xFFFFFF, 0x55FF55)

    /** The tab list's lines in display order, centered titles included (tests replace it). */
    internal var tabLines: () -> List<String> = { TabWidgets.lastLines }

    fun count(): Int? {
        var total = 0
        var seen = false
        for (l in tabLines()) COUNT.matchEntire(LegacyText.strip(l).trim())?.let { total += it.groupValues[2].toInt(); seen = true }
        return if (seen) total else null
    }

    fun max(s: ScoreboardSnapshot): Int? {
        val guests = VisitingLine.counts(s)
        if (guests != null) return null
        return when {
            s.serverId?.startsWith("mega", ignoreCase = true) == true -> 80
            s.area.equals("Hub", ignoreCase = true) -> 80
            else -> null
        }
    }

    override fun lines(ctx: LineContext): List<String> {
        val n = count() ?: return emptyList()
        val limit = if (ctx.options.bool(MAX)) max(ctx.sources) else null
        val value = if (limit != null) "$n§7/§a$limit" else "$n"
        return listOf(ctx.labelValue(ctx.options.string("label"), "§a", value))
    }

    fun value(ctx: LineContext): String? = count()?.let { "§a$it" }
}
