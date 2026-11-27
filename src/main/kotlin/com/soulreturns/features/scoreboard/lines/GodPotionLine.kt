package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/** `God Potion: 12h`: time left on your God Potion, read from the tab list footer (real capture). */
object GodPotionLine : LineType(
    "godpot", ScoreboardCatalog.BUFFS, "God Potion",
    "God Potion time left.",
    listOf("God Potion: §c12h"),
) {
    private const val LABEL = "God Potion:"

    override val options = listOf(LineOptions.label(LABEL))

    override val colors = LineColors.LabelValue(0xFFFFFF, 0xFF5555)

    override fun lines(ctx: LineContext): List<String> {
        val left = ActiveEffects.godPotion(ctx.sources) ?: return emptyList()
        return listOf(ctx.labelValue(ctx.options.string("label"), "§c", left))
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf(ctx.labelValue(ctx.options.string("label"), "§8", "-"))
}
