package com.soulreturns.features.contests.hud

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.ContestsConfig
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.Theme
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.features.contests.gui.Connection
import com.soulreturns.features.contests.gui.ContestView
import com.soulreturns.features.contests.gui.ContestsFormat
import com.soulreturns.features.contests.gui.ContestsSource
import com.soulreturns.features.contests.gui.StoreContestsSource
import com.soulreturns.features.contests.model.ContestTypeDescriptor
import com.soulreturns.features.mining.nucleus.CrystalState
import com.soulreturns.features.mining.nucleus.NucleusRuns
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.tracker.TimerInfo
import com.soulreturns.ui.hud.tracker.TrackerColumn
import com.soulreturns.ui.hud.tracker.TrackerHud
import com.soulreturns.ui.hud.tracker.TrackerSegments
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerSort
import com.soulreturns.ui.hud.tracker.TrackerSpec
import com.soulreturns.ui.hud.tracker.TrackerTab
import net.minecraft.client.gui.screens.Screen

/**
 * What the HUD needs from a contest type's game side: where the type is played and the run in
 * progress. One implementation per capture (today [NucleusRunProbe]); unknown types show standings only.
 */
interface ContestRunProbe {
    /** Epoch ms the current run started, or null between runs. */
    fun runStart(): Long?
    fun partial(): Boolean

    /** "3/5 placed"-style progress inside the run, or null when it isn't known. */
    fun progress(): Pair<Int, Int>?

    companion object {
        fun of(t: ContestTypeDescriptor): ContestRunProbe? = if (t.id == "nucleus-profit") NucleusRunProbe else null
    }
}

object NucleusRunProbe : ContestRunProbe {
    override fun runStart(): Long? = NucleusRuns.contestRunStart()
    override fun partial(): Boolean = NucleusRuns.contestRunPartial()

    /** The crystal state is only kept while the Nucleus tracker or checklist is on. */
    override fun progress(): Pair<Int, Int>? {
        if (!NucleusRuns.trackerOn() && !cfg.mining.nucleus.checklist) return null
        return NucleusRuns.handle.read().run.count(CrystalState.PLACED) to 5
    }
}

/**
 * The contest panel (mock `hudHtml`) on the shared tracker panel: compact = you ±1 plus the leader,
 * expanded = everyone with the timer and the "Live" tab; spectators see the top 3. Drawn only while in
 * a running contest and in the type's area (the Crystal Hollows for Nucleus profit).
 */
object ContestHud : TrackerHud<ContestHud.Row>("contest_hud", "Contest", "general") {
    data class Row(val uuid: String, val label: String, val units: Int, val goal: Int, val total: Long, val color: Int, val order: Int)

    private const val COLOR_ME = 0xFF55FFFF.toInt()
    private const val COLOR_DONE = 0xFFFFAA00.toInt()
    private const val COLOR_RUN = 0xFF55FFFF.toInt()
    private const val COLOR_OK = 0xFF55FF55.toInt()
    private const val COLOR_BAD = 0xFFFF5555.toInt()
    private const val COLOR_WARN = 0xFFFFFF55.toInt()

    var source: ContestsSource = StoreContestsSource

    /** The contest shown: the first running one you play in, else the first you watch. */
    fun current(): ContestView? {
        val self = source.selfUuid
        val running = (source.contests().value?.active.orEmpty())
            .filter { it.state == ContestView.RUNNING }
            .mapNotNull { s -> source.snapshot(s.id).value }
        val snap = running.firstOrNull { it.me.plays } ?: running.firstOrNull() ?: return null
        return ContestView(snap, self, source.queued(snap.contest.id))
    }

    /** `/soul dev contests hud`: a fixture is shown anywhere, and may force compact/expanded. */
    var fixture = false
    var expandedOverride: Boolean? = null

    private fun expanded(): Boolean = expandedOverride ?: cfg.contests.hudExpanded

    private fun inArea(v: ContestView): Boolean = fixture || (v.type.area?.let { SkyblockLocation.isInArea(it) } ?: true)

