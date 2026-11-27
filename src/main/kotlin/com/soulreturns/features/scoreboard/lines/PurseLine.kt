package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** `Purse: 1,193,004,241 (+5)`: coins in the purse (`Piggy:` with a piggy bank) and the last gain. */
object PurseLine : LineType(
    "purse", ScoreboardCatalog.MONEY, "Purse",
    "Coins in your purse (Piggy with a piggy bank).",
    listOf("Purse: §61,193,004,241"),
) {
    const val PIGGY = "piggy"
    private const val LABEL = "Purse:"

    private val gains = GainTracker()

    override val options = listOf(LineOptions.label(LABEL), LineOptions.gain(), LineSwitch(PIGGY, "\"Piggy\" with a piggy bank", true))

    override val colors = LineColors.LabelValue(0xFFFFFF, 0xFFAA00)

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = listOfNotNull(sources.fields.purseIndex.takeIf { it >= 0 })

    override fun lines(ctx: LineContext): List<String> {
        val f = ctx.fields
        val own = gains.update(f.purse, ctx.nowMs)
        val purse = f.purse ?: return emptyList()
        var label = ctx.options.string("label")
        if (label == LABEL && f.purseLabel == "Piggy" && ctx.options.bool(PIGGY)) label = "Piggy:"
        val gain =
            if (!ctx.options.bool("gain")) "" else f.purseGain?.let { " §e($it)" } ?: own?.let { " §e(+${ctx.number(it)})" } ?: ""
        return listOf(ctx.labelValue(label, "§6", ctx.number(purse), gain))
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf(ctx.labelValue(ctx.options.string("label"), "§8", "-"))
}
