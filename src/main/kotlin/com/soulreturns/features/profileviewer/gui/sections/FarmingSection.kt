package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.JacobCrop
import com.soulreturns.data.hypixel.gardenPlayer
import com.soulreturns.data.hypixel.jacobsContest
import com.soulreturns.data.items.CollectionIcons
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Cell
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Col
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.TableRow
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Jacob's Contests, copper and larva; the Garden itself comes later (mock `farmingSec`). */
object FarmingSection : ProfileSection {
    override val id = "farming"
    override val title = "Farming"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_sprout"
    override val subtitle = "Jacob's Contests and the Garden"

    /** Anita's Double Drops perk levels, and the Farming level cap perk levels (`50 + n`). */
    private const val DOUBLE_DROPS_MAX = 15
    private const val LEVEL_CAP_PERKS_MAX = 10

    /** Mock `BC`: bracket colors. */
    private val BRACKET_COLORS = mapOf(
        "bronze" to 0xFFC0835A.toInt(),
        "silver" to 0xFFC8C8C8.toInt(),
        "gold" to (Rich.mcColor('6') ?: KitStyle.TEXT),
        "platinum" to 0xFF7FD3E0.toInt(),
        "diamond" to (Rich.mcColor('b') ?: KitStyle.TEXT),
    )

    override fun build(ctx: ProfileContext, ui: Ui) {
        val j = ctx.member.jacobsContest
        val garden = ctx.member.gardenPlayer
        val farming = ctx.member.skillsCapped(ctx.caps).firstOrNull { it.def.key == "farming" }
        ui.tiles {
            if (farming != null) {
                val perks = j?.let { " · ${it.farmingLevelCapPerk}/$LEVEL_CAP_PERKS_MAX level perks" } ?: ""
                tile("Farming", "${farming.level.level}", sub = "cap ${ctx.member.effectiveCap(farming.def, ctx.caps)}$perks", bar = farming.level.progress.toFloat(), maxed = farming.level.maxed)
            } else {
                tile("Farming", "—", sub = "Skills API off", locked = true)
            }
            tile("Contests", j?.let { Format.num(it.contests) } ?: "—", sub = "Jacob's Farming Contests")
            if (j != null) {
                val dot = Rich.colored(" · ", KitStyle.TEXT_FAINT)
                val medals = Rich.join(
                    Rich.colored("${j.medalsGold}", BRACKET_COLORS.getValue("gold")), dot,
                    Rich.colored("${j.medalsSilver}", BRACKET_COLORS.getValue("silver")), dot,
                    Rich.colored("${j.medalsBronze}", BRACKET_COLORS.getValue("bronze")),
                )
                tile("Medals", medals, sub = "gold · silver · bronze to spend")
                tile("Double drops", "${j.doubleDrops} / $DOUBLE_DROPS_MAX", sub = "Anita's perk")
            } else {
                tile("Medals", "—", sub = "gold · silver · bronze to spend")
                tile("Double drops", "—", sub = "Anita's perk")
            }
            tile("Copper", garden?.copper?.let { Format.num(it) } ?: "—", sub = garden?.larvaConsumed?.let { "${Format.num(it)} larva consumed" })
        }
        ui.grid(300) {
            card("Crops", span = 2, sub = "best contest bracket and personal best") {
                if (j == null) {
                    empty("pv_sprout", "No Jacob's contests entered", "")
                } else {
                    table(
                        listOf(Col("Crop"), Col("Best bracket"), Col("Personal best")),
                        j.crops.map { cropRow(it) },
                    )
                }
            }
            later("Garden", "Plots, visitors, crop milestones and the composter live on a separate Hypixel endpoint that Soul's server doesn't fetch yet.")
        }
    }

    private fun cropRow(crop: JacobCrop): TableRow {
        val bracket = crop.bracket
        val color = bracket?.let { BRACKET_COLORS[it] }
        return TableRow(
            listOf(
                Cell.of(crop.name, bold = true, icon = CollectionIcons.collection(crop.id, crop.name)),
                if (bracket == null || color == null) Cell.DASH else Cell(chips = listOf(Chip(bracket.uppercase(), color, KitStyle.CHIP_BG, color))),
                crop.personalBest?.let { Cell.of(Format.num(it)) } ?: Cell.DASH,
            ),
        )
    }
}
