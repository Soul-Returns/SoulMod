package com.soulreturns.ui.hud.tracker

import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.background
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.height
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.composer.weight
import com.soulreturns.ui.composer.width
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Button
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Dropdown
import com.soulreturns.ui.foundation.DropdownOption
import com.soulreturns.ui.foundation.MultiSelectDropdown
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.ScrollableList
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Tabs
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import java.util.Locale

/**
 * Generic tracker HUD composable — consumes a [TrackerSpec] + [TrackerSettings] and renders
 * the full Fishing-HUD-style panel: header (title + optional festival-style banner +
 * timer/tabs), divider, scrollable list of rows, per-column summary chips, divider, footer
 * (filter dropdown + Show All button, Sort + Columns dropdowns, Reset button).
 *
 * **Two-flavour usage:**
 *  - A **general tracker** (sea creatures, mineshaft drops, slayer kills, …) provides
 *    domain-specific count columns (`Catches`, `DH`, `Cocoons`).
 *  - A **profit tracker** (dragon drops, future slayer/mining profit) adds an `Amount`
 *    column (count) + `Value` column (coin value via `PriceCache`); same composable
 *    renders both.
 *
 * The composable handles all the layout, the dropdown popups, and the at-least-one-column-
 * visible invariant. Callers only define the row type, the columns, the sorts, and where
 * the data comes from — see `FishingHud` and `DragonProfitHud` for examples.
 */
@SoulComposable
fun <T : Any> TrackerHud(
    spec: TrackerSpec<T>,
    settings: TrackerSettings,
) {
    if (!spec.isVisible()) {
        Box {}
        return
    }
    // Footer (filter / sort / columns / reset) and the Session/Total tab switcher only render
    // when the player's own inventory is open. Other AbstractContainerScreens (chests, anvils,
    // Hypixel-custom GUIs) shouldn't spawn a config UI for the tracker every time the user
    // opens a chest — that's noisy. Outside the inventory, collapse the panel to data-only
    // and replace the Tabs widget with a dim label so the active scope is still visible.
    val interactive = Minecraft.getInstance().screen is InventoryScreen

    Surface(
        modifier = SoulModifier.Empty.fillMaxWidth(),
        color = if (SoulHud.shouldDrawBackground(spec.id)) SoulTheme.colors.panel else 0x00000000,
    ) {
        Column(gap = 6f, modifier = SoulModifier.Empty.fillMaxWidth()) {
            Header(spec, settings, interactive)
            HorizontalDivider()
            RowList(spec, settings)
            if (anyChipVisible(spec, settings)) {
                ChipsLine(spec, settings)
            }
            if (interactive) {
                HorizontalDivider()
                Footer(spec, settings)
            }
        }
    }
}

// ───────────────────── header ─────────────────────

@SoulComposable
private fun <T : Any> Header(
    spec: TrackerSpec<T>,
    settings: TrackerSettings,
    interactive: Boolean,
) {
    Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 4f) {
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = VerticalAlignment.Center,
        ) {
            Text(
                text = spec.title,
                size = SoulTheme.typography.heading.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.heading.font,
            )
        }
        spec.headerExtra?.invoke()
        // Timer + tabs row. Both halves render unconditionally so SpaceBetween balances even
        // when there's no timer (timer side becomes an empty Row, tabs sit flush-right).
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = VerticalAlignment.Center,
        ) {
            val timer = spec.timerLine?.invoke()
            if (timer != null) {
                Row(gap = 6f, verticalAlignment = VerticalAlignment.Center) {
                    Text(
                        text = timer.text,
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.text,
                        font = SoulTheme.typography.mono.font,
                    )
                    if (timer.paused) {
                        Text(
                            text = "(Paused)",
                            size = SoulTheme.typography.body.size,
                            color = COLOR_PAUSED_RED,
                            font = SoulTheme.typography.body.font,
                        )
                    }
                }
            } else {
                Box {}
            }
            if (interactive) {
                Tabs(
                    options = TrackerTab.values().map { it.name },
                    selectedIndex = settings.tab.ordinal,
                    onSelect = { idx ->
                        val newTab = TrackerTab.values()[idx]
                        if (newTab != settings.tab) {
                            settings.tab = newTab
                            settings.scrollOffset = 0f
                            settings.markDirty()
                        }
                    },
                    keyPrefix = "${spec.id}.tabs",
                )
            } else {
                Text(
                    text = settings.tab.name,
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.body.font,
                )
            }
        }
    }
}

