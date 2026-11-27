package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * The island's weather from the tab list's weather rows (` Breeze: in 18m <icon>` = next,
 * ` Tropical Rain: for 8m <icon>` = running; captured 2026-11 in the Dwarven Mines, Hollows and Galatea,
 * rule://sounds "Crystal Hollows weather"). [com.soulreturns.features.mining.HollowsWeather] only mutes
 * the weather's sounds and keeps no state, so the tab is the source. Names are never validated; the
 * trailing private-use icon is what marks a weather row.
 */
object IslandWeatherLine : SoulLine(
    "weather", "Island weather", "Current and next weather on the island.",
    listOf("§9Rockfall §7in 20m §8· §bMist §7for 9m"), "Crystal Hollows, Galatea",
) {
    const val NEXT = "wxNext"

    override val options = listOf(LineSwitch(NEXT, "Show the next weather too", true))

    override val colors = LineColors.Text(0x5555FF)

    class Weather(val name: String, val running: Boolean, val time: String)

    private val ROW = Regex("^(.+?):\\s+(in|for)\\s+(.+?)\\s*[\\uE000-\\uF8FF]+\\s*$")

    /** Every weather row of every tab widget, in tab order. */
    fun read(s: ScoreboardSnapshot): List<Weather> {
        val out = ArrayList<Weather>()
        for (w in s.tabWidgets) for (l in w.lines) {
            val m = ROW.matchEntire(l.text.trim()) ?: continue
            out += Weather(m.groupValues[1].trim(), m.groupValues[2] == "for", m.groupValues[3].trim())
        }
        return out
    }

    private fun color(name: String): String =
        when (name.lowercase()) {
            "rockfall" -> "§9"
            "breeze" -> "§f"
            "mist" -> "§b"
            else -> "§b"
        }

    fun format(weather: List<Weather>, showNext: Boolean): String? {
        val shown = weather.filter { it.running || showNext }
        if (shown.isEmpty()) return null
        return shown.joinToString(SoulText.SEP) { color(it.name) + it.name + " §7" + (if (it.running) "for " else "in ") + it.time }
    }

    /** `{weather}`: the running weather and its time left (`§bMist §7(9m)`), else nothing. */
    fun running(s: ScoreboardSnapshot): String? = read(s).firstOrNull { it.running }?.let { color(it.name) + it.name + " §7(${it.time})" }

    override fun lines(ctx: LineContext): List<String> = listOfNotNull(format(read(ctx.sources), ctx.options.bool(NEXT)))
}
