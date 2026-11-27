package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** ` 2:20pm ☀`: SkyBlock time with Hypixel's day, night or weather icon. */
object TimeLine : LineType(
    "time", ScoreboardCatalog.TIME_PLACE, "SkyBlock time",
    "In-game time with Hypixel's day, night or weather icon.",
    listOf(" §72:20pm §e☀"),
) {
    const val H24 = "t24"
    const val EXACT = "exactMin"

    /** SkyBlock's calendar start (Year 1, Early Spring 1st, 00:00): a day is 20 real minutes, an hour 50 s. */
    private const val EPOCH_MS = 1_560_275_700_000L
    private const val DAY_MS = 1_200_000L
    private const val HOUR_MS = 50_000L

    private val CLOCK = Regex("((?:§#[0-9a-fA-F]{6}|§.)*)(\\d{1,2}):(\\d{2})\\s*([ap]m)", RegexOption.IGNORE_CASE)

    override val options =
        listOf(
            LineSwitch(H24, "24-hour clock", false),
            LineSwitch(EXACT, "Exact minutes", false, "Not just every 10 minutes"),
            LineOptions.indent(),
        )

    override val colors = LineColors.Text(0xAAAAAA)

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = listOfNotNull(sources.fields.timeIndex.takeIf { it >= 0 })

    override fun lines(ctx: LineContext): List<String> {
        val line = ctx.sidebar(ctx.fields.timeIndex) ?: return emptyList()
        val legacy = indented(ctx, line.legacy)
        val exact = ctx.options.bool(EXACT)
        val h24 = ctx.options.bool(H24)
        if (!exact && !h24) return listOf(legacy)
        return listOf(
            CLOCK.replace(legacy) { m ->
                var h = m.groupValues[2].toInt() % 12 + if (m.groupValues[4].equals("pm", true)) 12 else 0
                var min = m.groupValues[3].toInt()
                if (exact) {
                    val t = Math.floorMod(ctx.nowMs - EPOCH_MS, DAY_MS)
                    h = (t / HOUR_MS).toInt()
                    min = ((t % HOUR_MS) * 60 / HOUR_MS).toInt()
                }
                m.groupValues[1] + clock(h, min, h24)
            },
        )
    }

    /** `14:05` or `2:05pm`. */
    fun clock(hour: Int, minute: Int, h24: Boolean): String {
        val mm = minute.toString().padStart(2, '0')
        if (h24) return "$hour:$mm"
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$h12:$mm${if (hour < 12) "am" else "pm"}"
    }
}