// ───────────────────── row list ─────────────────────

@SoulComposable
private fun <T : Any> RowList(
    spec: TrackerSpec<T>,
    settings: TrackerSettings,
) {
    val rows = buildRows(spec, settings)
    ScrollableList(
        scrollOffset = settings.scrollOffset,
        onScroll = { newOffset ->
            settings.scrollOffset = newOffset
            settings.markDirty()
        },
        modifier = SoulModifier.Empty.fillMaxWidth().height(LIST_HEIGHT),
        gap = 2f,
        key = "${spec.id}.scroll",
    ) {
        if (rows.isEmpty()) {
            Text(
                text = spec.emptyText,
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.textFaint,
                font = SoulTheme.typography.body.font,
            )
        } else {
            rows.forEach { row -> RenderRow(spec, settings, row) }
        }
    }
}

@SoulComposable
private fun <T : Any> RenderRow(
    spec: TrackerSpec<T>,
    settings: TrackerSettings,
    row: T,
) {
    Row(
        modifier = SoulModifier.Empty.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        gap = 6f,
        verticalAlignment = VerticalAlignment.Center,
    ) {
        Text(
            text = spec.rowLabel(row),
            size = SoulTheme.typography.body.size,
            color = spec.rowLabelColor(row),
            font = SoulTheme.typography.body.font,
        )
        // Right-side numeric cells. Render visible columns in spec-declared order, divider
        // between each. Hidden columns drop out entirely (no reserved space).
        Row(gap = 2f, verticalAlignment = VerticalAlignment.Center) {
            var first = true
            spec.columns.forEach { column ->
                if (!settings.isColumnVisible(column.id, column.defaultVisible)) return@forEach
                if (!first) ColumnDivider()
                NumberCell(
                    text = column.formatCell(column.cellValue(row, settings.tab)),
                    color = column.resolvedCellColor(),
                    isCaption = column.isCaption,
                    width = column.cellWidth,
                )
                first = false
            }
        }
    }
}

@SoulComposable
private fun ColumnDivider() {
    // Outer Box absorbs the top-offset so the visible 9 px line sits below Inter's ascender
    // region; the inner Box paints the actual 1×9 px separator.
    Box(modifier = SoulModifier.Empty.padding(top = COLUMN_DIVIDER_TOP_OFFSET)) {
        Box(
            modifier =
                SoulModifier.Empty
                    .width(COLUMN_DIVIDER_WIDTH)
                    .height(COLUMN_DIVIDER_HEIGHT)
                    .background(SoulTheme.colors.separator),
        )
    }
}

@SoulComposable
private fun NumberCell(
    text: String,
    color: Int,
    isCaption: Boolean,
    width: Float,
) {
    Row(
        modifier = SoulModifier.Empty.width(width),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = VerticalAlignment.Center,
    ) {
        Text(
            text = text,
            size = if (isCaption) SoulTheme.typography.caption.size else SoulTheme.typography.body.size,
            color = color,
            font = SoulTheme.typography.mono.font,
        )
    }
}

// ───────────────────── chips ─────────────────────

private fun <T : Any> anyChipVisible(
    spec: TrackerSpec<T>,
    settings: TrackerSettings,
): Boolean =
    spec.columns.any { col ->
        settings.isColumnVisible(col.id, col.defaultVisible) && col.chipShouldRender()
    }

