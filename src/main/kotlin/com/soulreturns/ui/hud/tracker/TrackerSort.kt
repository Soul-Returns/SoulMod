package com.soulreturns.ui.hud.tracker

/**
 * One sort option in the Sort dropdown.
 *
 * - [id] is the persisted key (saved in [TrackerSettings.sortId]).
 * - [label] is the human-readable label shown in the dropdown and as `"Sort: <label>"` on
 *   the trigger.
 * - [comparator] orders rows. The framework appends a stable alphabetical tie-break in
 *   [TrackerHud], so sorts that key on the same field will stay deterministic across
 *   identical numeric values.
 * - [requiresColumnId] hides this sort from the dropdown when its dependent column is
 *   hidden — sorting rows by an invisible number is confusing. Use `null` for sorts that
 *   don't bind to any specific column (e.g. `"Rarity"`, `"Alphabetical"`).
 */
data class TrackerSort<T>(
    val id: String,
    val label: String,
    val comparator: Comparator<T>,
    val requiresColumnId: String? = null,
)
