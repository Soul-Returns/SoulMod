package com.soulreturns.features.profileviewer.gui.tabs

import com.soulreturns.config.gui.Theme
import com.soulreturns.data.hypixel.calc.LevelCalculator
import com.soulreturns.features.profileviewer.gui.ProfileCanvas
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileTab

/**
 * Slayers: level and XP per boss, plus the tier-by-tier kill counts underneath each one.
 *
 * Bosses are named the way players name them ("Voidgloom Seraph", not "enderman") — that is what
 * [com.soulreturns.data.hypixel.calc.SlayerDef.bossName] is for. A slayer with an entry but no XP is
 * still listed, because Hypixel writing the key at all means the player has been to the boss.
 */
object SlayersTab : ProfileTab {
    override val id = "slayers"
    override val label = "Slayers"

    /** Roman numerals for the five boss tiers — how the game itself labels them. */
    private val TIERS = listOf("I", "II", "III", "IV", "V")

    override fun draw(c: ProfileCanvas, ctx: ProfileContext) {
        val slayers = ctx.member.slayers
        if (slayers.isEmpty()) {
            c.section("Slayers") {
                note("${ctx.identity.name} has no slayer data on ${ctx.profile.cuteName}.")
            }
            return
        }

        val total = ctx.member.totalSlayerXp
        c.section("Slayers", "Total ${LevelCalculator.formatXp(total)} XP") {
            for (slayer in slayers) {
                levelRow(slayer.def.bossName, slayer.level, slayer.experience)
            }
        }

        c.section("Boss kills") {
            val killed = slayers.filter { it.totalKills > 0 }
            if (killed.isEmpty()) {
                note("No boss kills recorded.")
                return@section
            }
            // Only as many tier columns as anyone actually reached — Riftstalker has fewer than the
            // others, and an all-dash column is noise.
            val tiers = killed.maxOf { s -> s.bossKills.indexOfLast { it > 0 } + 1 }.coerceAtLeast(1)
            val nameW = (width - tiers * 42).coerceAtLeast(90)
            val widths = IntArray(tiers + 1) { if (it == 0) nameW else 42 }
            tableRow(listOf("Boss") + TIERS.take(tiers), widths, header = true)
            for (slayer in killed) {
                tableRow(
                    listOf(slayer.def.bossName) +
                        (0 until tiers).map { tier ->
                            val kills = slayer.bossKills.getOrElse(tier) { 0 }
                            if (kills > 0) kills.toString() else "—"
                        },
                    widths,
                )
            }
            divider()
            statRow("Total bosses", killed.sumOf { it.totalKills }.toString(), Theme.TEXT, bold = true)
        }
    }
}
