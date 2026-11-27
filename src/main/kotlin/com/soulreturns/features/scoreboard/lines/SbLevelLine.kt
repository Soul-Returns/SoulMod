package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/** `SB Level: [171] 97/100 XP`: your SkyBlock level and the XP to the next one, from the tab list. */
object SbLevelLine : LineType(
    "sblevel", ScoreboardCatalog.PROGRESS, "SkyBlock level",
    "Your level and XP to the next one.",
    listOf("SB Level: §b[171] §797/100 XP"),
) {
    private val LEVEL_CODE = Regex("§([0-9a-f])\\d+§8]", RegexOption.IGNORE_CASE)

    override val colors = LineColors.LabelValue(0xFFFFFF, 0x55FFFF)

    /** The level's color as Hypixel draws it in the tab list (it changes with the level). */
    private fun levelCode(ctx: LineContext): String {
        for (w in ctx.sources.tabWidgets) for (l in w.lines) {
            if ("SB Level" in l.text) LEVEL_CODE.find(l.legacy)?.let { return "§" + it.groupValues[1] }
        }
        return "§b"
    }

    override fun lines(ctx: LineContext): List<String> {
        val lvl = ctx.sources.tab.skyblockLevel ?: return emptyList()
        val xp = if (lvl.xp != null && lvl.xpForNext != null) " §7${lvl.xp}/${lvl.xpForNext} XP" else ""
        return listOf(ctx.labelValue("SB Level:", levelCode(ctx), "[${lvl.level}]$xp"))
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf(ctx.labelValue("SB Level:", "§8", "-"))
}
