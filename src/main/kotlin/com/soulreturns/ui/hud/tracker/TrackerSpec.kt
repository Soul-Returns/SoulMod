package com.soulreturns.ui.hud.tracker

import com.soulreturns.gui.lib.HudHorizontalAnchor
import com.soulreturns.gui.lib.HudVerticalAnchor
import com.soulreturns.ui.composer.SoulComposable

/**
 * Optional header timer — single text line + paused-state indicator. Trackers without a
 * running timer (most profit trackers) leave [TrackerSpec.timerLine] null.
 */
data class TimerInfo(val text: String, val paused: Boolean = false)

/**
 * Declarative description of one tracker HUD. Consumed by [TrackerHud] which builds the
 * full panel layout (header / tabs / scrollable list / chips line / footer / dropdowns /
 * reset). One TrackerSpec per concrete tracker — see `FishingHud` and `DragonProfitHud`
 * for both flavours (general-counts tracker vs profit tracker).
 *
 * The two-flavour distinction the user asked for lives in HOW the spec is filled in, not in
 * the type — a "general" tracker (sea creatures, mineshafts) defines per-creature count
 * columns; a "profit" tracker adds an "Amount" + "Value" pair on top of [PriceCache]
 * lookups. Both produce a [TrackerSpec], both render through the same [TrackerHud].
 *
 * @param T the row type — e.g. `CreatureRow` for Fishing, `DragonProfitTracker.Row` for
 *   Dragon. Provided by [rowsProvider].
 */
data class TrackerSpec<T : Any>(
    /**
     * HUD id passed to [com.soulreturns.ui.runtime.SoulHud.register]. Doubles as the prefix
     * for input region keys (`"<id>.tabs"`, `"<id>.scroll"`, …) and as the key into the
     * gui_layout.json registry.
     */
    val id: String,
    /** Title text rendered centered at the top of the header. */
    val title: String,
    val width: Int = 280,
    val height: Int = 380,
    val defaultAnchorX: Double = 0.01,
    val defaultAnchorY: Double = 0.02,
    val defaultHorizontalAnchor: HudHorizontalAnchor = HudHorizontalAnchor.Start,
    val defaultVerticalAnchor: HudVerticalAnchor = HudVerticalAnchor.Top,
    /** Deep-link target for the `/soul gui` right-click → Settings entry. */
    val settingsCategory: String,
    val settingsSubcategory: String,
    val columns: List<TrackerColumn<T>>,
    val sorts: List<TrackerSort<T>>,
    /**
     * Build the list of rows for the active tab. Called every frame inside the composable,
     * so it must be cheap — read pre-aggregated maps from the tracker singleton, don't
     * walk Minecraft state here.
     */
    val rowsProvider: (TrackerTab) -> List<T>,
    /** Stable id used for hover state. Usually the row's own name / item id. */
    val rowKey: (T) -> String,
    /** Left-column label text. */
    val rowLabel: (T) -> String,
    /** Left-column label color — typically derived from rarity / domain type. */
    val rowLabelColor: (T) -> Int,
    /**
     * Variants offered in the Filter multi-select dropdown. Empty list = no filter dropdown
     * is rendered (footer gets one fewer row). Display names should already be friendly —
     * the framework doesn't transform them.
     */
    val filterVariants: () -> List<String> = { emptyList() },
    /**
     * Decides whether a row matches the current filter. Empty selection = show everything
     * (the framework calls with `emptySet()` in that case and most implementations should
     * return true). Only invoked when [filterVariants] is non-empty.
     */
    val filterPredicate: (T, Set<String>) -> Boolean = { _, _ -> true },
    /** Trigger label generator. Defaults to `"Filter"` / `"Filter (N)"`. */
    val filterLabel: (Set<String>) -> String = { sel ->
        if (sel.isEmpty()) "Filter" else "Filter (${sel.size})"
    },
    /**
     * Optional reset-session callback. When null, no Reset button is rendered. Profit
     * trackers and stat trackers typically both provide one — the button is rendered full-
     * width centered at the bottom of the footer.
     */
    val onResetSession: (() -> Unit)? = null,
    /** Reset button label. Defaults to `"Reset Session"`. */
    val resetLabel: String = "Reset Session",
    /**
     * Whether the HUD renders at all this frame. Composes the master toggle + area gate +
     * any per-tracker prerequisites. The composable short-circuits to `Box {}` when this
     * returns false so the panel takes no PIP texture space.
     */
    val isVisible: () -> Boolean,
    /**
     * Optional header timer (e.g. Fishing session stopwatch). Called each frame; return
     * null to skip. The composable lays it out on the left of the timer/tabs row.
     */
    val timerLine: (() -> TimerInfo?)? = null,
    /**
     * Optional fully-custom extra header row, drawn between the title row and the
     * timer/tabs row. Use for tracker-specific banners (Fishing's festival countdown).
     * Called every frame; render `Box {}` when there's nothing to show.
     */
    val headerExtra: (@SoulComposable () -> Unit)? = null,
    /**
     * Optional fully-custom footer row, drawn ABOVE the filter dropdown row (inside the
     * interactive-only footer block). Use for tracker-specific cross-axis selectors —
     * e.g. the Dragon HUD's Source picker (All / Summoned / Lootshare) that selects which
     * kill-source partition feeds [rowsProvider]. Called every frame, only when the
     * footer is being rendered (player inventory open); render `Box {}` to skip.
     */
    val footerExtra: (@SoulComposable () -> Unit)? = null,
    /**
     * Optional placeholder text shown when [rowsProvider] returns an empty list. Defaults
     * to `"(no data yet)"`.
     */
    val emptyText: String = "(no data yet)",
    /**
     * When this returns true, [listOverride] is rendered in place of the default
     * scrollable list AND the chip-line is suppressed for that frame. Used for transient
     * "instructional" states — e.g. the dragon-profit HUD swaps the list for a "Go near
     * the loot to track it" banner while a scan window is open but no loot has rendered
     * yet. The override is expected to occupy the same vertical space as the default list
     * so the panel doesn't visibly shrink/grow as the override toggles.
     */
    val shouldOverrideList: () -> Boolean = { false },
    val listOverride: (@SoulComposable () -> Unit)? = null,
    /**
     * Optional fully-custom replacement for the auto-generated chip line. When non-null,
     * the framework skips its own per-column chip rendering and invokes this composable
     * instead. Used by trackers that need a multi-element layout the per-column chip API
     * can't express — e.g. the dragon-profit HUD's two-cell SpaceBetween line with
     * `Per dragon` flush-left and `Profit` flush-right. The override is responsible for
     * its own Row / alignment / formatting.
     */
    val chipOverride: (@SoulComposable () -> Unit)? = null,
)
