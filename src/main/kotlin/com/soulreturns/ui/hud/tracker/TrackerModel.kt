package com.soulreturns.ui.hud.tracker

import com.soulreturns.config.gui.Theme
import net.minecraft.world.item.ItemStack
import java.util.Locale

/**
 * Which bucket of counters a tracker is showing.
 *
 * - [Session] — persisted per profile; survives a quick restart and resets when the client launches
 *   more than 30 minutes after it last ran ([com.soulreturns.stats.TrackerSessions]), or by the
 *   tracker's Reset button.
 * - [Event] — persisted per Hypixel mayor term. Opt-in via [TrackerSpec.tabs]; no tracker in this
 *   baseline uses it yet, and it is kept because dropping it would mean reshaping the strip later.
 * - [Total] — persisted all-time, per SkyBlock profile.
 *
 * Enum order is the left-to-right order in the tab strip.
 *
 * Ported verbatim from the legacy framework. Note the codebase rule against `when (enumValue)` —
 * compare with `==` or round-trip through [name].
 */
enum class TrackerTab { Session, Event, Total }

/**
 * One sort option in the Sort dropdown.
 *
 * @property id the persisted key.
 * @property comparator orders rows. [TrackerHud] appends an alphabetical tie-break, so two rows with
 *   the same number keep a stable order instead of shuffling between frames.
 * @property requiresColumnId hides this sort while its column is hidden — sorting by a number you
 *   cannot see is confusing. Null for sorts that bind to no column (Rarity, Alphabetical).
 */
data class TrackerSort<T>(
    val id: String,
    val label: String,
    val comparator: Comparator<T>,
    val requiresColumnId: String? = null,
)

/**
 * One numeric column: a per-row cell, a summary chip under the list, and an entry in the Columns
 * dropdown.
 *
 * **There is no header row.** Column identity is carried by the cell's own format (`5 DH`) and by
 * the chip label below the list. That is what keeps the panel readable at HUD scale.
 *
 * @property cellValue / [totalValue] read from the tracker's aggregates. Both receive the active tab
 *   so they can pick the right backing map.
 * @property chipPercentageOfColumn id of another column used as the denominator, rendering the chip
 *   as `Label: N (P.P%)`. Null for a plain chip.
 * @property isCaption smaller font for compact unit-suffixed cells; body for the headline metric.
 * @property cellWidth fixed cell width in Soul-logical px. Widen it for columns that format wider
 *   values (a profit column rendering `12.5M coins` needs roughly twice the default).
 */
data class TrackerColumn<T>(
    val id: String,
    /** Prefix in the summary chip: `DH`, `Catches`, `Coins`. */
    val chipLabel: String,
    /** Label in the Columns dropdown: `Double Hooks`. */
    val toggleLabel: String,
    val defaultVisible: Boolean = true,
    val cellValue: (row: T, tab: TrackerTab) -> Long,
    val totalValue: (rows: List<T>, tab: TrackerTab) -> Long,
    val formatCell: (Long) -> String = { String.format(Locale.ROOT, "%,d", it) },
    val formatChipTotal: (Long) -> String = { String.format(Locale.ROOT, "%,d", it) },
    val isCaption: Boolean = false,
    val cellColor: Int? = null,
    val chipPercentageOfColumn: String? = null,
    /** Returns false to hide this column's chip even while the column itself is visible. */
    val chipShouldRender: () -> Boolean = { true },
    val cellWidth: Float = 32f,
) {
    /** Caption columns read dim, body columns read accent — the legacy convention, kept. */
    fun resolvedCellColor(): Int = cellColor ?: if (isCaption) Theme.TEXT_DIM else Theme.ACCENT
}

/**
 * The mutable UI state of one tracker panel: active tab, sort, scroll, filter, column visibility and
 * the transient popup state.
 *
 * [TrackerHud] reads these every frame and writes them back as the user interacts. **Persistence is
 * the implementor's business** — it calls [markDirty] after anything that should survive a restart,
 * and the implementor decides what that means. The popup state is deliberately not part of that: a
 * popup boots closed every launch.
 */
