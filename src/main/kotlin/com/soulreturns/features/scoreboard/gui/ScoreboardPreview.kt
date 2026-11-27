package com.soulreturns.features.scoreboard.gui

import com.soulreturns.config.ScoreboardConfig
import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.data.skyblock.ProfileType
import com.soulreturns.data.skyblock.TabSnapshot
import com.soulreturns.data.skyblock.TabWidgetParser
import com.soulreturns.features.scoreboard.BuiltLine
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.OptionValues
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOption
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardActivities
import com.soulreturns.features.scoreboard.ScoreboardBuilder
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot
import com.soulreturns.features.scoreboard.ScoreboardSources
import com.soulreturns.features.scoreboard.ScoreboardValue
import com.soulreturns.features.scoreboard.ScoreboardValues
import com.soulreturns.features.scoreboard.lines.CustomTextLine
import com.soulreturns.features.scoreboard.lines.GainTracker

/**
 * The setup screen's preview: the setup built by [ScoreboardBuilder] (what the HUD draws) from the live
 * sources when the previewed setup is the one drawing where you are, else from a sample of that place.
 * A line with no value there shows its picker example when it would show in that place (its `where`
 * names it, or it has none), so every line can be seen and styled before it ever shows in game.
 */
internal object ScoreboardPreview {
    /**
     * A place the preview stands in for: [label] for the corner, [sample] sources, and the `where` texts
     * ([LineType.where], split at ", ") of lines that would show there.
     */
    class Place(val label: String, val sample: ScoreboardSnapshot, val shows: Set<String>)

    /** What the preview draws: the lines, the setup indexes that show here at all, where it is. */
    class Result(val lines: List<BuiltLine>, val here: Set<Int>, val label: String)

    private const val REFRESH_MS = 250L

    private var cacheKey: List<Any?>? = null
    private var cached: Result? = null

    /** The preview of [entries] (the setup of activity [activity], null = Everywhere) for config [c]. Cached per frame-ish. */
    fun build(c: ScoreboardConfig, entries: List<ScoreboardEntry>, activity: String?, nowMs: Long): Result {
        val key = listOf(c, entries, activity, ScoreboardSources.version, nowMs / REFRESH_MS)
        cached?.let { if (cacheKey == key) return it }
        val place = place(activity)
        val live = ScoreboardSources.snapshot
        val useLive = live.onSkyblock && live.sidebar.isNotEmpty() && isHere(c, live, activity)
        val sources = if (useLive) live else place.sample
        val label = if (useLive) "here" + ((live.subLocation ?: live.area)?.let { ", $it" } ?: "") else place.label
        val types: (String) -> LineType? = { id -> ScoreboardCatalog.type(id)?.let { PreviewType(it, place) } }
        // Sample values (and the preview's own passes) must never feed the HUD's "(+N)" gain trackers.
        GainTracker.frozen = true
        val lines: List<BuiltLine>
        val shown: List<BuiltLine>
        try {
            lines = if (c.look.hypixelLinesOnly) ScoreboardBuilder.raw(sources, c.look) else ScoreboardBuilder.build(entries, sources, c.look, nowMs, activity, types)
            shown = ScoreboardBuilder.build(entries.map { if (it.hidden) it.copy(hidden = false) else it }, sources, c.look, nowMs, activity, types)
        } finally {
            GainTracker.frozen = false
        }
        val here = HashSet<Int>()
        for (l in shown) if (l.entry >= 0) here += l.entry
        val result = Result(lines, here, label)
        cacheKey = key
        cached = result
        return result
    }

    /** Whether [live] is drawn with the setup of [activity] (null = Everywhere) right now. */
    private fun isHere(c: ScoreboardConfig, live: ScoreboardSnapshot, activity: String?): Boolean =
        if (activity == null) ScoreboardActivities.active(c, live) == null else ScoreboardActivities.current(live)?.id == activity

    /** The place previewed for [activity] (null = Everywhere). */
    fun place(activity: String?): Place = places[activity] ?: places.getValue(null)

