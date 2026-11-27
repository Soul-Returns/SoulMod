package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `9:41 PM`: the clock of your computer. */
object RealTimeLine : LineType(
    "irl", ScoreboardCatalog.TIME_PLACE, "Real time",
    "The clock of your computer.",
    listOf("§79:41 PM"),
) {
    const val H24 = "t24"

    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    private val H12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val H24F = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    override val options = listOf(LineSwitch(H24, "24-hour clock", false))

    override val colors = LineColors.Text(0xAAAAAA)

    override fun lines(ctx: LineContext): List<String> {
        val t = Instant.ofEpochMilli(ctx.nowMs).atZone(zone())
        return listOf("§7" + t.format(if (ctx.options.bool(H24)) H24F else H12))
    }
}
