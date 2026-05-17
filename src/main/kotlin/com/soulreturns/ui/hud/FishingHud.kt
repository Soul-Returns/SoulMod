package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.fishing.SeaCreatureCatalog
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.features.fishing.FishingHudSettings
import com.soulreturns.features.fishing.FishingTimer
import com.soulreturns.features.fishing.FishingTracker
import com.soulreturns.features.fishing.FishingVisibility
import com.soulreturns.stats.PersistentStats
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
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
 * Fishing HUD — per-creature sea-creature tracker with tabs, sorting, paging, and reset.
 *
 * **Built fresh in P3 on the Soul UI framework** — replaces the legacy `FishingTrackerOverlay`
 * + `gui/lib/tracker/` package. Composables instead of imperative drawing; settings via the
 * per-feature [FishingHudSettings] instead of the generic `TrackerSettingsStore`.
 *
 * Layout:
 * ```
 * ┌──────────────────────────────────────────────────────────┐
 * │ Fishing                                  [Session][Total]│  ← header
 * │ Fishing Time: 00:12:34                       Cocoons: 7  │  ← timer + cocoon summary
 * │ ────────────────────────────────────────────────────────  │
 * │ Sea Archer                  47 │ 12 DH │ 0 CC            │  ← scrollable list,
 * │ Lord Jawbus                  3 │  0 DH │ 0 CC            │    fixed-width right-aligned
 * │ ...                                                       │    cells with vertical
 * │ ────────────────────────────────────────────────────────  │    dividers
 * │ [Sort: Catches▾]   [Columns▾]                            │  ← footer (controls row)
 * │ [          Reset Session         ]                       │  ← footer (full-width)
 * └──────────────────────────────────────────────────────────┘
 * ```
 *
 * The footer and the [Session/Total] toggle only render while an `AbstractContainerScreen`
 * is open — outside of that the active tab shows as a dim label and the footer is hidden,
 * so the HUD reads as data-only during normal play.
 */
object FishingHud {
    private const val HUD_ID = "fishing_tracker"
    private const val SEPARATOR_HEIGHT = 1f
    private const val COLOR_PAUSED_RED = 0xFFFF5555.toInt() // §c
    private const val COLOR_FESTIVAL_GOLD = 0xFFFFAA00.toInt() // §6 — matches Hypixel's festival msg colour

    // Per-column slot width (logical pixels). Wide enough for a 3-digit comma-formatted
    // number plus the 3-char " DH" / " CC" suffix in the mono font at caption size. Tight
    // on purpose: number is `Arrangement.Center`-positioned in the slot, and a thin
    // vertical divider sits in the small gap between adjacent slots.
    private const val NUMBER_CELL_WIDTH = 32f

    // Vertical divider between visible columns. The visible line is 9px tall; we wrap it
    // in a Box with `padding(top = 2f)` so the outer measured height (11f) matches the
    // body-text bounding box of a NumberCell. The 2px top-padding compensates for Inter's
    // ascender region — without it, the divider would sit centered in the text *bbox* but
    // visually float above the digit *glyph*, since digits occupy roughly the lower 70% of
    // their bbox.
    private const val COLUMN_DIVIDER_WIDTH = 1f
    private const val COLUMN_DIVIDER_HEIGHT = 9f
    private const val COLUMN_DIVIDER_TOP_OFFSET = 2f