    /** A [type] for the preview: its own lines first, then the sample's, then its example where it would show. */
    private class PreviewType(val base: LineType, val place: Place) :
        LineType(base.id, base.group, base.name, base.description, base.example) {
        override val where: String? get() = base.where
        override val tag: String? get() = base.tag
        override val options: List<LineOption> get() = base.options
        override val colors: LineColors get() = base.colors
        override val pinned: Int get() = base.pinned
        override val repeatable: Boolean get() = base.repeatable

        override fun claims(sources: ScoreboardSnapshot): Collection<Int> = base.claims(sources)

        override fun isOff(options: OptionValues): Boolean = base.isOff(options)

        override fun emptyLines(ctx: LineContext): List<String> = base.emptyLines(ctx)

        override fun lines(ctx: LineContext): List<String> {
            // Switched off by its own options (Header/Footer "Hidden"): gone, never replaced by its example.
            if (base.isOff(ctx.options)) return emptyList()
            if (base === CustomTextLine) return listOf(ScoreboardValues.expand(ctx.options.string(CustomTextLine.TEXT), ctx, ::exampleValue))
            base.lines(ctx).let { if (it.isNotEmpty()) return it }
            if (ctx.sources !== place.sample) {
                base.lines(LineContext(place.sample, ctx.entry, ctx.options, ctx.look, ctx.nowMs, ctx.activity)).let { if (it.isNotEmpty()) return it }
            }
            return if (showsIn(base, place)) base.example else emptyList()
        }
    }

    /** A live value, or its example while it has none (the preview never shows `-` for a value). */
    private fun exampleValue(key: String): ScoreboardValue? {
        val v = ScoreboardValues.get(key) ?: return null
        return ScoreboardValue(v.key, v.group, v.name, v.example) { ctx -> runCatching { v.value(ctx) }.getOrNull() ?: v.example }
    }

    /** Whether [type] would show in [place]: no `where` (everywhere it has a value), or one of its places is this one. */
    fun showsIn(type: LineType, place: Place): Boolean {
        val where = type.where ?: return true
        return where.split(", ").any { w -> place.shows.any { it.equals(w.trim(), ignoreCase = true) } }
    }

    // ------------------------------------------------------------------------------------- samples

    private fun sidebar(time: String, area: String, vararg extra: String): List<String> =
        listOf("§711/26/26 §8m14AC", "", " Late Winter 10th", time, area, "", "Purse: §61,193,004,241 §e(+5)", "Bits: §b13,845", "") +
            extra.toList() + listOf("", "§ewww.hypixel.net")

    private fun snapshot(
        title: String,
        lines: List<String>,
        area: String,
        sub: String,
        mode: String?,
        tabLines: List<String> = emptyList(),
    ): ScoreboardSnapshot {
        val s = ScoreboardSnapshot.ofSidebar(title, lines, tabLines)
        return ScoreboardSnapshot(
            title = s.title,
            sidebar = s.sidebar,
            tabWidgets = s.tabWidgets,
            area = area,
            subLocation = sub,
            mode = mode,
            serverId = "m14AC",
            profile = "Strawberry",
            profileType = ProfileType.IRONMAN,
            tab = tab(tabLines),
        )
    }

    /** What [com.soulreturns.data.skyblock.TabWidgets] would hold after reading [lines] once. */
    private fun tab(lines: List<String>): TabSnapshot {
        if (lines.isEmpty()) return TabSnapshot.EMPTY
        val r = TabWidgetParser.parse(lines)
        return TabSnapshot(
            skyblockLevel = r.skyblockLevel,
            bank = r.bank,
            interest = r.interest,
            gems = r.gems,
            fairySouls = r.fairySouls,
            skills = r.skills.associateBy { it.name },
            skillAverage = r.skillAverage,
            stats = r.stats,
            pet = r.pet,
            petKnown = r.petSeen,
        )
    }

    /** The Info columns of a real Dwarven Mines tab list (2026-11-15), trimmed: what tab-list lines read in the preview. */
    private val MINES_TAB =
        listOf(
            "§r               §r§3§lInfo",
            "§r§b§lArea: §r§7Dwarven Mines",
            "§r Server: §r§8mini14AC",
            "§r Gems: §r§a1,204",
            "§r Fairy Souls: §r§d1§r§5/§r§d15",
            "",
            "§r§e§lProfile: §r§aStrawberry §r§7♲",
            "§r SB Level§r§f: §r§8[§r§2171§r§8] §r§b97§r§3/§r§b100 XP",
            "§r Bank: §r§62.1B§r§7 / §r§6340M",
            "§r Interest: §r§e27 Hours§r§6 (876.3k)",
            "",
            "§r§e§lPet:",
            "§r §r§7[Lvl 200] §r§6Golden Dragon",
            "§r §r§e22,402.2§r§6/§r§e74.2k XP §r§6(30.2%)",
            "",
            "§r               §r§3§lInfo",
            "§r§9§lForges:",
            "§r 1) §r§9Refined Mithril§r§7: §r§b2h 14m",
            "",
            "§r§9§lCommissions:",
            "§r §r§fGoblin Slayer: §r§a68%",
            "§r §r§fUpper Mines Titanium: §r§aDONE",
            "",
            "§r§9§lPowders:",
            "§r Mithril: §r§22,988,324",
            "§r Gemstone: §r§d138,760",
            "§r Glacite: §r§b19,295",
            "§r               §r§3§lInfo",
            "§r§9§lPickaxe Ability:",
            "§r §r§fPickobulus: §r§aAvailable",
        )

