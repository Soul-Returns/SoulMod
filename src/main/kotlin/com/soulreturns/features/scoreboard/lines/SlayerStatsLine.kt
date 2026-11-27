package com.soulreturns.features.scoreboard.lines

import com.soulreturns.data.hypixel.calc.XpTables
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardFormat

/**
 * `Voidgloom Lv7 (612k/1M)`: level and XP of the slayer you are doing, from the quest on the sidebar and
 * your profile as Hypixel's API last reported it (so the XP is as of the last profile load).
 */
object SlayerStatsLine : LineType(
    "slayerStats", ScoreboardCatalog.PROGRESS, "Slayer level",
    "Level and XP of the slayer you are doing.",
    listOf("§5Voidgloom §7Lv7 §8(§e612k§7/§a1M§8)"),
) {
    const val LEVEL = "slayerLvl"

    override val where = "During a quest"

    override val options = listOf(LineSwitch(LEVEL, "Show the level", true))

    override val colors = LineColors.Text(0xAA00AA)

    override fun lines(ctx: LineContext): List<String> {
        val idx = SlayerLine.indexes(ctx.sources)
        val boss = SlayerLine.boss(ctx.sources) ?: return emptyList()
        val def = XpTables.slayers.firstOrNull { boss.startsWith(it.bossName, ignoreCase = true) } ?: return emptyList()
        val progress = Stat.member()?.slayers?.firstOrNull { it.def.key == def.key } ?: return emptyList()
        val code = Stat.leadingColor(ctx.sources.sidebar[idx[1]].legacy) ?: "§5"
        val sb = StringBuilder(code).append(def.bossName.substringBefore(' '))
        if (ctx.options.bool(LEVEL)) sb.append(" §7Lv").append(progress.level.level)
        val xp = ScoreboardFormat.number(progress.experience, true)
        sb.append(" §8(§e").append(xp)
        if (!progress.level.maxed) sb.append("§7/§a").append(ScoreboardFormat.number(progress.level.nextLevelXp.toDouble(), true))
        sb.append("§8)")
        return listOf(sb.toString())
    }
}
