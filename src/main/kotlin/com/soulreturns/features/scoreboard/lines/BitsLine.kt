package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot
import com.soulreturns.features.scoreboard.SidebarParser

/** `Bits: 13,845`: bits, the last gain, and optionally the bits still to claim (tab list `Bits Available:`). */
object BitsLine : LineType(
    "bits", ScoreboardCatalog.MONEY, "Bits",
    "Bits, and the ones still to claim.",
    listOf("Bits: §b13,845"),
) {
    const val AVAILABLE = "bitsAvail"
    private const val LABEL = "Bits:"
    private val AVAILABLE_LINE = Regex("\\s*Bits Available: ([\\d,]+).*")

    private val gains = GainTracker()

    override val options = listOf(LineSwitch(AVAILABLE, "Bits still to claim", false), LineOptions.label(LABEL), LineOptions.gain())

    override val colors = LineColors.LabelValue(0xFFFFFF, 0x55FFFF)

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = listOfNotNull(sources.fields.bitsIndex.takeIf { it >= 0 })

    override fun lines(ctx: LineContext): List<String> {
        val f = ctx.fields
        val own = gains.update(f.bits, ctx.nowMs)
        val bits = f.bits ?: return emptyList()
        val sb = StringBuilder()
        if (ctx.options.bool("gain")) (f.bitsGain?.let { " §b($it)" } ?: own?.let { " §b(+${ctx.number(it)})" })?.let(sb::append)
        if (ctx.options.bool(AVAILABLE)) available(ctx.sources)?.let { sb.append(" §3(${ctx.number(it)})") }
        return listOf(ctx.labelValue(ctx.options.string("label"), "§b", ctx.number(bits), sb.toString()))
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf(ctx.labelValue(ctx.options.string("label"), "§8", "-"))

    private fun available(s: ScoreboardSnapshot): Double? {
        for (w in s.tabWidgets) for (l in w.lines) AVAILABLE_LINE.matchEntire(LegacyText.strip(l.text))?.let { return SidebarParser.number(it.groupValues[1]) }
        return null
    }
}
