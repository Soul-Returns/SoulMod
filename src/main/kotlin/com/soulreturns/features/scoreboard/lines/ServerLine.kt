package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineChoice
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** `11/26/26 m14AC`: today's date (as Hypixel prints it, US order) and the server id. */
object ServerLine : LineType(
    "server", ScoreboardCatalog.TIME_PLACE, "Date & server id",
    "Today's date and the server you are on, like Hypixel.",
    listOf("§711/26/26 §8m14AC"),
) {
    const val DATE = "srvDate"
    const val FORMAT = "dateFormat"

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** The mock's date formats, shown with 26 November 2026. */
    private val FORMATS =
        listOf(
            "us" to "11/26/26", "usd" to "11-26-2026", "uk" to "26/11/2026", "ukd" to "26-11-2026", "iso" to "2026-11-26",
            "ymd" to "2026/11/26", "dmy" to "26 November 2026", "dmys" to "26 Nov 2026", "mdy" to "November 26, 2026", "mdys" to "Nov 26, 2026",
        )

    override val options =
        listOf(
            LineSwitch(DATE, "Date in front", true),
            LineChoice(FORMAT, "Date format", FORMATS.map { LineChoice.Choice(it.first, it.second) }, "us", segmented = false, visible = { it.bool(DATE) }),
        )

    override val colors = LineColors.DateServer

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = listOfNotNull(sources.fields.dateIndex.takeIf { it >= 0 })

    override fun lines(ctx: LineContext): List<String> {
        val f = ctx.fields
        val server = f.serverId ?: ctx.sources.serverId ?: return emptyList()
        if (!ctx.options.bool(DATE) || f.date == null) return listOf("§8$server")
        val format = ctx.options.string(FORMAT)
        if (format == "us") ctx.sidebar(f.dateIndex)?.let { return listOf(it.legacy) }
        return listOf("§7${format(f.date, format)} §8$server")
    }

    /** Hypixel's `MM/dd/yy` in [format]; unreadable dates stay as they are. */
    fun format(date: String, format: String): String {
        val p = date.split('/')
        val m = p.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return date
        val d = p.getOrNull(1)?.toIntOrNull() ?: return date
        val y = p.getOrNull(2)?.toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: return date
        val mm = m.toString().padStart(2, '0')
        val dd = d.toString().padStart(2, '0')
        val name = MONTHS[m - 1]
        return when (format) {
            "usd" -> "$mm-$dd-$y"
            "uk" -> "$dd/$mm/$y"
            "ukd" -> "$dd-$mm-$y"
            "iso" -> "$y-$mm-$dd"
            "ymd" -> "$y/$mm/$dd"
            "dmy" -> "$d $name $y"
            "dmys" -> "$d ${name.take(3)} $y"
            "mdy" -> "$name $d, $y"
            "mdys" -> "${name.take(3)} $d, $y"
            else -> date
        }
    }
}
