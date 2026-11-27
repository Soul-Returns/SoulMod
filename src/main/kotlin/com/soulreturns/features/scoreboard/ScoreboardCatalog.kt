package com.soulreturns.features.scoreboard

import com.soulreturns.features.scoreboard.lines.AreaLine
import com.soulreturns.features.scoreboard.lines.BitsLine
import com.soulreturns.features.scoreboard.lines.CoreValues
import com.soulreturns.features.scoreboard.lines.CustomTextLine
import com.soulreturns.features.scoreboard.lines.EmptyLine
import com.soulreturns.features.scoreboard.lines.FooterLine
import com.soulreturns.features.scoreboard.lines.HeaderLine
import com.soulreturns.features.scoreboard.lines.IslandLine
import com.soulreturns.features.scoreboard.lines.NewLinesLine
import com.soulreturns.features.scoreboard.lines.ObjectiveLine
import com.soulreturns.features.scoreboard.lines.PurseLine
import com.soulreturns.features.scoreboard.lines.ServerLine
import com.soulreturns.features.scoreboard.lines.SkyblockDateLine
import com.soulreturns.features.scoreboard.lines.TabWidgetLine
import com.soulreturns.features.scoreboard.lines.TimeLine

/**
 * Every line type and live value, in picker order. Adding one = its file under `lines/` plus one row
 * here; nothing else in the core changes.
 */
object ScoreboardCatalog {
    const val BASICS = "Basics"
    const val TIME_PLACE = "Time & place"
    const val MONEY = "Money"
    const val PROGRESS = "Progress"
    const val BUFFS = "Buffs & world"
    const val EVENTS = "Events"
    const val TAB_LIST = "From the tab list"
    const val SOUL = "Soul"

    /** Picker groups, in order. */
    val groups = listOf(BASICS, TIME_PLACE, MONEY, PROGRESS, BUFFS, EVENTS, TAB_LIST, SOUL)

    val lines: List<LineType> by lazy {
        listOf(
            HeaderLine,
            FooterLine,
            CustomTextLine,
            EmptyLine,
            NewLinesLine,
            ServerLine,
            SkyblockDateLine,
            TimeLine,
            IslandLine,
            AreaLine,
            PurseLine,
            BitsLine,
            ObjectiveLine,
            TabWidgetLine("tForges", "Forges", "Forges", "What is in your forge slots.", listOf("§lForges:", " §a1) §fRefined Mithril §e2h 14m"), "Dwarven Mines"),
            TabWidgetLine("tComms", "Commissions", "Commissions", "Commission progress.", listOf("§9§lCommissions:", " §fGoblin Slayer: §a68%"), "Mining islands"),
            TabWidgetLine("tPowders", "Powders", "Powders", "Powder amounts.", listOf("§9§lPowders:", " §fMithril: §266,017", " §fGemstone: §d13,076"), "Mining islands"),
        )
    }

    val values: List<ScoreboardValue> by lazy { CoreValues.all }

    private val byId: Map<String, LineType> by lazy { lines.associateBy { it.id } }

    fun type(id: String): LineType? = byId[id]

    /** Sidebar lines some type stands for; the rest are new to Soul ([NewLinesLine]). */
    fun claimed(sources: ScoreboardSnapshot): Set<Int> {
        val out = HashSet<Int>()
        for (t in lines) out += t.claims(sources)
        return out
    }
}