    private val liveSettings = object : TrackerSettings {
        override var tab: TrackerTab = TrackerTab.Session
        override var sortId: String = "rank"
        override var scrollOffset: Float = 0f
        override var filter: Set<String> = emptySet()
        override fun isColumnVisible(columnId: String, defaultVisible: Boolean): Boolean = true
        override fun setColumnVisible(columnId: String, visible: Boolean) {}
        override var sortDropdownOpen: Boolean = false
        override var columnDropdownOpen: Boolean = false
        override var filterDropdownOpen: Boolean = false
        override fun markDirty() {}
    }

    override val settings: TrackerSettings get() = liveSettings

    override val spec: TrackerSpec<Row> by lazy {
        TrackerSpec(
            id = "contest_hud",
            title = "Contest",
            titleText = ::title,
            width = 236,
            tabs = listOf(TrackerTab.Session),
            tabLabel = { "Live" },
            columns = listOf(
                TrackerColumn(
                    id = "units",
                    chipLabel = "Runs",
                    toggleLabel = "Runs",
                    // units * 10000 + goal: one Long carries "31/50".
                    cellValue = { row, _ -> row.units * 10_000L + row.goal },
                    totalValue = { _, _ -> 0L },
                    formatCell = { "${it / 10_000}/${it % 10_000}" },
                    isCaption = true,
                    cellWidth = 40f,
                    chipShouldRender = { false },
                ),
                TrackerColumn(
                    id = "total",
                    chipLabel = "Total",
                    toggleLabel = "Total",
                    cellValue = { row, _ -> row.total },
                    totalValue = { _, _ -> 0L },
                    formatCell = { Format.short(it) },
                    cellWidth = 40f,
                    chipShouldRender = { false },
                ),
            ),
            sorts = listOf(TrackerSort("rank", "Rank", compareBy { it.order })),
            rowsProvider = { rows() },
            rowLabel = { it.label },
            rowLabelColor = { it.color },
            isVisible = ::visible,
            timerLine = { if (expanded()) timer() else null },
            headerExtra = ::headerLines,
            chipOverride = ::chips,
            emptyText = { "(no contest running)" },
            scrollableList = { false },
        )
    }

    fun visible(): Boolean {
        if (!cfg.contests.hud) return false
        val v = current() ?: return false
        return inArea(v)
    }

    private fun title(): String {
        val v = current() ?: return "Contest"
        val place = if (v.isSpectator || v.me == null) "watching" else v.me.rank?.let(ContestsFormat::ord) ?: "-"
        return "${v.c.name} · $place"
    }