@SoulComposable
private fun <T : Any> ChipsLine(
    spec: TrackerSpec<T>,
    settings: TrackerSettings,
) {
    val rows = buildRows(spec, settings)
    val visible =
        spec.columns.filter { col ->
            settings.isColumnVisible(col.id, col.defaultVisible) && col.chipShouldRender()
        }
    // 1 chip → flush right; ≥ 2 chips → SpaceBetween distributes across the row width. The
    // arrangement matches FishingHud's chip-line behaviour exactly.
    val arrangement = if (visible.size == 1) Arrangement.End else Arrangement.SpaceBetween
    val totals: Map<String, Long> =
        visible.associate { col -> col.id to col.totalValue(rows, settings.tab) }
    Row(
        modifier = SoulModifier.Empty.fillMaxWidth(),
        horizontalArrangement = arrangement,
        gap = 8f,
    ) {
        visible.forEach { col ->
            val total = totals[col.id] ?: 0L
            val denominator = col.chipPercentageOfColumn?.let { totals[it] } ?: 0L
            val text = formatChip(col.chipLabel, total, denominator, col.chipPercentageOfColumn, col.formatChipTotal)
            Text(
                text = text,
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.accent,
                font = SoulTheme.typography.mono.font,
            )
        }
    }
}

private fun formatChip(
    label: String,
    count: Long,
    denominator: Long,
    percentageOf: String?,
    formatTotal: (Long) -> String,
): String =
    if (percentageOf != null && denominator > 0L) {
        val pct = count.toDouble() / denominator.toDouble() * 100.0
        String.format(Locale.ROOT, "%s: %s (%.1f%%)", label, formatTotal(count), pct)
    } else {
        String.format(Locale.ROOT, "%s: %s", label, formatTotal(count))
    }

// ───────────────────── footer ─────────────────────

@SoulComposable
private fun <T : Any> Footer(
    spec: TrackerSpec<T>,
    settings: TrackerSettings,
) {
    Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f) {
        spec.footerExtra?.invoke()
        val variants = spec.filterVariants()
        if (variants.isNotEmpty()) {
            Row(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f) {
                MultiSelectDropdown(
                    label = spec.filterLabel(settings.filter),
                    options =
                        variants.map { variant ->
                            DropdownOption(
                                label = variant,
                                selected = variant in settings.filter,
                            ) {
                                settings.filter =
                                    if (variant in settings.filter) {
                                        settings.filter - variant
                                    } else {
                                        settings.filter + variant
                                    }
                                settings.scrollOffset = 0f
                                settings.markDirty()
                            }
                        },
                    expanded = settings.filterDropdownOpen,
                    onExpandedChange = { settings.filterDropdownOpen = it },
                    modifier = SoulModifier.Empty.weight(2f),
                    key = "${spec.id}.filter",
                    popupMaxHeight = FOOTER_DROPDOWN_MAX_HEIGHT,
                )
                Button(
                    label = "Show All",
                    onClick = {
                        if (settings.filter.isNotEmpty()) {
                            settings.filter = emptySet()
                            settings.scrollOffset = 0f
                            settings.markDirty()
                        }
                    },
                    modifier = SoulModifier.Empty.weight(1f),
                    centerLabel = true,
                    key = "${spec.id}.showAll",
                )
            }
        }
        Row(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f) {
            val sortOptions = visibleSorts(spec, settings)
            val activeSort = sortOptions.firstOrNull { it.id == settings.sortId } ?: sortOptions.first()
            if (settings.sortId != activeSort.id) {
                settings.sortId = activeSort.id
                settings.markDirty()
            }
            val sortIndex = sortOptions.indexOf(activeSort).coerceAtLeast(0)
            Dropdown(
                triggerLabel = "Sort: ${activeSort.label}",
                options = sortOptions.map { it.label },
                selectedIndex = sortIndex,
                onSelect = { idx ->
                    settings.sortId = sortOptions[idx].id
                    settings.scrollOffset = 0f
                    settings.markDirty()
                },
                expanded = settings.sortDropdownOpen,
                onExpandedChange = { settings.sortDropdownOpen = it },
                modifier = SoulModifier.Empty.weight(1f),
                key = "${spec.id}.sort",
            )
            MultiSelectDropdown(
                label = "Columns",
                options =
                    spec.columns.map { col ->
                        DropdownOption(
                            label = col.toggleLabel,
                            selected = settings.isColumnVisible(col.id, col.defaultVisible),
                        ) {
                            toggleColumn(spec, settings, col.id)
                        }
                    },
                expanded = settings.columnDropdownOpen,
                onExpandedChange = { settings.columnDropdownOpen = it },
                modifier = SoulModifier.Empty.weight(1f),
                key = "${spec.id}.columns",
            )
        }
        if (spec.onResetSession != null) {
            Button(
                label = spec.resetLabel,
                onClick = spec.onResetSession,
                modifier = SoulModifier.Empty.fillMaxWidth(),
                centerLabel = true,
                key = "${spec.id}.reset",
            )
        }
    }
}