    private const val TITLE = "§e§lSKYBLOCK §7♲"

    private val places: Map<String?, Place> by lazy {
        val hub = snapshot(TITLE, sidebar(" §72:20pm §e☀", " §7⏣ §bVillage"), "Hub", "Village", "hub")
        fun of(id: String, sample: ScoreboardSnapshot, vararg shows: String) =
            id to Place(ScoreboardActivities.of(id)?.previewPlace ?: id, sample, shows.toSet())
        mapOf(
            null to
                Place(
                    "Dwarven Mines",
                    snapshot(TITLE, sidebar(" §72:20pm §e☀", " §7⏣ §bDwarven Village", "Objective", "§eReach the Crystal Hollows"), "Dwarven Mines", "Dwarven Village", "mining_3", MINES_TAB),
                    setOf("Mining islands", "Dwarven Mines", "Outside dungeons", "Holding a drill"),
                ),
            of(
                ScoreboardActivities.DUNGEONS,
                snapshot(
                    TITLE,
                    sidebar(" §78:50pm §b☽", " §7⏣ §cThe Catacombs §7(F7)", "Keys: §c■ §c✗ §8■ §a0x", "Time Elapsed: §a08m 12s", "Cleared: §c64% §8(312)"),
                    "Catacombs", "The Catacombs (F7)", "dungeon",
                ),
                "Dungeons", "In a party",
            ),
            of(
                ScoreboardActivities.KUUDRA,
                snapshot(TITLE, sidebar(" §711:40pm §b☽", " §7⏣ §cKuudra's Hollow §7(T5)", "Time Elapsed: §a04m 31s", "Wave: §c2 §8- §a0:09"), "Kuudra", "Kuudra's Hollow (T5)", "kuudra"),
                "Kuudra", "In a party",
            ),
            of("slayer", hub, "During a quest", "Outside dungeons"),
            of("jacob", snapshot(TITLE, sidebar(" §72:20pm §e☀", " §7⏣ §aThe Garden"), "Garden", "The Garden", "garden"), "During a contest", "The Garden", "Farming Islands", "Outside dungeons"),
            of("spooky", hub, "Spooky Festival", "Outside dungeons"),
            of(
                "mineshaft",
                snapshot(TITLE, sidebar(" §72:20pm §e☀", " §7⏣ §bGlacite Mineshafts"), "Mineshaft", "Glacite Mineshafts", "mineshaft", MINES_TAB),
                "Glacite Mineshafts", "Glacite Tunnels", "Mining islands", "Outside dungeons", "Holding a drill",
            ),
            of(
                "crystal_hollows",
                snapshot(TITLE, sidebar(" §72:20pm §e☀", " §7⏣ §6Jungle"), "Crystal Hollows", "Jungle", "crystal_hollows", MINES_TAB),
                "Crystal Hollows", "Mining islands", "Outside dungeons", "Holding a drill",
            ),
            of("rift", snapshot(TITLE, sidebar(" §72:20pm §e☀", " §7⏣ §aWizard Tower"), "The Rift", "Wizard Tower", "rift"), "The Rift", "Outside dungeons"),
            of("garden", snapshot(TITLE, sidebar(" §72:20pm §e☀", " §7⏣ §aThe Garden"), "Garden", "The Garden", "garden"), "The Garden", "Farming Islands", "Outside dungeons"),
            of("galatea", snapshot(TITLE, sidebar(" §72:20pm §e☀", " §7⏣ §2Moonglade Marsh"), "Galatea", "Moonglade Marsh", "galatea"), "Galatea", "Outside dungeons"),
        )
    }
}