    fun register() {
        FishingHudSettings.init()
        SoulHud.register(
            id = HUD_ID,
            width = 280,
            // Tall enough to fit: surface padding (24), header column (title row 13 +
            // timer/tabs row 18 + 4 gap = ~35), chips line (14), two separator dividers +
            // their column gaps (~16), scrollable list (140), footer column (~95: Category
            // row + Sort/Columns row + Reset Session row + gaps), and Column gaps between
            // them (~36).
            height = 380,
            // Top-left default. Shares its default slot with Seasoning tracker (only one is
            // contextually relevant per area — Farming/Garden vs Fishing islands), so a
            // shared origin keeps the default HUD layout uncluttered.
            defaultAnchorX = 0.01,
            defaultAnchorY = 0.02,
            settingsCategory = "fishing",
            settingsSubcategory = "fishingHud",
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        val hudCfg = cfg.fishing.fishingHud
        val showHud =
            hudCfg.showHud() &&
                cfg.dev.trackers.fishingTracker() &&
                SkyblockApi.isOnSkyblock &&
                FishingVisibility.isVisible
        if (!showHud) {
            Box {}
            return
        }
        val settings = FishingHudSettings.get()
        // Controls (tab switcher + footer buttons) only render in the **player inventory**.
        // SoulGuiHudAdapter actually routes HUD clicks for any `AbstractContainerScreen<*>`
        // (chests, anvils, Hypixel-custom GUIs, …) but expanding the footer on every chest
        // open is noisy — opening a chest is a routine inventory transfer action, not a
        // "I want to configure my fishing tracker" intent. Gating on `InventoryScreen`
        // specifically means players need to press their own inventory key (E by default)
        // to fiddle with sort / column / category filters. Outside the player inventory,
        // collapse the panel to the data and replace the tab switcher with a plain label
        // so the active scope is still visible.
        val interactive = Minecraft.getInstance().screen is InventoryScreen

        Surface(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            color = if (SoulHud.shouldDrawBackground(HUD_ID)) SoulTheme.colors.panel else 0x00000000,
        ) {
            Column(gap = 6f, modifier = SoulModifier.Empty.fillMaxWidth()) {
                Header(settings, interactive)
                HorizontalDivider()
                List(settings)
                // Totals row: per-column summary chips below the list so the row aggregates
                // sit right next to the per-creature numbers. Skipped entirely when every
                // column is hidden so the panel doesn't reserve vertical space for an empty
                // row + its surrounding Column gaps.
                if (settings.showCatches || settings.showDoubleHooks || settings.showCocoons) {
                    ChipsLine(settings)
                }
                if (interactive) {
                    HorizontalDivider()
                    Footer(settings)
                }
            }
        }
    }

    @SoulComposable
    private fun ChipsLine(settings: FishingHudSettings.Settings) {
        // Layout rules:
        //  - 3 chips: SpaceBetween puts the first at left, last at right, the middle one
        //    pinned between (visually centered when chip widths are balanced).
        //  - 2 chips: SpaceBetween puts them at opposite ends.
        //  - 1 chip: SpaceBetween degrades to Start (single child has no "between"). Force
        //    `End` so a lone chip sits flush right — keeps the line visually anchored to the
        //    summary column on the right of the per-row numbers.
        val visibleChips =
            listOf(settings.showCocoons, settings.showDoubleHooks, settings.showCatches).count { it }
        val arrangement =
            if (visibleChips == 1) Arrangement.End else Arrangement.SpaceBetween
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = arrangement,
            gap = 8f,
        ) {
            // `cfg.fishing.fishingHud.addDoubleHookToCatches` / `addCocoonToCatches` roll
            // the corresponding bucket into the Catches display so users who think of those
            // as catches see a single combined number. Affects both the displayed total AND
            // the percentage denominator below, so the chips stay self-consistent.
            val hudCfg = cfg.fishing.fishingHud
            val baseCatches = sessionOrTotalCatches(settings.tab)
            val cocoons = sessionOrTotalCocoons(settings.tab)
            val dh = sessionOrTotalDoubleHooks(settings.tab)
            val catches =
                baseCatches +
                    (if (hudCfg.addDoubleHookToCatches()) dh else 0L) +
                    (if (hudCfg.addCocoonToCatches()) cocoons else 0L)
            // Chip order mirrors the per-row column order (CC → DH → Catches), so the eye
            // sweeps left-to-right consistently between the summary line and the rows.
            if (settings.showCocoons) {
                Text(
                    text = formatChip("CC", cocoons, catches),
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.accent,
                    font = SoulTheme.typography.mono.font,
                )
            }
            if (settings.showDoubleHooks) {
                Text(
                    text = formatChip("DH", dh, catches),
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.accent,
                    font = SoulTheme.typography.mono.font,
                )
            }
            if (settings.showCatches) {
                // Catches is the denominator — no percent suffix, just the total.
                Text(
                    text = String.format(Locale.ROOT, "Catches: %,d", catches),
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.accent,
                    font = SoulTheme.typography.mono.font,
                )
            }
        }
    }

