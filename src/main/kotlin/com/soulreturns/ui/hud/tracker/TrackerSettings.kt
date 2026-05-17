package com.soulreturns.ui.hud.tracker

/**
 * Mutable HUD-local UI state shared by every tracker — which tab is active, which sort is
 * picked, scroll offset, filter selection, and per-column visibility. The framework reads
 * these every frame and writes back as the user interacts; persistence is the
 * implementor's concern (each tracker owns its own JSON file).
 *
 * **Mutators must call [markDirty] themselves** when they change persisted state. The
 * framework calls `markDirty()` after every interaction that flips state, so a tick-driven
 * save loop in the implementor only needs to check the dirty flag.
 *
 * The three `*DropdownOpen` flags are transient (never persisted) — popups boot closed on
 * every client launch and live on the singleton as `@Volatile` fields.
 */
interface TrackerSettings {
    var tab: TrackerTab
    var sortId: String
    var scrollOffset: Float

    /**
     * Multi-select filter on whatever variant axis the spec defines. Empty set = no filter
     * (show everything). Implementations should use a `Set<String>` swapped via `var`
     * rather than a mutable set so the framework can reason about identity-equality.
     */
    var filter: Set<String>

    /** Reads the persisted visibility flag for [columnId], falling back to [defaultVisible]. */
    fun isColumnVisible(
        columnId: String,
        defaultVisible: Boolean,
    ): Boolean

    /** Writes the visibility flag for [columnId]. Implementations are responsible for [markDirty]. */
    fun setColumnVisible(
        columnId: String,
        visible: Boolean,
    )

    var sortDropdownOpen: Boolean
    var columnDropdownOpen: Boolean
    var filterDropdownOpen: Boolean

    fun markDirty()
}
