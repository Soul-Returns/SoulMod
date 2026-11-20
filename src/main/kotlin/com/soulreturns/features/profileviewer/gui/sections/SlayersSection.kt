package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.SlayerProgress
import com.soulreturns.data.hypixel.calc.XpTables
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Box
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.Ui

/**
 * Every slayer boss: level, XP and kills per tier (mock `slayersSec`). The worked example of
 * `mocks/profile-viewer/IMPLEMENTATION.md`.
 */
object SlayersSection : ProfileSection {
    override val id = "slayers"
    override val title = "Slayers"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_sword"

    /** Boss tiers per slayer (mock `SLAYERS`): Sven, Voidgloom and Inferno stop at IV. */
    private val TIERS = mapOf("zombie" to 5, "spider" to 5, "wolf" to 4, "enderman" to 4, "blaze" to 4, "vampire" to 5)

    override fun badge(ctx: ProfileContext): String? =
        ctx.member.totalSlayerXp.takeIf { it > 0 }?.let { Format.short(it) }

    /** All six bosses in game order, the ones never fought at level 0 (the response omits them). */
    fun all(ctx: ProfileContext): List<SlayerProgress> {
        val fought = ctx.member.slayers.associateBy { it.def.key }
        return XpTables.slayers.map { def -> fought[def.key] ?: SlayerProgress(def, 0.0, def.curve.level(0.0), List(5) { 0 }) }
    }

    override fun build(ctx: ProfileContext, ui: Ui) {
        val slayers = all(ctx)
        ui.tiles {
            tile("Slayer XP", Format.short(slayers.sumOf { it.experience }), sub = "all six bosses")
            tile("Bosses killed", Format.num(slayers.sumOf { it.totalKills }), sub = "every tier")
            tile("Maxed", "${slayers.count { it.level.maxed }} / ${slayers.size}", sub = "at their top level")
        }
        ui.grid(250) {
            for (s in slayers) {
                card(s.def.bossName) {
                    val l = s.level
                    bigStat(
                        "${l.level}", l.progress.toFloat(), l.maxed,
                        if (l.maxed) "${Format.num(s.experience)} XP · max level" else "${Format.num(s.experience)} / ${Format.num(l.nextLevelXp)} XP",
                    )
                    space(3)
                    boxes(
                        s.bossKills.take(TIERS[s.def.key] ?: 5).mapIndexed { i, kills ->
                            Box(Format.roman(i + 1), if (kills > 0) Format.short(kills.toLong()) else "—", dim = kills == 0)
                        },
                    )
                }
            }
        }
    }
}
