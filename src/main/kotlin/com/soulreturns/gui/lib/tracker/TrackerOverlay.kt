package com.soulreturns.gui.lib.tracker

/**
 * Declarative description of a reusable list-style tracker HUD.
 *
 * Features build one of these at registration time and hand it to
 * [com.soulreturns.gui.lib.GuiLayoutApi.updateTrackerOverlay]. The same struct is then used by
 * [TrackerOverlayRenderer] (to paint the panel) and by [TrackerInputHandler] (to route clicks
 * and scroll events).
 *
 * @property id Stable tracker identifier. Used as the GuiElement id, the
 *   [TrackerSettingsStore] key, and the namespace for hit-region payloads.
 * @property title Header label shown at the top-left of the panel.
 * @property tabs At least one tab. Active tab is selected by [TrackerSettings.activeTab]; if
 *   no match, the first tab is shown.
 * @property sortOptions Available sort modes cycled through by the footer's "Sort: X" button.
 * @property limitOptions Row-limit values cycled by the "Show: Top N" button. `-1` means "all".
 * @property defaults Initial [TrackerSettings] used when no persisted record exists.
 * @property showResetButton Whether the footer includes a "Reset Session" button. Defaults to
 *   true (feature-typical use case); set false for trackers with no in-memory session state.
 * @property onReset Callback fired when the footer reset button is clicked. Required when
 *   [showResetButton] is true.
 */
data class TrackerOverlay(
    val id: String,
    val title: String,
    val tabs: List<TrackerTab>,
    val sortOptions: List<TrackerSortOption>,
    val limitOptions: List<Int> = TrackerSettings.DEFAULT_LIMIT_OPTIONS,
    val defaults: TrackerSettings,
    val showResetButton: Boolean = true,
    val onReset: (() -> Unit)? = null,
) {
    init {
        require(tabs.isNotEmpty()) { "TrackerOverlay '$id' must declare at least one tab" }
        require(sortOptions.isNotEmpty()) { "TrackerOverlay '$id' must declare at least one sort option" }
        require(limitOptions.isNotEmpty()) { "TrackerOverlay '$id' must declare at least one limit option" }
        if (showResetButton) {
            requireNotNull(onReset) { "TrackerOverlay '$id' enables reset button but provides no onReset callback" }
        }
    }

    fun tabByName(name: String): TrackerTab = tabs.firstOrNull { it.name == name } ?: tabs.first()

    fun sortByKey(key: String): TrackerSortOption = sortOptions.firstOrNull { it.key == key } ?: sortOptions.first()
}

/**
 * Single tab in a [TrackerOverlay]. The [rowsProvider] is invoked each render — keep it cheap.
 */
data class TrackerTab(
    val name: String,
    val rowsProvider: () -> List<TrackerRow>,
)

/**
 * One row in the tracker's scrollable list.
 *
 * @property label Left-aligned text — typically the creature / item / target name.
 * @property primaryValue Right-aligned headline value (e.g. catches).
 * @property secondaryValue Optional secondary value shown after [primaryValue] (e.g. double-hooks).
 * @property secondaryLabel Suffix appended to [secondaryValue] (e.g. "DH").
 * @property sortValues Per-sort-key values used by the sort button. Missing keys sort as 0.
 */
data class TrackerRow(
    val label: String,
    val primaryValue: Long,
    val secondaryValue: Long? = null,
    val secondaryLabel: String = "",
    val sortValues: Map<String, Long> = emptyMap(),
)

/** Sort mode selectable in the footer. Rows are sorted by [TrackerRow.sortValues] under [key]. */
data class TrackerSortOption(
    val key: String,
    val label: String,
)