    /**
     * Format a "Label: N (P.P%)" chip — used by both the DH and Cocoons summary on the
     * second row. Percentage = [count] / [catches] × 100, rendered with one decimal place
     * via `Locale.ROOT` so locales that swap `.` and `,` for the decimal separator don't
     * break the chip text. Falls back to "Label: N" with no percent when [catches] == 0
     * (the only way that can happen is the very-first session before any catch lands).
     */
    private fun formatChip(
        label: String,
        count: Long,
        catches: Long,
    ): String =
        if (catches > 0L) {
            val pct = count.toDouble() / catches.toDouble() * 100.0
            String.format(Locale.ROOT, "%s: %,d (%.1f%%)", label, count, pct)
        } else {
            String.format(Locale.ROOT, "%s: %,d", label, count)
        }

    /**
     * Format the festival countdown — Hypixel festivals last 60 minutes, so we only need
     * `Xm YYs`. Mirrors the old standalone sticker HUD's format.
     */
    private fun formatFestivalDuration(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.ROOT, "%dm %02ds", m, s)
    }

    private fun sessionOrTotalCocoons(tab: FishingHudSettings.Tab): Long =
        when (tab) {
            FishingHudSettings.Tab.Session -> FishingTracker.sessionCocoons
            FishingHudSettings.Tab.Total -> PersistentStats.current.cocoonsAllTime
        }

    private fun sessionOrTotalDoubleHooks(tab: FishingHudSettings.Tab): Long =
        when (tab) {
            FishingHudSettings.Tab.Session -> FishingTracker.sessionDoubleHooks
            FishingHudSettings.Tab.Total -> PersistentStats.current.doubleHooksAllTime
        }

    private fun sessionOrTotalCatches(tab: FishingHudSettings.Tab): Long =
        when (tab) {
            FishingHudSettings.Tab.Session -> FishingTracker.sessionCatches
            FishingHudSettings.Tab.Total -> PersistentStats.current.catchesAllTime
        }

    @SoulComposable
    private fun Header(
        settings: FishingHudSettings.Settings,
        interactive: Boolean,
    ) {
        // Stacked rows. Top: full-width "Fishing" title centered. Optional middle:
        // festival countdown (gold accent) when a fishing festival is active and the
        // `showFestivalTimer` toggle is on — integrated here rather than as a standalone
        // sticker HUD so users don't need to position a second element. Bottom: session
        // timer on the left, tabs on the right (SpaceBetween).
        Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 4f) {
            Row(
                modifier = SoulModifier.Empty.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = com.soulreturns.ui.composer.VerticalAlignment.Center,
            ) {
                Text(
                    text = "Fishing",
                    size = SoulTheme.typography.heading.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.heading.font,
                )
            }
            // Festival countdown — only present when a festival is active AND the user
            // hasn't turned the toggle off. Gold-accented (§6) heading-size text matching
            // the Hypixel festival message colour. Composable is the only "header" element
            // visible to users today; the standalone `fishing_festival_sticker` HUD is gone.
            if (cfg.fishing.fishingHud.showFestivalTimer() && FishingFestivalState.active) {
                Row(
                    modifier = SoulModifier.Empty.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = com.soulreturns.ui.composer.VerticalAlignment.Center,
                    gap = 6f,
                ) {
                    Text(
                        text = "Fishing Festival",
                        size = SoulTheme.typography.body.size,
                        color = COLOR_FESTIVAL_GOLD,
                        font = SoulTheme.typography.heading.font,
                    )
                    Text(
                        text = "—",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.textDim,
                        font = SoulTheme.typography.body.font,
                    )
                    Text(
                        text = "${formatFestivalDuration(FishingFestivalState.remainingMs())} left",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.text,
                        font = SoulTheme.typography.body.font,
                    )
                }
            }
            Row(
                modifier = SoulModifier.Empty.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = com.soulreturns.ui.composer.VerticalAlignment.Center,
            ) {
                Row(
                    gap = 6f,
                    verticalAlignment = com.soulreturns.ui.composer.VerticalAlignment.Center,
                ) {
                    Text(
                        text = FishingTimer.formatTime(),
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.text,
                        font = SoulTheme.typography.mono.font,
                    )
                    if (FishingTimer.isPaused) {
                        Text(
                            text = "(Paused)",
                            size = SoulTheme.typography.body.size,
                            color = COLOR_PAUSED_RED,
                            font = SoulTheme.typography.body.font,
                        )
                    }
                }
                if (interactive) {
                    Tabs(
                        options = FishingHudSettings.Tab.values().map { it.name },
                        selectedIndex = settings.tab.ordinal,
                        onSelect = { idx ->
                            val newTab = FishingHudSettings.Tab.values()[idx]
                            if (newTab != settings.tab) {
                                settings.tab = newTab
                                settings.scrollOffset = 0f
                                FishingHudSettings.markDirty()
                            }
                        },
                        keyPrefix = "$HUD_ID.tabs",
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

    @SoulComposable
    private fun List(settings: FishingHudSettings.Settings) {
        val rows = buildRows(settings)
        ScrollableList(
            scrollOffset = settings.scrollOffset,
            onScroll = { newOffset ->
                settings.scrollOffset = newOffset
                FishingHudSettings.markDirty()
            },
            modifier = SoulModifier.Empty.fillMaxWidth().height(140f),
            gap = 2f,
            key = "$HUD_ID.scroll",
        ) {
            if (rows.isEmpty()) {
                Text(
                    text = "(no data yet)",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textFaint,
                    font = SoulTheme.typography.body.font,
                )
            } else {
                rows.forEach { row -> Row(row, settings) }
            }
        }
    }

    @SoulComposable
    private fun Row(
        row: CreatureRow,
        settings: FishingHudSettings.Settings,
    ) {
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            gap = 6f,
            verticalAlignment = com.soulreturns.ui.composer.VerticalAlignment.Center,
        ) {
            Text(
                text = row.name,
                size = SoulTheme.typography.body.size,
                color = row.nameColor,
                font = SoulTheme.typography.body.font,
            )
            // Numeric columns: fixed-width centered cells so the divider sits visually
            // between values. Display order is CC → DH → Catches (rarest / most surprising
            // signals leftmost; the bread-and-butter Catches column on the right where the
            // eye expects the dominant metric). Hidden columns (toggled off in the footer)
            // are omitted; thin vertical dividers separate visible cells.
            Row(
                gap = 2f,
                verticalAlignment = com.soulreturns.ui.composer.VerticalAlignment.Center,
            ) {
                var first = true
                if (settings.showCocoons) {
                    NumberCell(
                        text = "%,d CC".format(row.cocoons),
                        color = SoulTheme.colors.textDim,
                        isCaption = true,
                    )
                    first = false
                }
                if (settings.showDoubleHooks) {
                    if (!first) ColumnDivider()
                    NumberCell(
                        text = "%,d DH".format(row.doubleHooks),
                        color = SoulTheme.colors.textDim,
                        isCaption = true,
                    )
                    first = false
                }
                if (settings.showCatches) {
                    if (!first) ColumnDivider()
                    // Same "Add DH / Cocoon to catches" toggle as the totals line — keeps
                    // per-row numbers in sync with the totals row at the bottom.
                    val hudCfg = cfg.fishing.fishingHud
                    val displayedCatches =
                        row.catches +
                            (if (hudCfg.addDoubleHookToCatches()) row.doubleHooks else 0L) +
                            (if (hudCfg.addCocoonToCatches()) row.cocoons else 0L)
                    NumberCell(
                        text = "%,d".format(displayedCatches),
                        color = SoulTheme.colors.accent,
                        isCaption = false,
                    )
                }
            }
        }
    }

    @SoulComposable
    private fun ColumnDivider() {
        // Outer Box: holds the top-offset padding so the visible line drops below the
        // ascender region of the row's text. Inner Box: the actual 1×9px separator strip.
        // Combined outer height (= padding + inner) matches the NumberCell text bbox so
        // the row's vertical alignment lines everything up.
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
    ) {
        // Center-align inside the fixed-width cell so the visible number sits in the
        // middle of the column. Right-aligning here makes the column-dividers feel
        // glued to the left number (because the next cell's empty padding sits between
        // the divider and the next visible number). Center-align keeps each visible
        // number equidistant from the dividers on both sides — at the cost of digit
        // alignment across rows, which the user prefers visually balanced over
        // strictly tabular here.
        Row(
            modifier = SoulModifier.Empty.width(NUMBER_CELL_WIDTH),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = com.soulreturns.ui.composer.VerticalAlignment.Center,
        ) {
            Text(
                text = text,
                size = if (isCaption) SoulTheme.typography.caption.size else SoulTheme.typography.body.size,
                color = color,
                font = SoulTheme.typography.mono.font,
            )
        }
    }

    @SoulComposable
    private fun Footer(settings: FishingHudSettings.Settings) {
        Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f) {
            // Filter — multi-select over sea-creature variants. Empty selection = unfiltered
            // (no synthetic "All" row in the popup; Show All is a dedicated button to the
            // right of the trigger). Toggling a variant resets scroll so the user doesn't
            // land halfway down a (now shorter) list.
            val variants = SeaCreatureCatalog.variants()
            val filterLabel =
                if (settings.categories.isEmpty()) {
                    "Filter"
                } else {
                    "Filter (${settings.categories.size})"
                }
            Row(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f) {
                MultiSelectDropdown(
                    label = filterLabel,
                    options =
                        variants.map { variant ->
                            val display =
                                with(SeaCreatureCatalog) { variant.toDisplayName() }
                            DropdownOption(
                                label = display,
                                selected = variant in settings.categories,
                            ) {
                                settings.categories =
                                    if (variant in settings.categories) {
                                        settings.categories - variant
                                    } else {
                                        settings.categories + variant
                                    }
                                settings.scrollOffset = 0f
                                FishingHudSettings.markDirty()
                            }
                        },
                    expanded = FishingHudSettings.categoryDropdownOpen,
                    onExpandedChange = { FishingHudSettings.categoryDropdownOpen = it },
                    modifier = SoulModifier.Empty.weight(2f),
                    key = "$HUD_ID.filter",
                    // 19 SkyHanni variants don't fit in any direction; cap to ~8 visible rows
                    // and let the user scroll through the rest.
                    popupMaxHeight = 156f,
                )
                Button(
                    label = "Show All",
                    onClick = {
                        if (settings.categories.isNotEmpty()) {
                            settings.categories = emptySet()
                            settings.scrollOffset = 0f
                            FishingHudSettings.markDirty()
                        }
                    },
                    modifier = SoulModifier.Empty.weight(1f),
                    centerLabel = true,
                    key = "$HUD_ID.showAll",
                )
            }
            // Sort dropdown (single-select) + columns dropdown (multi-select). Both open
            // upward as popup overlays — see `MultiSelectDropdown` for the depth/scrim model.
            // The Sort dropdown's options are filtered to currently-visible columns; selecting
            // a hidden sort key would silently drift the row order. `.weight(1f)` on each
            // splits the row width evenly between them so they always span the full footer.
            Row(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f) {
                val sortOptions =
                    FishingHudSettings.Sort.values().filter { settings.isSortVisible(it) }
                val sortIndex = sortOptions.indexOf(settings.sort).coerceAtLeast(0)
                Dropdown(
                    triggerLabel = "Sort: ${settings.sort.label}",
                    options = sortOptions.map { it.label },
                    selectedIndex = sortIndex,
                    onSelect = { idx ->
                        settings.sort = sortOptions[idx]
                        settings.scrollOffset = 0f
                        FishingHudSettings.markDirty()
                    },
                    expanded = FishingHudSettings.sortDropdownOpen,
                    onExpandedChange = { FishingHudSettings.sortDropdownOpen = it },
                    modifier = SoulModifier.Empty.weight(1f),
                    key = "$HUD_ID.sort",
                )
                MultiSelectDropdown(
                    label = "Columns",
                    options =
                        listOf(
                            DropdownOption("Catches", settings.showCatches) {
                                toggleColumn(settings) { showCatches = !showCatches }
                            },
                            DropdownOption("Double Hooks", settings.showDoubleHooks) {
                                toggleColumn(settings) { showDoubleHooks = !showDoubleHooks }
                            },
                            DropdownOption("Cocoons", settings.showCocoons) {
                                toggleColumn(settings) { showCocoons = !showCocoons }
                            },
                        ),
                    expanded = FishingHudSettings.columnDropdownOpen,
                    onExpandedChange = { FishingHudSettings.columnDropdownOpen = it },
                    modifier = SoulModifier.Empty.weight(1f),
                    key = "$HUD_ID.columns",
                )
            }
            // Reset Session — full-width on its own row, centered label. Keeps a destructive
            // action visually separated from the Sort/Columns row above so it can't be
            // mis-clicked while picking a sort key.
            Button(
                label = "Reset Session",
                onClick = { FishingTracker.resetSession() },
                modifier = SoulModifier.Empty.fillMaxWidth(),
                centerLabel = true,
                key = "$HUD_ID.reset",
            )
        }
    }

    /**
     * Flip one of the column visibility flags via [block]. Refuses the flip if it would
     * leave zero visible columns (the row would become "name only" with no numbers, which
     * is useless) and snaps the [FishingHudSettings.Sort] key back to a visible column
     * when the active one just got hidden.
     */
    private fun toggleColumn(
        settings: FishingHudSettings.Settings,
        block: FishingHudSettings.Settings.() -> Unit
    ) {
        val backupCatches = settings.showCatches
        val backupDH = settings.showDoubleHooks
        val backupCC = settings.showCocoons
        settings.block()
        if (!settings.showCatches && !settings.showDoubleHooks && !settings.showCocoons) {
            settings.showCatches = backupCatches
            settings.showDoubleHooks = backupDH
            settings.showCocoons = backupCC
            return
        }
        if (!settings.isSortVisible(settings.sort)) {
            settings.sort = settings.firstVisibleSort()
        }
        FishingHudSettings.markDirty()
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

    // ───────────────────── row data assembly ─────────────────────

    private data class CreatureRow(
        val name: String,
        val nameColor: Int,
        /**
         * Sort key for `Sort.Rarity`. Higher = rarer (matches the `SkyblockRarity` enum
         * ordinal, where `COMMON=0` and `ULTIMATE=9`). Creatures not in the catalog get
         * `-1` so they sink to the bottom of a rarity sort instead of clumping with
         * commons.
         */
        val rarityOrdinal: Int,
        val catches: Long,
        val doubleHooks: Long,
        val cocoons: Long,
    )

    private fun buildRows(settings: FishingHudSettings.Settings): List<CreatureRow> {
        val catches: Map<String, Long>
        val doubleHooks: Map<String, Long>
        val cocoons: Map<String, Long>
        when (settings.tab) {
            FishingHudSettings.Tab.Session -> {
                catches = FishingTracker.sessionCatchesByCreature
                doubleHooks = FishingTracker.sessionDoubleHooksByCreature
                cocoons = FishingTracker.sessionCocoonsByCreature
            }
            FishingHudSettings.Tab.Total -> {
                val stats = PersistentStats.current
                catches = stats.catchesByCreature
                doubleHooks = stats.doubleHooksByCreature
                cocoons = stats.cocoonsByCreature
            }
        }
        val names = (catches.keys + doubleHooks.keys + cocoons.keys).toSet()
        val categoryFilter = settings.categories
        val rows =
            names
                .mapNotNull { name ->
                    val creature = SeaCreatureCatalog.byName(name)
                    // Filter applies on the creature's `variant`. Empty selection = show
                    // everything. Creatures not in the catalog (catch-line text that doesn't
                    // match any known entry) have no variant and are dropped entirely when
                    // any filter is active — they'd otherwise leak through every category.
                    if (categoryFilter.isNotEmpty() && creature?.variant !in categoryFilter) {
                        return@mapNotNull null
                    }
                    val displayName = creature?.name ?: name
                    val color = creature?.rarityColor() ?: SoulTheme.colors.text
                    val rarityOrdinal =
                        com.soulreturns.data.skyblock.SkyblockRarity.forName(creature?.rarity)
                            ?.ordinal ?: -1
                    CreatureRow(
                        name = displayName,
                        nameColor = color,
                        rarityOrdinal = rarityOrdinal,
                        catches = catches[name] ?: 0L,
                        doubleHooks = doubleHooks[name] ?: 0L,
                        cocoons = cocoons[name] ?: 0L,
                    )
                }
                .sortedWith(
                    when (settings.sort) {
                        FishingHudSettings.Sort.Catches ->
                            compareByDescending<CreatureRow> { it.catches }.thenBy { it.name }
                        FishingHudSettings.Sort.DoubleHooks ->
                            compareByDescending<CreatureRow> { it.doubleHooks }.thenBy { it.name }
                        FishingHudSettings.Sort.Cocoons ->
                            compareByDescending<CreatureRow> { it.cocoons }.thenBy { it.name }
                        FishingHudSettings.Sort.Rarity ->
                            compareByDescending<CreatureRow> { it.rarityOrdinal }.thenBy { it.name }
                        FishingHudSettings.Sort.Alphabetical ->
                            compareBy<CreatureRow> { it.name }
                    },
                )
        return rows
    }
}
