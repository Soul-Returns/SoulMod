package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext

/** `Mimic ✔ · Prince ✖`: whether this run's Mimic and Prince are dead ([DungeonRunState], chat and party sync; rule://dungeons). Both are listed in any run: Soul does not know which floors have them. */
object MimicPrinceLine : SoulLine(
    "mimic", "Mimic & Prince", "Whether the Mimic and Prince are dead.",
    listOf("§7Mimic §a✔ §8· §7Prince §c✖"), "Dungeons",
) {
    override val colors = LineColors.Text(0xAAAAAA)

    fun format(mimic: Boolean, prince: Boolean): String = "§7Mimic ${mark(mimic)}${SoulText.SEP}§7Prince ${mark(prince)}"

    private fun mark(dead: Boolean) = if (dead) "§a✔" else "§c✖"

    override fun lines(ctx: LineContext): List<String> =
        if (DungeonRunState.inRun) listOf(format(DungeonRunState.mimicKilled, DungeonRunState.princeKilled)) else emptyList()
}
