package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.scoreboard.ScoreboardFormat
import com.soulreturns.features.scoreboard.ScoreboardValue

/**
 * The `{value}`s of the Soul group for Custom text, besides `{pet}` (CoreValues: the tab's pet). Each
 * is null (drawn `-`) while its source has nothing.
 */
object SoulValues {
    val all: List<ScoreboardValue> =
        listOf(
            ScoreboardValue("petxp", "Soul", "Pet XP per hour", "§b412k XP/h") { c ->
                PetXpLine.view(c.nowMs)?.rate?.let { "§b" + ScoreboardFormat.number(it, true) + " XP/h" }
            },
            ScoreboardValue("combo", "Soul", "Kill combo", "§a+15 Combo") { _ -> ComboLine.placeholder() },
            ScoreboardValue("weather", "Soul", "Island weather", "§bMist §7(9m)") { c -> IslandWeatherLine.running(c.sources) },
            ScoreboardValue("fuel", "Soul", "Drill fuel", "§239,764") { c -> DrillFuelLine.placeholder(c.sources, c.nowMs) },
            ScoreboardValue("skill", "Soul", "Last skill gain", "§3+42.7 Combat") { c -> SkillGainLine.placeholder(c.sources, c.nowMs) },
        )
}
