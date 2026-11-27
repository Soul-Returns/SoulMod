package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.config.cfg
import com.soulreturns.features.mining.nucleus.CrystalState
import com.soulreturns.features.mining.nucleus.NucleusRuns
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext

/**
 * `Nucleus: 3/5 crystals · 12:04`: crystals placed in the run in progress and its timer
 * ([NucleusRuns] `run`, rule://nucleus). The run is only kept while the Nucleus tracker or checklist is
 * on, so the line shows nothing otherwise, and nothing outside the Crystal Hollows.
 */
object NucleusRunLine : SoulLine(
    "nucleus", "Nucleus run", "Crystals placed and run time.",
    listOf("§fNucleus: §a3§7/§a5 §7crystals §8· §f12:04"), "Crystal Hollows",
) {
    override val colors = LineColors.LabelValue(0xFFFFFF, 0x55FF55)

    fun format(placed: Int, total: Int, elapsedMs: Long?): String =
        "§fNucleus: §a$placed§7/§a$total §7crystals" + (elapsedMs?.let { "${SoulText.SEP}§f${SoulText.clock(it)}" } ?: "")

    override fun lines(ctx: LineContext): List<String> {
        if (!NucleusRuns.inHollows() || !(NucleusRuns.trackerOn() || cfg.mining.nucleus.checklist)) return emptyList()
        val run = NucleusRuns.handle.read().run
        return listOf(format(run.count(CrystalState.PLACED), run.foundTotal, NucleusRuns.runElapsedMs()))
    }
}
