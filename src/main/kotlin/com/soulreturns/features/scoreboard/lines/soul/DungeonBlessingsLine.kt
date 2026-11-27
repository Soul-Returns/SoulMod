package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * `Power XII · Life IX · Wisdom VIII`: the blessings collected this run, from the tab list's footer
 * (`Dungeon Buffs`, then one `Blessing of <Name> <Level>` per line, or `No Buffs active.` when none;
 * SkyHanni DungeonApi and Skyblocker DungeonBuffWidget show the same footer). Names and levels are read
 * as printed, never validated; the name colors below are only cosmetic.
 */
object DungeonBlessingsLine : SoulLine(
    "blessings", "Dungeon blessings", "Blessings collected this run.",
    listOf("§7Power §cXII §8· §7Life §aIX §8· §7Wisdom §bVIII"), "Dungeons",
) {
    override val colors = LineColors.Text(0xAAAAAA)

    class Blessing(val name: String, val level: String)

    private val BLESSING = Regex("Blessing of (\\w+) (\\w+)")

    /** The blessings listed under `Dungeon Buffs` in the footer, in its order. */
    fun read(s: ScoreboardSnapshot): List<Blessing> {
        val start = s.tabFooter.indexOfFirst { it.text.contains("Dungeon Buffs") }
        if (start < 0) return emptyList()
        return s.tabFooter.drop(start + 1).mapNotNull { l -> BLESSING.find(l.text)?.let { Blessing(it.groupValues[1], it.groupValues[2]) } }
    }

    private fun color(name: String): String =
        when (name.lowercase()) {
            "power" -> "§c"
            "life" -> "§a"
            "wisdom" -> "§b"
            "love" -> "§d"
            "stone" -> "§7"
            "time" -> "§9"
            else -> "§f"
        }

    fun format(list: List<Blessing>): String? =
        if (list.isEmpty()) null else list.joinToString(SoulText.SEP) { "§7${it.name} ${color(it.name)}${it.level}" }

    override fun lines(ctx: LineContext): List<String> = listOfNotNull(format(read(ctx.sources)))
}
