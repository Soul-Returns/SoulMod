package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.ColorPart
import com.soulreturns.features.scoreboard.ColorText
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineChoice
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.OptionValues
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineText
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/** The first line: Soul's own title, Hypixel's (animated in events) or none. */
object HeaderLine : LineType(
    "header", ScoreboardCatalog.BASICS, "Header",
    "Always the first line; change or hide it here.",
    listOf("§z§lSKYBLOCK"),
) {
    const val SHOW = "show"
    const val TEXT = "text"
    const val OUTSIDE = "outside"

    override val pinned = PIN_TOP

    override fun isOff(options: OptionValues): Boolean = options.string(SHOW) == "off"

    override val options =
        listOf(
            LineChoice(SHOW, "Show", listOf(LineChoice.Choice("soul", "Custom"), LineChoice.Choice("hypixel", "Hypixel's"), LineChoice.Choice("off", "Hidden")), "soul"),
            LineText(TEXT, "Text", "§z§lSKYBLOCK", rich = true, width = 330, visible = { it.string(SHOW) == "soul" }),
            LineOptions.align("center") { it.string(SHOW) != "off" },
            LineSwitch(OUTSIDE, "Also outside SkyBlock", false),
        )

    override val colors = LineColors.Own(listOf(ColorPart("t", "Color", 0xFFFF55, chromaTarget = "header", visible = { it.string(SHOW) == "hypixel" })))

    override fun lines(ctx: LineContext): List<String> {
        val show = ctx.options.string(SHOW)
        if (show == "off") return emptyList()
        val chroma = "header" in ctx.look.chromaOn
        if (show == "hypixel") {
            val title = ctx.sources.title?.legacy?.takeIf { LegacyText.strip(it).isNotBlank() } ?: "§e§lSKYBLOCK"
            if (chroma) return listOf(ColorText.recolor(title, LegacyText.CHROMA))
            val c = ctx.color("t") ?: return listOf(title)
            return listOf(ColorText.replaceMain(title, c))
        }
        val text = ctx.options.string(TEXT)
        return listOf(if (chroma) ColorText.recolor(text, LegacyText.CHROMA) else text)
    }
}
