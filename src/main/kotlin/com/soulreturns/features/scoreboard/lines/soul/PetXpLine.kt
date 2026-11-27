package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.pets.PetState
import com.soulreturns.features.pets.PetView
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.ScoreboardFormat

/**
 * The active pet with its level progress and XP per hour, from the Pet HUD's tracker
 * ([PetState.tracker] `view`, rule://pet-hud). Shows nothing while the Pet HUD is switched off (it
 * listens to nothing then) or no pet is known.
 */
object PetXpLine : SoulLine(
    "petxp", "Pet XP", "Active pet, level progress and XP per hour.",
    listOf("§6Golden Dragon §7Lv187 §8· §a62% §8· §b+1.2M/h"),
) {
    const val RATE = "petRate"

    override val options = listOf(LineSwitch(RATE, "XP per hour", true))

    override val colors = LineColors.Text(0xFFAA00)

    /** The tracker's view of the active pet, or null (feature off, no pet). */
    fun view(now: Long): PetView? = if (PetState.enabled()) PetState.tracker.view(now) else null

    fun rate(per: Double): String = "§b+" + ScoreboardFormat.number(per, true) + "/h"

    fun format(v: PetView, withRate: Boolean): String {
        val name = (v.rarity?.let { LegacyText.rgbCode(it.color) } ?: "§f") + v.name
        val parts = arrayListOf(name + (v.level?.let { " §7Lv$it" } ?: ""))
        if (v.maxed) parts += "§aMAX" else v.fraction?.let { parts += "§a${(it * 100).toInt()}%" }
        if (withRate) v.rate?.let { parts += rate(it) }
        return parts.joinToString(SoulText.SEP)
    }

    override fun lines(ctx: LineContext): List<String> = listOfNotNull(view(ctx.nowMs)?.let { format(it, ctx.options.bool(RATE)) })
}
