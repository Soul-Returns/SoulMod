package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/** `Soulflow: 812`: the Soulflow line of the tab list (nothing while the tab list does not show it). */
object SoulflowLine : LineType(
    "soulflow", ScoreboardCatalog.MONEY, "Soulflow",
    "Soulflow (from the tab list).",
    listOf("Soulflow: §3812"),
) {
    private const val LABEL = "Soulflow:"

    private val gains = GainTracker()

    override val options = listOf(LineOptions.label(LABEL), LineOptions.gain())

    override val colors = LineColors.LabelValue(0xFFFFFF, 0x00AAAA)

    fun amount(ctx: LineContext): Double? = Stat.tabValue(ctx.sources, "Soulflow")?.let { Stat.parse(it.takeWhile { c -> c.isDigit() || c == ',' || c == '.' }) }

    override fun lines(ctx: LineContext): List<String> {
        val v = amount(ctx)
        val own = gains.update(v, ctx.nowMs)
        v ?: return emptyList()
        val gain = if (ctx.options.bool("gain") && own != null) " §3(+${ctx.number(own)})" else ""
        return listOf(ctx.labelValue(ctx.options.string("label"), "§3", ctx.number(v), gain))
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf(ctx.labelValue(ctx.options.string("label"), "§8", "-"))

    fun value(ctx: LineContext): String? = amount(ctx)?.let { "§3" + ctx.number(it) }
}
