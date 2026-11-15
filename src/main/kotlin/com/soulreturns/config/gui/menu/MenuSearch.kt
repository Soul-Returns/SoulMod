package com.soulreturns.config.gui.menu

import com.soulreturns.config.gui.ConfigRegistry
import com.soulreturns.config.gui.model.ActionOption
import com.soulreturns.config.gui.model.ConfigOption
import com.soulreturns.config.gui.model.FeatureCard
import com.soulreturns.config.gui.model.KeybindOption
import com.soulreturns.config.gui.model.SettingsSection
import com.soulreturns.config.gui.model.SettingsTab
import com.soulreturns.config.gui.model.SoundOption

/**
 * The search palette (Ctrl+K), ported from the mock's `index()` / `score()` / `results()`:
 * features, every option row (feature panels + Settings) and pages (activities, their tabs,
 * Settings tabs).
 */
class SearchItem(
    val kind: Kind,
    val name: String,
    val path: String,
    val desc: String,
    val synonyms: List<String>,
    val icon: String,
    val card: FeatureCard? = null,
    val option: ConfigOption? = null,
    val advanced: Boolean = false,
    /** Page targets: an activity (+ tab) or a Settings tab (+ section). */
    val activity: String? = null,
    val tab: String? = null,
    val settingsTab: String? = null,
    val section: String? = null,
) {
    enum class Kind(val label: String) { FEATURE("FEATURE"), SETTING("SETTING"), PAGE("PAGE") }

    var score = 0.0
}

class SearchGroup(val title: String, val items: List<SearchItem>)

object MenuSearch {
    /** Rows per group (PALETTE_LIMITS). */
    private val LIMITS = mapOf(SearchItem.Kind.FEATURE to 6, SearchItem.Kind.SETTING to 6, SearchItem.Kind.PAGE to 4)
    private val WORD_SPLIT = Regex("[\\s/·›'()-]+")

    fun index(cards: List<FeatureCard>, settings: List<SettingsTab>, synonyms: Map<ConfigOption, List<String>> = emptyMap()): List<SearchItem> {
        val out = ArrayList<SearchItem>()
        for (c in cards) {
            val path = ConfigRegistry.pathOf(c)
            out += SearchItem(SearchItem.Kind.FEATURE, c.name, path, "${c.line} ${c.description}", c.synonyms + if (c.beta) listOf("beta", "work in progress") else emptyList(), c.icon, card = c)
            for ((list, adv) in listOf(c.simple to false, c.advanced to true)) {
                for (o in list) {
                    // Every HUD has a Position row; the feature result covers it.
                    if (o is ActionOption && o.actionId == ConfigRegistry.MOVE_GUI) continue
                    out += SearchItem(SearchItem.Kind.SETTING, o.label, "$path › ${c.name}", o.description.orEmpty(), emptyList(), iconOf(o), card = c, option = o, advanced = adv)
                }
            }
        }
        for (t in settings) {
            out += SearchItem(SearchItem.Kind.PAGE, "Settings › ${t.name}", t.sections.joinToString(", ") { it.title }, "", t.synonyms, t.icon, settingsTab = t.id)
            for (s in t.sections) {
                for ((list, adv) in listOf(s.simple to false, s.advanced to true)) {
                    for (o in list) out += settingItem(t, s, o, adv, synonyms[o].orEmpty())
                }
            }
        }
        for (a in ConfigRegistry.activities) {
            val tabs = ConfigRegistry.tabsOf(a.id)
            out += SearchItem(SearchItem.Kind.PAGE, a.name, tabs.joinToString(" · ") { it.name }, "", emptyList(), a.icon, activity = a.id)
            for (t in tabs) {
                val n = ConfigRegistry.cardsIn(a.id, t.id).size
                out += SearchItem(SearchItem.Kind.PAGE, "${a.name} › ${t.name}", "$n feature${if (n == 1) "" else "s"}", "", emptyList(), a.icon, activity = a.id, tab = t.id)
            }
        }
        return out
    }

    private fun settingItem(t: SettingsTab, s: SettingsSection, o: ConfigOption, adv: Boolean, syn: List<String>) =
        SearchItem(SearchItem.Kind.SETTING, o.label, "Settings › ${t.name} › ${s.title}", o.description.orEmpty(), syn, t.icon, option = o, advanced = adv, settingsTab = t.id, section = s.id)

    private fun iconOf(o: ConfigOption): String = if (o is KeybindOption) "keys" else if (o is SoundOption) "music" else "sliders"

    /** Every token must hit name, synonym, path or description; earlier fields weigh more. 0 = no match. */
    fun score(item: SearchItem, tokens: List<String>): Double {
        var s = 0.0
        val n = item.name.lowercase()
        val p = item.path.lowercase()
        val d = item.desc.lowercase()
        val syn = item.synonyms.map { it.lowercase() }
        for (t in tokens) {
            val b =
                if (n.startsWith(t)) 6.0
                else if (n.split(WORD_SPLIT).any { it.startsWith(t) }) 5.0
                else if (n.contains(t)) 4.0
                else if (syn.any { w -> w.startsWith(t) || w.split(' ').any { it.startsWith(t) } }) 3.5
                else if (syn.any { it.contains(t) }) 3.0
                else if (p.contains(t)) 2.0
                else if (d.contains(t)) 1.0
                else 0.0
            if (b == 0.0) return 0.0
            s += b
        }
        val joined = tokens.joinToString(" ")
        if (n == joined) s += 4
        if (syn.firstOrNull() == joined) s += 3 // the first synonym is the feature's own keyword
        if (item.kind == SearchItem.Kind.SETTING && item.advanced) s -= 0.3
        return s
    }

    fun tokens(query: String): List<String> = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * Grouped results. An empty query lists Right now (or nothing) and the pages; otherwise
     * Features / Settings / Pages by score, each capped.
     */
    fun results(index: List<SearchItem>, query: String, rightNow: List<FeatureCard>): List<SearchGroup> {
        val toks = tokens(query)
        if (toks.isEmpty()) {
            val here = rightNow.take(5).mapNotNull { c -> index.firstOrNull { it.kind == SearchItem.Kind.FEATURE && it.card === c } }
            val pages = ConfigRegistry.activities.mapNotNull { a -> index.firstOrNull { it.kind == SearchItem.Kind.PAGE && it.activity == a.id && it.tab == null } } +
                listOfNotNull(index.firstOrNull { it.kind == SearchItem.Kind.PAGE && it.settingsTab == "interface" && it.option == null })
            return listOf(SearchGroup("RIGHT NOW", here), SearchGroup("PAGES", pages)).filter { it.items.isNotEmpty() }
        }
        val scored = index.onEach { it.score = score(it, toks) }.filter { it.score > 0 }.sortedByDescending { it.score }
        return listOf(SearchItem.Kind.FEATURE to "FEATURES", SearchItem.Kind.SETTING to "SETTINGS", SearchItem.Kind.PAGE to "PAGES")
            .map { (k, title) -> SearchGroup(title, scored.filter { it.kind == k }.take(LIMITS.getValue(k))) }
            .filter { it.items.isNotEmpty() }
    }

    /** A new query selects the best match overall, even when a weaker group sits above it. */
    fun bestIndex(groups: List<SearchGroup>): Int {
        val flat = groups.flatMap { it.items }
        if (flat.isEmpty()) return 0
        var best = 0
        for (i in flat.indices) if (flat[i].score > flat[best].score) best = i
        return best
    }
}
