package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.pets.PetState
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/** The active pet as the line reads it: [rgb] is its rarity color, [level] null while only the name is known. */
class PetInfo(val name: String, val rgb: Int?, val level: Int?, val maxed: Boolean)

/**
 * `Pet: Golden Dragon [Lv200]`: the active pet. Data comes from the Pet HUD's tracker (rule://pet-hud)
 * while that HUD is on (it also reads chat and the pet menus), else from the tab list's Pet widget.
 */
object PetLine : LineType(
    "pet", ScoreboardCatalog.BUFFS, "Pet",
    "Your active pet and its level.",
    listOf("Pet: §6Golden Dragon §7[Lv200]"),
) {
    const val MAX = "petMax"
    private const val LABEL = "Pet:"

    /** Where the active pet comes from; tests replace it. */
    @Volatile
    var source: (LineContext) -> PetInfo? = ::live

    override val options = listOf(LineOptions.label(LABEL), LineSwitch(MAX, "\"Max\" at max level", true))

    override val colors = LineColors.LabelValue(0xFFFFFF, 0xFFAA00)

    override fun lines(ctx: LineContext): List<String> {
        val pet = source(ctx) ?: return emptyList()
        val level =
            when {
                pet.maxed && ctx.options.bool(MAX) -> " §7[§6Max§7]"
                pet.level != null -> " §7[Lv${pet.level}]"
                else -> ""
            }
        return listOf(ctx.labelValue(ctx.options.string("label"), LegacyText.rgbCode(pet.rgb ?: 0xFFFFFF), pet.name, level))
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf(ctx.labelValue(ctx.options.string("label"), "§8", "-"))

    private fun live(ctx: LineContext): PetInfo? {
        if (PetState.enabled()) {
            PetState.tracker.view(ctx.nowMs)?.let { v -> return PetInfo(v.name, v.rarity?.color, v.level, v.maxed) }
        }
        return ctx.sources.tab.pet?.let { p -> PetInfo(p.name, p.rarity?.color, p.level, p.maxed) }
    }
}
