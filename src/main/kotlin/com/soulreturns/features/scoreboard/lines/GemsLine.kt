package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardFormat

/** `Gems: 1.2k`: SkyBlock gems from the tab list. */
object GemsLine : LineType(
    "gems", ScoreboardCatalog.MONEY, "Gems",
    "SkyBlock gems (from the tab list).",
    listOf("Gems: §a1.2k"),
) {
    const val SHORT = "gemsShort"
    private const val LABEL = "Gems:"

    private val gains = GainTracker()

    override val options = listOf(LineSwitch(SHORT, "Always short (1.2k)", true), LineOptions.label(LABEL), LineOptions.gain())

    override val colors = LineColors.LabelValue(0xFFFFFF, 0x55FF55)

    private fun fmt(ctx: LineContext, v: Double) = if (ctx.options.bool(SHORT)) ScoreboardFormat.number(v, true) else ctx.number(v)

    override fun lines(ctx: LineContext): List<String> {
        val gems = ctx.sources.tab.gems?.toDouble()
        val own = gains.update(gems, ctx.nowMs)
        gems ?: return emptyList()
        val gain = if (ctx.options.bool("gain") && own != null) " §a(+${fmt(ctx, own)})" else ""
        return listOf(ctx.labelValue(ctx.options.string("label"), "§a", fmt(ctx, gems), gain))
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf(ctx.labelValue(ctx.options.string("label"), "§8", "-"))
}
