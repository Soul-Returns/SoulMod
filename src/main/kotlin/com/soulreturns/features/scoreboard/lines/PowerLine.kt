package com.soulreturns.features.scoreboard.lines

import com.soulreturns.data.hypixel.magicalPower
import com.soulreturns.data.hypixel.powerDisplayName
import com.soulreturns.data.hypixel.selectedPower
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/**
 * `Power: Sighted (1,412)`: the selected Maxwell power and your Magical Power, from your profile as
 * Hypixel's API last reported it (loaded on every profile switch; not available on the Alpha network).
 */
object PowerLine : LineType(
    "power", ScoreboardCatalog.PROGRESS, "Maxwell power",
    "Selected power and magical power.",
    listOf("Power: §aSighted §7(§61,412§7)"),
) {
    const val MP = "mp"
    private const val LABEL = "Power:"

    override val options = listOf(LineSwitch(MP, "Magical power", true), LineOptions.label(LABEL))

    override val colors = LineColors.LabelValue(0xFFFFFF, 0x55FF55)

    override fun lines(ctx: LineContext): List<String> {
        val member = Stat.member() ?: return emptyList()
        val power = member.selectedPower?.takeIf { it.isNotBlank() }?.let(::powerDisplayName) ?: return emptyList()
        val mp = if (ctx.options.bool(MP)) member.magicalPower?.let { " §7(§6${ctx.number(it.toDouble())}§7)" }.orEmpty() else ""
        return listOf(ctx.labelValue(ctx.options.string("label"), "§a", power + mp))
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf(ctx.labelValue(ctx.options.string("label"), "§8", "-"))
}
