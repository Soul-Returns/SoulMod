package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * A `Label: value` counter Hypixel draws on the sidebar itself (Motes, Copper, Heat, …). Found by its
 * label, shown with Soul's label/number style, Hypixel's value color and its own `(+n)` gain. Nothing
 * shows while Hypixel does not draw the line (it only does on its own islands).
 */
abstract class SidebarStatLine(
    id: String,
    name: String,
    description: String,
    example: List<String>,
    /** The label as Hypixel writes it, without the colon. */
    private val sidebarLabel: String,
    /** The value's color when Hypixel's line has none. */
    private val valueCode: String,
    valueRgb: Int,
    at: String,
    /** Also show Soul's own `(+n)` when Hypixel draws none. */
    private val ownGain: Boolean,
) : LineType(id, ScoreboardCatalog.MONEY, name, description, example) {
    private val pattern = Regex("${Regex.escape(sidebarLabel)}:\\s*(.*)")
    private val gains = GainTracker()

    override val where: String? = at

    override val options = listOf(LineOptions.label("$sidebarLabel:"), LineOptions.gain())

    override val colors = LineColors.LabelValue(0xFFFFFF, valueRgb)

    private class Read(val code: String, val shown: String, val gain: String?, val number: Double?)

    fun index(s: ScoreboardSnapshot): Int = s.sidebar.indexOfFirst { pattern.matches(it.text.trim()) }

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = index(sources).let { if (it < 0) emptyList() else listOf(it) }

    private fun read(ctx: LineContext): Read? {
        val line = ctx.sidebar(index(ctx.sources)) ?: return null
        val rest = line.legacy.substringAfter(':').trimStart()
        val text = Stat.strip(rest).trim()
        val gain = Stat.GAIN.find(text)?.groupValues?.get(1)
        val core = text.replace(Stat.GAIN, "").trim()
        if (core.isEmpty()) return null
        return Read(Stat.leadingColor(rest) ?: valueCode, Stat.number(ctx, core), gain, Stat.parse(core))
    }

    /** The colored value alone (Money on one line, `{placeholders}`), null while not shown. */
    fun value(ctx: LineContext): String? = read(ctx)?.let { it.code + it.shown }

    override fun lines(ctx: LineContext): List<String> {
        val r = read(ctx)
        val own = if (ownGain) gains.update(r?.number, ctx.nowMs) else null
        r ?: return emptyList()
        val gain = if (!ctx.options.bool("gain")) "" else r.gain?.let { " §7($it)" } ?: own?.let { " §7(+${ctx.number(it)})" } ?: ""
        return listOf(ctx.labelValue(ctx.options.string("label"), r.code, r.shown, gain))
    }
}
