package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.ColorPart
import com.soulreturns.features.scoreboard.ColorText
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineChoice
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineText
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** The last line, after a gap: hypixel.net (alpha.hypixel.net on Alpha), the server id, own text or none. */
object FooterLine : LineType(
    "footer", ScoreboardCatalog.BASICS, "Footer",
    "Always the last line; change or hide it here.",
    listOf("§ewww.hypixel.net"),
) {
    const val SHOW = "show"
    const val TEXT = "text"

    override val pinned = PIN_BOTTOM

    override val options =
        listOf(
            LineChoice(
                SHOW, "Show",
                listOf(LineChoice.Choice("url", "hypixel.net"), LineChoice.Choice("server", "Server id"), LineChoice.Choice("custom", "Custom"), LineChoice.Choice("off", "Hidden")),
                "url",
            ),
            LineText(TEXT, "Text", "§ewww.hypixel.net", rich = true, width = 330, visible = { it.string(SHOW) == "custom" }),
            LineOptions.align("left") { it.string(SHOW) != "off" },
        )

    override val colors =
        LineColors.Own(
            listOf(
                ColorPart("t", "Color", 0xFFFF55, chromaTarget = "footer", visible = { it.string(SHOW) == "url" }),
                ColorPart("t", "Color", 0x555555, chromaTarget = "footer", visible = { it.string(SHOW) == "server" }),
            ),
        )

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = listOfNotNull(sources.fields.footerIndex.takeIf { it >= 0 })

    override fun lines(ctx: LineContext): List<String> {
        val show = ctx.options.string(SHOW)
        val chroma = "footer" in ctx.look.chromaOn
        val code = if (chroma) LegacyText.CHROMA else ctx.color("t")
        val text =
            when (show) {
                "off" -> return emptyList()
                "server" -> {
                    val id = ctx.fields.serverId ?: ctx.sources.serverId ?: return emptyList()
                    (code ?: "§8") + id
                }
                "custom" -> ctx.options.string(TEXT).let { if (chroma) ColorText.recolor(it, LegacyText.CHROMA) else it }
                else -> {
                    val url = ctx.sidebar(ctx.fields.footerIndex)?.text?.trim() ?: "www.hypixel.net"
                    (code ?: "§e") + url
                }
            }
        return listOf("", text)
    }
}
