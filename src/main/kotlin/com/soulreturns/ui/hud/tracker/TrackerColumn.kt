package com.soulreturns.ui.hud.tracker

import com.soulreturns.ui.theme.SoulTheme
import java.util.Locale

/**
 * One numeric column inside a [TrackerHud] — one per-row cell + one summary chip below the
 * scroll list + one entry in the Columns multi-select dropdown.
 *
 * **Wiring:**
 * - [cellValue] / [totalValue] read from the tracker's data — usually `Tracker.session*` for
 *   the Session tab and `PersistentStats.current.*` (or a profit-tracker JSON) for the Total
 *   tab. The lambdas receive the row + the active tab so they can pick the right backing map.
 * - [formatCell] / [formatChipTotal] render the long value. Cells often include a tight unit
 *   suffix (`"5 DH"`); chips just show the comma-formatted number. Both default to
 *   `"%,d"` via `Locale.ROOT` to avoid locale-specific decimal separators.
 * - [chipPercentageOfColumn] — id of another column. When set, the chip text gets a
 *   `" (P.P%)"` suffix computed as `100 × this.total / denominator.total`. Use it for
 *   "this column as a fraction of catches / kills" displays. Null = plain "Label: N" chip.
 * - [chipShouldRender] hides the chip entirely when its source has nothing meaningful to
 *   say (e.g. profit chip when value column is hidden). Defaults to "always render".
 *
 * **No header in the row** — column identity is conveyed by the cell text format (`"5 DH"`,
 * `"-100k"`) and by the chip label below the list, not by a header row inside the
 * scrollable list itself. Saves vertical space and keeps the panel readable at HUD scale.
 */
data class TrackerColumn<T>(
    val id: String,
    /** Prefix shown in the summary chip below the scroll list, e.g. `"DH"`, `"Catches"`, `"Coins"`. */
    val chipLabel: String,
    /** Label shown in the Columns multi-select dropdown, e.g. `"Double Hooks"`. */
    val toggleLabel: String,
    /** Default visibility for fresh installs / unset entries in [TrackerSettings.isColumnVisible]. */
    val defaultVisible: Boolean = true,
    val cellValue: (row: T, tab: TrackerTab) -> Long,
    val totalValue: (rows: List<T>, tab: TrackerTab) -> Long,
    val formatCell: (Long) -> String = { String.format(Locale.ROOT, "%,d", it) },
    val formatChipTotal: (Long) -> String = { String.format(Locale.ROOT, "%,d", it) },
    /**
     * Caption (smaller font) vs body. Use caption for compact columns where the unit suffix
     * is more important than the number's prominence — Fishing's `"5 DH"` cells are caption,
     * `Catches: 12` body. Profit columns can use body for the value column and caption for
     * the count.
     */
    val isCaption: Boolean = false,
    /** Cell text color. Defaults to `textDim` for caption columns, `accent` for body. */
    val cellColor: Int? = null,
    /**
     * id of another column used as percentage denominator in the chip. When set, the chip
     * renders as `"Label: N (P.P%)"` where `P.P = 100 × this.total / denominator.total`.
     * `null` = no percentage suffix.
     */
    val chipPercentageOfColumn: String? = null,
    /** Returns false to hide this column's chip even when the column itself is visible. */
    val chipShouldRender: () -> Boolean = { true },
    /**
     * Fixed cell width in logical pixels. Bump above the 32 px default for columns that
     * format wider values — profit columns rendering `"12.5M coins"` need ~58 px; Fishing's
     * `"%,d DH"` cells fit comfortably at 32.
     */
    val cellWidth: Float = 32f,
) {
    /**
     * Color used for this column's cells. Mirrors the FishingHud convention: caption columns
     * (compact unit-suffix cells) get [SoulTheme.colors.textDim]; body columns (the primary
     * "headline" metric) get [SoulTheme.colors.accent]. Overrideable via [cellColor].
     */
    fun resolvedCellColor(): Int = cellColor ?: if (isCaption) SoulTheme.colors.textDim else SoulTheme.colors.accent
}