// ───────────────────── helpers ─────────────────────

private fun <T : Any> visibleSorts(
    spec: TrackerSpec<T>,
    settings: TrackerSettings,
): List<TrackerSort<T>> =
    spec.sorts.filter { sort ->
        val req = sort.requiresColumnId ?: return@filter true
        val col = spec.columns.firstOrNull { it.id == req }
        col == null || settings.isColumnVisible(col.id, col.defaultVisible)
    }.ifEmpty { spec.sorts }

/**
 * Flip the visibility of [columnId]. Refuses to leave the panel with zero visible columns
 * (the row would render label-only — useless). Snaps the active sort to the first remaining
 * visible one when the current sort's dependent column got hidden.
 */
private fun <T : Any> toggleColumn(
    spec: TrackerSpec<T>,
    settings: TrackerSettings,
    columnId: String,
) {
    val col = spec.columns.firstOrNull { it.id == columnId } ?: return
    val currentlyVisible = settings.isColumnVisible(col.id, col.defaultVisible)
    val proposedVisible = !currentlyVisible
    if (!proposedVisible) {
        val remainingVisible =
            spec.columns.count { c ->
                if (c.id == columnId) false else settings.isColumnVisible(c.id, c.defaultVisible)
            }
        if (remainingVisible == 0) return
    }
    settings.setColumnVisible(columnId, proposedVisible)
    val sorts = visibleSorts(spec, settings)
    if (sorts.none { it.id == settings.sortId } && sorts.isNotEmpty()) {
        settings.sortId = sorts.first().id
    }
    settings.markDirty()
}

private fun <T : Any> buildRows(
    spec: TrackerSpec<T>,
    settings: TrackerSettings,
): List<T> {
    val raw = spec.rowsProvider(settings.tab)
    val filtered =
        if (settings.filter.isEmpty()) raw else raw.filter { spec.filterPredicate(it, settings.filter) }
    val sort = spec.sorts.firstOrNull { it.id == settings.sortId } ?: spec.sorts.firstOrNull()
    val comparator =
        if (sort != null) {
            sort.comparator.then(compareBy { spec.rowLabel(it) })
        } else {
            compareBy { spec.rowLabel(it) }
        }
    return filtered.sortedWith(comparator)
}

@SoulComposable
private fun HorizontalDivider() {
    Box(
        modifier =
            SoulModifier.Empty
                .fillMaxWidth()
                .height(SEPARATOR_HEIGHT)
                .background(SoulTheme.colors.separator),
    )
}

private const val SEPARATOR_HEIGHT = 1f
private const val COLOR_PAUSED_RED = 0xFFFF5555.toInt()
private const val LIST_HEIGHT = 140f
private const val FOOTER_DROPDOWN_MAX_HEIGHT = 156f
private const val COLUMN_DIVIDER_WIDTH = 1f
private const val COLUMN_DIVIDER_HEIGHT = 9f
private const val COLUMN_DIVIDER_TOP_OFFSET = 2f
