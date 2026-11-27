package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** ` ⏣ Dwarven Village`: the area on the island, with Hypixel's marker (a resource-pack glyph today). */
object AreaLine : LineType(
    "area", ScoreboardCatalog.TIME_PLACE, "Area",
    "The area on the island, like Hypixel shows it.",
    listOf(" §7⏣ §bDwarven Village"),
) {
    override val options = listOf(LineOptions.indent())

    override val colors = LineColors.Text(0x55FFFF)

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = listOfNotNull(sources.fields.areaIndex.takeIf { it >= 0 })

    override fun lines(ctx: LineContext): List<String> {
        val line = ctx.sidebar(ctx.fields.areaIndex)
        if (line != null) return listOf(indented(ctx, line.legacy))
        val sub = ctx.sources.subLocation ?: return emptyList()
        return listOf(indented(ctx, " §7⏣ §b$sub"))
    }
}
