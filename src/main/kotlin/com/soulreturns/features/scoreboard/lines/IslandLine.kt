package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/**
 * `㋖ Dwarven Mines`: the island, from the tab list's `Area:` (the location packet's map in dungeons,
 * where the tab list has none). Hypixel's sidebar has no such line; Soul draws it in the mock's form.
 */
object IslandLine : LineType(
    "island", ScoreboardCatalog.TIME_PLACE, "Island",
    "The island you are on.",
    listOf("§7㋖ §aDwarven Mines"),
) {
    override val colors = LineColors.Text(0x55FF55)

    override fun lines(ctx: LineContext): List<String> {
        val area = ctx.sources.area ?: return emptyList()
        return listOf("§7㋖ §a$area")
    }
}
