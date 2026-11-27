package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * A tab list widget, shown as Hypixel draws it (header and body lines), optionally tinted. One class
 * for every widget: [TabWidgetCatalog] lists one instance per widget. [widget] and [alsoWidgets] are
 * the header names Hypixel prints (matched case-insensitively, never validated: a widget Hypixel
 * renames simply shows nothing); every widget with one of those names is drawn, in tab order.
 */
class TabWidgetLine(
    id: String,
    val widget: String,
    name: String,
    description: String,
    example: List<String>,
    override val where: String? = null,
    /** More header names that belong to this line (the Dragon line also draws `Eyes placed`). */
    val alsoWidgets: List<String> = emptyList(),
    /** Two lines share the `Event` header; the island decides which one draws it. */
    val scope: Scope = Scope.ANYWHERE,
    /** The header names come from Soul's own captures (else from SkyHanni's patterns, unverified). */
    val verified: Boolean = false,
) : LineType(id, ScoreboardCatalog.TAB_LIST, name, description, example) {
    enum class Scope { ANYWHERE, MINING, NOT_MINING }

    private val names: List<String> = listOf(widget) + alsoWidgets

    override val tag = "tab list"

    override val colors = LineColors.Tint

    override fun lines(ctx: LineContext): List<String> {
        if (scope != Scope.ANYWHERE && isMining(ctx.sources) != (scope == Scope.MINING)) return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (w in ctx.sources.tabWidgets) {
            val key = w.name.lowercase()
            if (names.none { it.equals(w.name, ignoreCase = true) } || !seen.add(key)) continue
            for (l in w.lines) out += l.legacy
        }
        return out
    }

    companion object {
        /** Mining islands: Dwarven Mines, Crystal Hollows, Glacite areas (mode `mining_*` / `crystal_hollows`). */
        fun isMining(s: ScoreboardSnapshot): Boolean {
            val mode = s.mode
            if (mode != null && (mode.startsWith("mining") || mode == "crystal_hollows")) return true
            val a = s.area ?: return false
            return a.contains("Mines", true) || a.contains("Crystal Hollows", true) || a.contains("Glacite", true)
        }
    }
}
