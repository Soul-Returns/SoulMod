package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.ScoreboardValue

/** `{placeholders}` of the Money and Progress lines (the rest of the "More values" list is in [CoreValues]). */
object MoneyProgressValues {
    private fun powder(c: LineContext, name: String): String? {
        val row = c.sources.widget("Powders")?.lines?.drop(1)?.firstOrNull { it.text.trim().startsWith("$name:") } ?: return null
        val v = Stat.parse(row.text.substringAfter(':').trim()) ?: return null
        val code = Stat.leadingColor(row.legacy.substringAfter(':').trimStart()) ?: "§f"
        return code + c.number(v)
    }

    val all: List<ScoreboardValue> =
        listOf(
            ScoreboardValue("datetime", "Time", "SkyBlock date & time", "§fLate Winter 10th §72:20pm") { c ->
                listOfNotNull(DateTimeLine.date(c), DateTimeLine.time(c)).takeIf { it.isNotEmpty() }?.joinToString(" §r")
            },
            ScoreboardValue("motes", "Money", "Motes", "§d0") { c -> MotesLine.value(c) },
            ScoreboardValue("copper", "Money", "Copper", "§c1,204") { c -> CopperLine.value(c) },
            ScoreboardValue("soulflow", "Money", "Soulflow", "§3812") { c -> SoulflowLine.value(c) },
            ScoreboardValue("players", "Place", "Player count", "§a24") { c -> PlayersLine.value(c) },
            ScoreboardValue("slayer", "Progress", "Slayer quest", "§cRevenant Horror IV") { c ->
                SlayerLine.boss(c.sources)?.let { name -> (Stat.leadingColor(c.sources.sidebar[SlayerLine.indexes(c.sources)[1]].legacy) ?: "§c") + name }
            },
            ScoreboardValue("mithril", "Progress", "Mithril powder", "§22,988,324") { c -> powder(c, "Mithril") },
            ScoreboardValue("gemstone", "Progress", "Gemstone powder", "§d138,760") { c -> powder(c, "Gemstone") },
            ScoreboardValue("glacite", "Progress", "Glacite powder", "§b19,295") { c -> powder(c, "Glacite") },
        )
}
