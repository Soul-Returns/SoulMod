package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.config.cfg
import com.soulreturns.features.sacks.SackState
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import java.util.Locale

/**
 * `Sacks: +1,204 Mithril (+3 more)`: the biggest net gain of this session among the sack tracker's
 * tracked items ([SackState] sessions; rule://sacks). Nothing while the sack tracker is off.
 */
object SackGainsLine : SoulLine(
    "sacks", "Sack gains", "Biggest sack gain this session.",
    listOf("§fSacks: §a+1,204 §fMithril §8(+3 more)"),
) {
    override val colors = LineColors.LabelValue(0xFFFFFF, 0x55FF55)

    class Gain(val name: String, val color: String, val net: Long)

    fun gains(): List<Gain> {
        if (!cfg.sacks.hud) return emptyList()
        val sessions = SackState.sessionsView()
        return cfg.sacks.tracked.mapNotNull { t ->
            val net = sessions[t.id]?.net?.takeIf { it != 0L } ?: return@mapNotNull null
            Gain(t.name.ifEmpty { t.id }, LegacyText.rgbCode(t.color), net)
        }
    }

    fun format(gains: List<Gain>): String? {
        val best = gains.maxByOrNull { it.net } ?: return null
        val more = gains.size - 1
        val n = String.format(Locale.ROOT, "%,d", Math.abs(best.net))
        return "§fSacks: " + (if (best.net >= 0) "§a+$n" else "§c-$n") + " " + best.color + best.name + (if (more > 0) " §8(+$more more)" else "")
    }

    override fun lines(ctx: LineContext): List<String> = listOfNotNull(format(gains()))
}
