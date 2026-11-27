package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** `Objective` and the objective under it (Hypixel shows none in dungeons and some islands). */
object ObjectiveLine : LineType(
    "objective", ScoreboardCatalog.PROGRESS, "Objective",
    "Your current SkyBlock objective.",
    listOf("Objective", "§eReach the Crystal Hollows"),
) {
    override val where = "Outside dungeons"

    override val colors = LineColors.HeadingLabels(0xFFFFFF, 0xFFFF55)

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> {
        val f = sources.fields
        return if (f.objectiveIndex < 0) emptyList() else listOf(f.objectiveIndex) + f.objectiveLines
    }

    override fun lines(ctx: LineContext): List<String> {
        val f = ctx.fields
        val heading = ctx.sidebar(f.objectiveIndex) ?: return emptyList()
        return listOf(heading.legacy) + f.objectiveLines.mapNotNull { ctx.sidebar(it)?.legacy }
    }
}