interface TrackerSettings {
    var tab: TrackerTab
    var sortId: String
    var scrollOffset: Float

    /** Multi-select filter over whatever variant axis the spec defines. Empty = show everything. */
    var filter: Set<String>

    fun isColumnVisible(
        columnId: String,
        defaultVisible: Boolean,
    ): Boolean

    fun setColumnVisible(
        columnId: String,
        visible: Boolean,
    )

    var sortDropdownOpen: Boolean
    var columnDropdownOpen: Boolean
    var filterDropdownOpen: Boolean

    /**
     * Which [TrackerSpec.footerSelects] popup is open, by its [TrackerSelect.id], or null for none.
     *
     * An **id rather than a boolean**, because a tracker may declare more than one selector and the
     * panel has a single overlay slot: two expanded triggers would mean one of them silently never
     * paints. Holding the id makes "at most one open" the representation rather than an invariant
     * three booleans have to keep between them.
     *
     * Defaulted, because a tracker without any footer selector — which is most of them — should not
     * have to carry state for a widget it never declares. A tracker that does declare one overrides
     * this like the three flags above.
     */
    var openSelectId: String?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    fun markDirty()
}

/**
 * A single-select dropdown row across the top of the footer — a real widget, not a line of text.
 *
 * The dragon tracker's Source picker (All / Summoned / Lootshare) is the reason this exists: it
 * chooses which kill-source partition every number on the panel is read from, which is a control, and
 * [TrackerSpec.footerExtra] can only render text. Its price-mode picker is the second, and is what
 * turned this from one slot into a list. The legacy framework let a tracker hand the panel an
 * arbitrary composable for exactly this; 26.2 has no layout engine, so the shape is declared here
 * instead of being smuggled in.
 *
 * A spec may declare several; they stack down the top of the footer in declaration order, and at
 * most one is expanded at a time (see [TrackerSettings.openSelectId]).
 *
 * @property id popup key, and the prefix of this row's hit regions — so **it must be unique within a
 *   spec**, or two selectors would share hit regions and the second would be unclickable.
 * @property labelPrefix the trigger reads `"<labelPrefix>: <selected option>"`.
 * @property selectedIndex a lambda, because the selection is the tracker's state and not the panel's.
 *   Out-of-range answers are clamped rather than crashing the HUD.
 */
data class TrackerSelect(
    val id: String,
    val labelPrefix: String,
    val options: List<String>,
    val selectedIndex: () -> Int,
    val onSelect: (Int) -> Unit,
)

/** Optional header clock. Trackers without one leave [TrackerSpec.timerLine] null. */
data class TimerInfo(val text: String, val paused: Boolean = false)

/**
 * A line of coloured text a tracker can inject into the panel — the 26.2 stand-in for the legacy
 * spec's `@SoulComposable` slots.
 *
 * The legacy framework let a tracker hand the panel an arbitrary composable for its header banner,
 * its footer selector and its chip line. That framework does not exist here, so the extension points
 * take the one shape a tracker actually used them for: a row of `(text, colour)` segments, laid out
 * left-to-right. Anything more elaborate belongs in the tracker's own HUD, not in a slot.
 */
typealias TrackerSegments = List<Pair<String, Int>>

/**
 * Declarative description of one tracker panel. [TrackerHud] turns it into the whole thing: header,
 * tabs, scrollable list, chips, footer, dropdown popups, reset.
 *
 * One spec per tracker. A **general** tracker (sea creatures, mineshafts) declares per-row count
 * columns; a **profit** tracker adds an amount + value pair. Both render through the same panel —
 * the distinction lives in how the spec is filled in, not in the type.
 *
 * @param T the row type, produced by [rowsProvider].
 */
