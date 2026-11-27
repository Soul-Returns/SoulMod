package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.features.contests.hud.ContestHud
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.ScoreboardFormat

/**
 * `Contest: #2 of 5 · 4.1M`: your place, the players ranked and your total in the running friends contest
 * you play in ([ContestHud.current], the same pick as the contest HUD; shown only in the contest type's
 * area like the HUD). Nothing when no contest runs, when you only watch it, or you are not ranked.
 */
object FriendsContestLine : SoulLine(
    "contest", "Friends contest", "Your place in a contest with friends.",
    listOf("§fContest: §6#2 §7of 5 §8· §e4.1M"), "During a contest",
) {
    override val colors = LineColors.LabelValue(0xFFFFFF, 0xFFAA00)

    class Standing(val rank: Int, val of: Int, val total: Long)

    fun format(s: Standing, short: Boolean): String =
        "§fContest: §6#${s.rank} §7of ${s.of}${SoulText.SEP}§e${ScoreboardFormat.number(s.total.toDouble(), short)}"

    fun standing(): Standing? {
        val v = ContestHud.current() ?: return null
        if (v.isSpectator) return null
        val me = v.me ?: return null
        val rank = me.rank ?: return null
        if (!(v.type.area?.let { SkyblockLocation.isInArea(it) } ?: true)) return null
        return Standing(rank, v.ranked.size, me.total)
    }

    override fun lines(ctx: LineContext): List<String> = listOfNotNull(standing()?.let { format(it, ctx.look.numbers == "short") })
}
