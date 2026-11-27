package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.mining.nucleus.BalState
import com.soulreturns.features.mining.nucleus.NucleusRuns
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext

/** `Bal: up!`: whether Bal (the Khazad-dûm boss) is up, spawning or down ([BalState], rule://nucleus). Nothing while the Bal helper is off, outside the Crystal Hollows or before a phase is known. */
object BalLine : SoulLine(
    "bal", "Bal", "Whether Bal is up.",
    listOf("§fBal: §cup! §7(Magma Fields)"), "Crystal Hollows",
) {
    override val colors = LineColors.LabelValue(0xFFFFFF, 0xFF5555)

    fun format(phase: BalState.Phase, approximate: Boolean): String? =
        when (phase) {
            BalState.Phase.UP -> "§fBal: §cup!"
            BalState.Phase.SPAWNING -> "§fBal: §espawning"
            BalState.Phase.DOWN -> "§fBal: §adown" + if (approximate) " §7(about)" else ""
            BalState.Phase.UNKNOWN -> null
        }

    override fun lines(ctx: LineContext): List<String> {
        if (!BalState.enabled() || !NucleusRuns.inHollows()) return emptyList()
        return listOfNotNull(format(BalState.phase, BalState.approximate))
    }
}
