package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.config.cfg
import com.soulreturns.features.combo.ComboData
import com.soulreturns.features.combo.ComboTracker
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext

/** `Combo: 42 (+4☯ Wisdom)`: the Grandma Wolf kill combo ([ComboTracker], rule: Combo HUD) and its stacked bonuses. Nothing while the combo overlay is off or no combo runs. */
object ComboLine : SoulLine(
    "combo", "Kill combo", "Your Grandma Wolf combo and its bonus.",
    listOf("§fCombo: §a42 §8(§3+4☯ Wisdom§8)"),
) {
    override val colors = LineColors.LabelValue(0xFFFFFF, 0x55FF55)

    /** The count's tier color, as ComboData's table (5/10 green, 15 blue, 20/25 purple, 30 gold). */
    fun countColor(combo: Int): String =
        when (ComboData.milestoneOf(combo)) {
            5, 10 -> "§a"
            15 -> "§9"
            20, 25 -> "§5"
            else -> "§6"
        }

    fun bonus(wisdom: Double, coins: Double): String? {
        val parts = ArrayList<String>()
        if (wisdom > 0) parts += "§3+${SoulText.plain(wisdom)}☯ Wisdom"
        if (coins > 0) parts += "§6+${SoulText.plain(coins)} coins"
        return parts.takeIf { it.isNotEmpty() }?.joinToString("§8, ")?.let { "§8($it§8)" }
    }

    fun format(combo: Int, wisdom: Double, coins: Double): String =
        "§fCombo: " + countColor(combo) + combo + (bonus(wisdom, coins)?.let { " $it" } ?: "")

    /** The combo now, or 0 (feature off, none running). */
    fun current(): Int = if (cfg.combo.enabled) ComboTracker.displayCombo() else 0

    /** `{combo}`: `§a+15 Combo`. */
    fun placeholder(): String? = current().takeIf { it > 0 }?.let { countColor(it) + "+$it Combo" }

    override fun lines(ctx: LineContext): List<String> {
        val c = current()
        if (c <= 0) return emptyList()
        return listOf(format(c, ComboTracker.bonuses.combatWisdom, ComboTracker.bonuses.coinsPerKill))
    }
}
