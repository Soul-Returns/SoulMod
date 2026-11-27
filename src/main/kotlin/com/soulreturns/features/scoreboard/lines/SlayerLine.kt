package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * `Slayer Quest` and the lines under it (boss, kills or Combat XP, `Slay the boss!`), as Hypixel draws them
 * while a quest runs. "Only in slayer areas" hides it away from the boss's area; a boss whose area is not
 * known always shows.
 */
object SlayerLine : LineType(
    "slayer", ScoreboardCatalog.PROGRESS, "Slayer quest",
    "The running slayer quest and its kills.",
    listOf("Slayer Quest", " §cVoidgloom Seraph III", " §e12§7/§c120 §7Kills"),
) {
    const val AREA = "slayerArea"
    private const val HEADING = "Slayer Quest"

    override val where = "During a quest"

    override val options = listOf(LineSwitch(AREA, "Only in slayer areas", false))

    override val colors = LineColors.HeadingLabels(0xFFFFFF, 0xFF5555)

    /** Sidebar indexes of the quest: the heading and up to two lines under it. */
    fun indexes(s: ScoreboardSnapshot): List<Int> {
        val h = s.sidebar.indexOfFirst { it.text.trim() == HEADING }
        if (h < 0) return emptyList()
        val out = arrayListOf(h)
        var i = h + 1
        while (i < s.sidebar.size && out.size < 3 && s.sidebar[i].text.isNotBlank() && s.fields.footerIndex != i) out += i++
        return out
    }

    /** The boss line of the quest (`Voidgloom Seraph III`), color-stripped, or null. */
    fun boss(s: ScoreboardSnapshot): String? =
        indexes(s).getOrNull(1)?.let { s.sidebar[it].text.trim().removePrefix("-").trim() }?.takeIf { it.isNotEmpty() }

    /** Where each boss lives: (island names, sublocations); a match on either keeps the quest visible. */
    private fun inArea(s: ScoreboardSnapshot, boss: String): Boolean {
        val island = s.area.orEmpty()
        val sub = s.subLocation.orEmpty()
        fun any(vararg parts: String, text: String) = parts.any { text.contains(it, ignoreCase = true) }
        return when {
            boss.startsWith("Revenant") -> any("Graveyard", "Coal Mine", text = sub) || any("Hub", "Deep Caverns", text = island)
            boss.startsWith("Tarantula") -> any("Spider", text = island) || any("Spider", text = sub)
            boss.startsWith("Sven") -> any("Ruins", "Howling", text = sub) || any("Hub", text = island)
            boss.startsWith("Voidgloom") -> any("End", text = island)
            boss.startsWith("Inferno") -> any("Crimson", text = island)
            boss.startsWith("Riftstalker") -> any("Rift", text = island)
            else -> true
        }
    }

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = indexes(sources)

    override fun lines(ctx: LineContext): List<String> {
        val idx = indexes(ctx.sources)
        if (idx.isEmpty()) return emptyList()
        if (ctx.options.bool(AREA)) boss(ctx.sources)?.let { if (!inArea(ctx.sources, it)) return emptyList() }
        return idx.map { ctx.sources.sidebar[it].legacy }
    }
}
