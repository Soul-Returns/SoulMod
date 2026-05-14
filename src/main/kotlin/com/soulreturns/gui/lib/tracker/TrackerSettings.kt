package com.soulreturns.gui.lib.tracker

/**
 * Per-tracker UI state persisted across sessions.
 *
 * Lives separately from [com.soulreturns.gui.lib.GuiLayoutManager]'s `gui_layout.json` because:
 *  - Layout (position, scale, enabled) is owned by the user and edited via `/soul gui`.
 *  - UI state (which tab is active, which sort, how many rows) is changed by clicking the
 *    panel itself and persists across launches without going through the GUI editor.
 *
 * All fields are `var`s so callers can read once, mutate, write once. Persisted as JSON via
 * [TrackerSettingsStore].
 *
 * @property activeTab Name of the currently-selected tab (matches a [TrackerTab.name]).
 * @property sortKey Key of the currently-selected sort option (matches a [TrackerSortOption.key]).
 * @property limit Max number of rows to display; -1 means "all".
 * @property scrollOffset Index of the first visible row when the list overflows.
 */
data class TrackerSettings(
    var activeTab: String,
    var sortKey: String,
    var limit: Int = 10,
    var scrollOffset: Int = 0,
) {
    companion object {
        /** Limit values offered by the standard "Show: Top N" cycle button. -1 means "all". */
        val DEFAULT_LIMIT_OPTIONS: List<Int> = listOf(5, 10, 15, -1)
    }
}
