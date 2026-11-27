package com.soulreturns.features.scoreboard.lines

import com.soulreturns.data.hypixel.powder
import com.soulreturns.features.scoreboard.LineChoice
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot
import java.util.Locale

/**
 * `Powder` and one line per powder. Available powder is the tab list's `Powders` widget, total (available
 * plus spent in the tree) comes from your profile as Hypixel's API last reported it. "Hypixel's own powder
 * line" shows the `᠅ Mithril: 2,963,902` line the sidebar draws in the Crystal Hollows and Galatea instead.
 */
object PowderLine : LineType(
    "powder", ScoreboardCatalog.PROGRESS, "Powder",
    "HOTM powder; HOTF whispers on Galatea.",
    listOf("Powder", " §2᠅ §fMithril: §22,988,324"),
) {
    const val SHOW = "powderShow"
    const val OWN = "hyPowder"

    /** Hypixel's own line: `᠅ Mithril: 2,963,902` (a trailing ` Powder` in the name is optional). */
    private val OWN_LINE = Regex("᠅\\s*([A-Za-z ]+?)(?: Powder)?:?\\s*([\\d,.]+[kMB]?)?(?:\\s*\\(\\+[^)]*\\))?")
    private val WIDGET_LINE = Regex("([A-Za-z ]+?)(?: Powder)?:\\s*([\\d,]+)")
    private val DEFAULT_CODES = mapOf("mithril" to "§2", "gemstone" to "§d", "glacite" to "§b")

    override val where = "Mining islands"

    override val options =
        listOf(
            LineChoice(SHOW, "Show", listOf(LineChoice.Choice("current", "Available"), LineChoice.Choice("total", "Total"), LineChoice.Choice("both", "Both")), "current", segmented = true),
            LineSwitch(OWN, "Hypixel's own powder line", true, "Whispers on Galatea"),
        )

    override val colors = LineColors.HeadingLabels(0xFFFFFF, 0x00AA00)

    fun ownIndexes(s: ScoreboardSnapshot): List<Int> = s.sidebar.indices.filter { OWN_LINE.matchEntire(s.sidebar[it].text.trim()) != null }

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = ownIndexes(sources)

    private class Row(val name: String, val code: String, val available: Double)

    private fun rows(s: ScoreboardSnapshot): List<Row> {
        val w = s.widget("Powders") ?: return emptyList()
        return w.lines.drop(1).mapNotNull { l ->
            val m = WIDGET_LINE.matchEntire(l.text.trim()) ?: return@mapNotNull null
            val name = m.groupValues[1].trim()
            val v = Stat.parse(m.groupValues[2]) ?: return@mapNotNull null
            Row(name, Stat.leadingColor(l.legacy.substringAfter(':').trimStart()) ?: DEFAULT_CODES[name.lowercase(Locale.ROOT)] ?: "§f", v)
        }
    }

    override fun lines(ctx: LineContext): List<String> {
        val own = ownIndexes(ctx.sources)
        if (ctx.options.bool(OWN) && own.isNotEmpty()) return listOf("Powder") + own.map { ctx.sources.sidebar[it].legacy }
        val rows = rows(ctx.sources)
        if (rows.isEmpty()) return emptyList()
        val mode = ctx.options.string(SHOW)
        val member = Stat.member()
        val out = arrayListOf("Powder")
        for (r in rows) {
            val total = member?.powder(r.name.lowercase(Locale.ROOT))?.total?.toDouble()
            val value =
                when {
                    mode == "total" && total != null -> ctx.number(total)
                    mode == "both" && total != null -> "${ctx.number(r.available)}§7/${r.code}${ctx.number(total)}"
                    else -> ctx.number(r.available)
                }
            out += " ${r.code}᠅ §f${r.name}: ${r.code}$value"
        }
        return out
    }
}
