package com.soulreturns.features.scoreboard.lines

import com.soulreturns.data.hypixel.activeTuning
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineNumber
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/**
 * `Tuning: ❁34, ⚔20, and ☣7`: the points of your active Stats Tuning, from your profile as Hypixel's API
 * last reported it. Compact is one line; otherwise a heading and one line per stat.
 */
object TuningsLine : LineType(
    "tunings", ScoreboardCatalog.PROGRESS, "Maxwell tunings",
    "Your tuning points.",
    listOf("Tuning: §c❁34§7, §e⚔20§7, and §9☣7"),
) {
    const val COMPACT = "tunCompact"
    const val COUNT = "tunN"
    private const val LABEL = "Tuning:"

    /** Stat name to its icon and color code, as Hypixel draws them. */
    private val STATS =
        mapOf(
            "Health" to ("❤" to "§c"), "Defense" to ("❈" to "§a"), "Speed" to ("✦" to "§f"), "Strength" to ("❁" to "§c"),
            "Crit Chance" to ("☣" to "§9"), "Crit Damage" to ("☠" to "§9"), "Attack Speed" to ("⚔" to "§e"), "Intelligence" to ("✎" to "§b"),
        )

    override val options =
        listOf(LineSwitch(COMPACT, "Compact", true), LineNumber(COUNT, "Tunings shown", 2, 1, 8), LineOptions.label(LABEL))

    override val colors = LineColors.LabelValue(0xFFFFFF, 0xFF5555)

    override fun lines(ctx: LineContext): List<String> {
        val tunings = Stat.member()?.activeTuning?.take(ctx.options.int(COUNT)) ?: return emptyList()
        if (tunings.isEmpty()) return emptyList()
        val label = ctx.options.string("label")
        fun parts(t: com.soulreturns.data.hypixel.TuningPoint): Triple<String, String, String> {
            val (icon, code) = STATS[t.stat] ?: ("" to "§f")
            return Triple(icon, code, t.points.toString())
        }
        if (ctx.options.bool(COMPACT)) {
            val cells = tunings.map { t -> parts(t).let { (icon, code, n) -> "$code$icon$n" } }
            val joined =
                when (cells.size) {
                    1 -> cells[0]
                    2 -> "${cells[0]}§7 and ${cells[1]}"
                    else -> cells.dropLast(1).joinToString("§7, ") + "§7, and " + cells.last()
                }
            return listOf(ctx.labelValue(label, "", joined))
        }
        return listOf(label.removeSuffix(":") + ":") + tunings.map { t -> parts(t).let { (icon, code, n) -> " §7- §f${t.stat}: $code$icon$n" } }
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf(ctx.labelValue(ctx.options.string("label"), "§8", "-"))
}
