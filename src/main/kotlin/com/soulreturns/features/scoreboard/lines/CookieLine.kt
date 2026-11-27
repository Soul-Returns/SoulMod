package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/** `Cookie Buff: 2d 22h`: time left on your Booster Cookie, read from the tab list footer (real capture). */
object CookieLine : LineType(
    "cookie", ScoreboardCatalog.BUFFS, "Cookie buff",
    "Booster Cookie time left.",
    listOf("§dCookie Buff§f: 2d 22h"),
) {
    private const val LABEL = "Cookie Buff:"

    override val options = listOf(LineOptions.label(LABEL))

    override val colors = LineColors.LabelValue(0xFF55FF, 0xFFFFFF)

    override fun lines(ctx: LineContext): List<String> {
        val left = ActiveEffects.cookie(ctx.sources) ?: return emptyList()
        return listOf("§d${ctx.options.string("label")} §f$left")
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf("§d${ctx.options.string("label")} §8-")
}
