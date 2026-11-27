package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.config.cfg
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.features.mining.mineshaft.MineshaftCorpses
import com.soulreturns.features.mining.mineshaft.MineshaftState
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext

/**
 * `Corpses: 2/4 looted · Lapis`: corpses looted of those found in this mineshaft, and the best one still
 * to loot ([MineshaftCorpses] tab scan, colors of the corpse HUD; rule://mineshaft). Types are an open
 * set: the best-first order below only ranks the ones it knows, any other type ranks last but still counts.
 */
object MineshaftCorpsesLine : SoulLine(
    "corpses", "Mineshaft corpses", "Corpses found and looted, and the best one.",
    listOf("§fCorpses: §a2§7/§a4 §7looted §8· §9Lapis"), "Glacite Mineshafts",
) {
    override val colors = LineColors.LabelValue(0xFFFFFF, 0x55FF55)

    private val RANK = listOf("vanguard", "tungsten", "umber", "lapis")

    private fun rank(type: String): Int = RANK.indexOf(type.lowercase()).let { if (it < 0) RANK.size else it }

    /** The best type that still has an unlooted corpse. */
    fun best(byType: Map<String, MineshaftCorpses.Counts>): String? =
        byType.filter { it.value.unlooted > 0 }.keys.minWithOrNull(compareBy<String> { rank(it) }.thenBy { it })

    fun format(byType: Map<String, MineshaftCorpses.Counts>, color: (String) -> String): String? {
        if (byType.isEmpty()) return null
        val total = byType.values.sumOf { it.total }
        val looted = byType.values.sumOf { it.looted }
        val best = best(byType)
        return "§fCorpses: §a$looted§7/§a$total §7looted" + (best?.let { "${SoulText.SEP}${color(it)}$it" } ?: "")
    }

    override fun lines(ctx: LineContext): List<String> {
        if (!SkyblockLocation.isInArea(MineshaftState.AREA)) return emptyList()
        val colors = cfg.mining.mineshaft.corpsesHudColors
        return listOfNotNull(format(MineshaftCorpses.byType) { LegacyText.code(colors.of(it)) })
    }
}
