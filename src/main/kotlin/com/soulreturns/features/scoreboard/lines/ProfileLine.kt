package com.soulreturns.features.scoreboard.lines

import com.soulreturns.data.skyblock.ProfileType
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * `♲ Ironman`: the profile's game mode (Ironman, Stranded, Bingo) with Hypixel's symbol, or the profile's
 * name. A normal profile has no mode, so without "Profile name" there is nothing to show.
 */
object ProfileLine : LineType(
    "profile", ScoreboardCatalog.TIME_PLACE, "Profile",
    "Profile type (Ironman, Stranded, Bingo) or its name.",
    listOf("§7♲ Ironman"),
) {
    const val NAME = "profName"
    private val SHAPE = Regex("[♲☀Ⓑ]\\s*(?:Ironman|Stranded|Bingo)")

    override val options = listOf(LineSwitch(NAME, "Profile name instead of type", false))

    override val colors = LineColors.Text(0xAAAAAA)

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> =
        sources.sidebar.indices.filter { SHAPE.containsMatchIn(sources.sidebar[it].text) }

    /** Symbol and color code of a game mode, or null for profiles without one. */
    private fun mode(type: ProfileType): Triple<String, String, String>? =
        when (type) {
            ProfileType.IRONMAN -> Triple("♲", "§7", "Ironman")
            ProfileType.STRANDED -> Triple("☀", "§a", "Stranded")
            ProfileType.BINGO -> Triple("Ⓑ", "§9", "Bingo")
            else -> null
        }

    /** The text without the symbol (`Ironman` or the profile name), for `{profile}`. */
    fun label(s: ScoreboardSnapshot, byName: Boolean): Pair<String, String>? {
        val m = mode(s.profileType)
        if (byName) {
            val name = s.profile ?: return null
            return (m?.second ?: "§a") to name
        }
        return m?.let { it.second to it.third }
    }

    override fun lines(ctx: LineContext): List<String> {
        val byName = ctx.options.bool(NAME)
        val (code, text) = label(ctx.sources, byName) ?: return emptyList()
        val symbol = mode(ctx.sources.profileType)?.first
        return listOf(if (symbol != null) "$code$symbol $code$text" else "$code$text")
    }
}