data class TrackerSpec<T : Any>(
    /** HUD id. Also the Move-GUI key and the prefix for every hit region in the panel. */
    val id: String,
    val title: String,
    val width: Int = 280,
    /** Height of the scrollable row region. The panel's total height follows its content. */
    val listHeight: Float = TrackerHud.LIST_HEIGHT,
    val columns: List<TrackerColumn<T>>,
    val sorts: List<TrackerSort<T>>,
    /** Tabs in the header strip, in display order. */
    val tabs: List<TrackerTab> = listOf(TrackerTab.Session, TrackerTab.Total),
    /**
     * Rows for the active tab. Called every frame **and once more per frame for measurement**, so it
     * must be cheap and free of side effects: read pre-aggregated maps, never walk Minecraft state.
     */
    val rowsProvider: (TrackerTab) -> List<T>,
    val rowLabel: (T) -> String,
    val rowLabelColor: (T) -> Int,
    /** Item drawn left of the row label, scaled to the row height. Null = no icon column. */
    val rowIcon: ((T) -> ItemStack?)? = null,
    /**
     * Removes a row from the tracker. When set, an interactive panel (inventory open, controls shown)
     * draws a small `×` at the start of every row that calls it. Null = no remove control.
     */
    val onRowRemove: ((T) -> Unit)? = null,
    /** Variants offered in the Filter dropdown. Empty = no filter row in the footer. */
    val filterVariants: () -> List<String> = { emptyList() },
    /** Does this row match the current selection? Only called when [filterVariants] is non-empty. */
    val filterPredicate: (T, Set<String>) -> Boolean = { _, _ -> true },
    val filterLabel: (Set<String>) -> String = { sel -> if (sel.isEmpty()) "Filter" else "Filter (${sel.size})" },
    /** Null = no Reset button. */
    val onResetSession: (() -> Unit)? = null,
    val resetLabel: String = "Reset Session",
    /** Whether the panel renders at all: master toggle, area gate, and any tracker prerequisites. */
    val isVisible: () -> Boolean,
    /** Header clock, drawn to the left of the tab strip. Return null to omit it. */
    val timerLine: (() -> TimerInfo?)? = null,
    /**
     * Extra header lines under the title — the fishing festival countdown, and the optional rate
     * line beside it. Return an empty list for none; the panel then shrinks by exactly those rows
     * rather than leaving a gap where they would have been.
     */
    val headerExtra: (() -> List<TrackerSegments>)? = null,
    /** Extra footer lines above the filter row. Only drawn while the panel is interactive. */
    val footerExtra: (() -> List<TrackerSegments>)? = null,
    /**
     * Single-select dropdown rows at the top of the footer, above [footerExtra]'s lines, in
     * declaration order. Empty for the trackers that need no such control — see [TrackerSelect].
     */
    val footerSelects: List<TrackerSelect> = emptyList(),
    /**
     * Placeholder for an empty list. A lambda, not a `String`: a tracker's reason for being empty can
     * change while the client runs — "the catalog has not loaded" and "you have not caught anything"
     * are different answers and the panel should not freeze whichever was true at startup.
     */
    val emptyText: () -> String = { "(no data yet)" },
    /**
     * When false the row list drops its fixed height and the panel grows with the row count. Wired
     * to config by trackers so the user can flip it live; a lambda, not a `Boolean`, for that reason.
     */
    val scrollableList: () -> Boolean = { true },
    /** Replaces the row list and suppresses the chip line while true — a transient banner state. */
    val shouldOverrideList: () -> Boolean = { false },
    /**
     * The banner that stands in for the row list. A list of lines, centred as a block in exactly the
     * list's height so the panel's footprint does not jump when it appears — a banner that explains
     * why the list is empty usually needs a second line to say what to do about it.
     */
    val listOverride: (() -> List<TrackerSegments>)? = null,
    /** Replaces the auto-generated chip line wholesale when a tracker needs its own arrangement. */
    val chipOverride: (() -> TrackerSegments)? = null,
    /**
     * Drop the row list and chip line entirely (header + footer only): the tracker's own filter can
     * mean "show no rows" (the Metal Detector's Groups: none).
     */
    val hideList: () -> Boolean = { false },
)