    private fun timer(): TimerInfo? {
        val v = current() ?: return null
        val start = v.c.startedAt ?: return null
        val s = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
        return TimerInfo(String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60))
    }

    fun rows(): List<Row> {
        val v = current() ?: return emptyList()
        val ranked = v.ranked
        val me = v.me
        val spect = v.isSpectator || me == null
        val idx = if (spect) 0 else ranked.indexOf(me)
        var rows = if (expanded()) ranked else if (spect) ranked.take(3) else ranked.subList(maxOf(0, idx - 1), minOf(ranked.size, idx + 2))
        if (!spect && !expanded() && idx > 1) rows = listOf(ranked[0]) + rows
        return rows.mapIndexed { i, r ->
            val mine = v.isMe(r.uuid)
            val color = if (mine) COLOR_ME else if (r.units >= v.goal) COLOR_DONE else Theme.TEXT
            Row(r.uuid, "${r.rank ?: "-"}. ${if (mine) "You" else r.name}", r.units, v.goal, r.total, color, i)
        }
    }

    private fun headerLines(): List<TrackerSegments> {
        val v = current() ?: return emptyList()
        val out = ArrayList<TrackerSegments>()
        val me = v.me
        val unit = v.type.unit
        if (v.isSpectator || me == null) {
            val lead = v.ranked.firstOrNull()
            if (lead != null) out += listOf("Watching · " to Theme.TEXT_DIM, lead.name to Theme.TEXT, " leads" to Theme.TEXT_DIM)
        } else {
            if (source.capture(v.c.id)?.state == com.soulreturns.features.contests.CaptureStates.WRONG_PROFILE) {
                out += listOf("Other profile" to COLOR_BAD, " · ${unit.plural} here don't count" to Theme.TEXT_DIM)
            }
            val probe = ContestRunProbe.of(v.type)
            val start = probe?.runStart()
            val n = me.units + v.queued.size + 1
            if (start != null) {
                val line = arrayListOf(
                    "${ContestsFormat.unitTitle(v.type)} $n" to COLOR_RUN,
                    " · " to Theme.TEXT_FAINT,
                    (if (probe.partial()) "~" else "") + ContestsFormat.mmss(System.currentTimeMillis() - start) to Theme.TEXT,
                )
                probe.progress()?.let { (a, b) ->
                    line += " · " to Theme.TEXT_FAINT
                    line += "$a" to COLOR_OK
                    line += "/$b placed" to Theme.TEXT_DIM
                }
                out += line
            } else if (me.units < v.goal) {
                out += listOf("Next: " to Theme.TEXT_DIM, "${unit.singular} $n" to Theme.TEXT, " of ${v.goal}" to Theme.TEXT_DIM)
            } else {
                out += listOf("All ${v.goal} ${unit.plural} done" to COLOR_DONE)
            }
        }
        val c = source.connection()
        if (c.state == Connection.OFFLINE) {
            val q = v.queued.size
            out += listOf("Offline" to COLOR_BAD, (if (q > 0) " · $q ${if (q == 1) unit.singular else unit.plural} waiting to send" else " · standings may be old") to Theme.TEXT_DIM)
        } else if (c.state == Connection.RECONNECTING) {
            out += listOf("Reconnecting… " to COLOR_WARN, (c.since?.let { "standings from ${ContestsFormat.span(System.currentTimeMillis() - it)} ago" } ?: "") to Theme.TEXT_DIM)
        }
        return out
    }

    private fun chips(): TrackerSegments {
        val v = current() ?: return emptyList()
        val me = v.me ?: return emptyList()
        if (v.isSpectator) return emptyList()
        val idx = v.ranked.indexOf(me)
        if (idx < 0) return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        // Gaps to the nearest player with a different place (a tie is no gap).
        val myRank = me.rank ?: return emptyList()
        v.ranked.take(idx).lastOrNull { (it.rank ?: 0) < myRank }?.let { a -> out += "${Format.short(a.total - me.total)} to ${ContestsFormat.ord(a.rank ?: 0)}" to Theme.TEXT }
        v.ranked.drop(idx + 1).firstOrNull { (it.rank ?: Int.MAX_VALUE) > myRank }?.let { b ->
            if (out.isNotEmpty()) out += "  " to Theme.TEXT_DIM
            out += "${Format.short(me.total - b.total)} ahead of ${ContestsFormat.ord(b.rank ?: 0)}" to Theme.TEXT
        }
        if (expanded() && me.avg != null) {
            if (out.isNotEmpty()) out += "  " to Theme.TEXT_DIM
            out += "Avg ${Format.short(me.avg)}/${v.type.unit.singular}" to Theme.TEXT_DIM
        }
        return out
    }

    override fun configuredOn(): Boolean = cfg.contests.hud

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "contests")

    override fun readTransform(): HudTransform = cfg.contests.hudTransform

    override fun writeTransform(transform: HudTransform) = update { copy(hudTransform = transform) }

    override fun defaultTransform(): HudTransform = SoulConfig().contests.hudTransform

    override fun drawBackground(): Boolean = true

    override fun backgroundColor(): Int = 0xD81A1A1A.toInt()

    override fun menuToggles(): List<HudMenuToggle> = listOf(
        HudMenuToggle("Expanded", { cfg.contests.hudExpanded }) { update { copy(hudExpanded = !hudExpanded) } },
    )

    private fun update(block: ContestsConfig.() -> ContestsConfig) {
        ConfigManager.update { copy(contests = contests.block()) }
    }
}
