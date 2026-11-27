package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.config.cfg
import com.soulreturns.features.profit.dragon.EyePlacementTracker
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * `Eyes: 8/8 · yours 2`: summoning eyes placed (the tab's `Eyes placed` widget) and how many of them this
 * player placed ([EyePlacementTracker], rule://dragon-profit). Nothing while the dragon tracker is off or
 * the widget is absent.
 */
object DragonEyesLine : SoulLine(
    "eyes", "Dragon eyes", "Eyes placed, and how many are yours.",
    listOf("§fEyes: §d8§7/§d8 §8· §fyours §d2"), "Dragon's Nest",
) {
    override val colors = LineColors.LabelValue(0xFFFFFF, 0xFF55FF)

    private val COUNT = Regex("(\\d+)(?:\\s*/\\s*(\\d+))?")

    /** Placed and total (8 unless the widget says otherwise) from the `Eyes placed` widget. */
    fun placed(s: ScoreboardSnapshot): Pair<Int, Int>? {
        val line = s.widget("Eyes placed")?.lines?.firstOrNull()?.text ?: return null
        val m = COUNT.find(line.substringAfter(':', "")) ?: return null
        return m.groupValues[1].toInt() to (m.groupValues[2].toIntOrNull() ?: 8)
    }

    fun format(placed: Pair<Int, Int>, own: Long): String =
        "§fEyes: §d${placed.first}§7/§d${placed.second}" + if (own > 0) "${SoulText.SEP}§fyours §d$own" else ""

    override fun lines(ctx: LineContext): List<String> {
        if (!cfg.combat.dragons.enabled) return emptyList()
        val placed = placed(ctx.sources) ?: return emptyList()
        return listOf(format(placed, EyePlacementTracker.ownPending()))
    }
}
