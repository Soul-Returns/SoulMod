package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceSource
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.features.profit.dragon.DragonDrop
import com.soulreturns.features.profit.dragon.DragonProfitHudSettings
import com.soulreturns.features.profit.dragon.DragonProfitTracker
import com.soulreturns.features.profit.dragon.DragonType
import com.soulreturns.features.profit.dragon.KillSource
import com.soulreturns.gui.lib.HudHorizontalAnchor
import com.soulreturns.gui.lib.HudVerticalAnchor
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.foundation.Dropdown
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.hud.tracker.TrackerColumn
import com.soulreturns.ui.hud.tracker.TrackerHud
import com.soulreturns.ui.hud.tracker.TrackerSort
import com.soulreturns.ui.hud.tracker.TrackerSpec
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme
import java.util.Locale

/**
 * Dragon-profit HUD — drops + coin value tracker scoped to the End-island dragon nest.
 * First profit tracker built on the [TrackerHud] framework; serves as the reference
 * implementation for future profit trackers (slayer profit, mineshaft profit, …).
 *
 * **Drop detection.** Wired via [com.soulreturns.features.profit.dragon.DragonDeathDetector]
 * + [com.soulreturns.features.profit.dragon.DragonLootScanner] — listens for the
 * `"<TYPE> DRAGON DOWN!"` chat banner, then walks armor stands within an xz radius of the
 * player for 10 s to read `customName` values like `"Protector Dragon Fragment x8"`. The
 * scanner takes the max count seen per drop across multiple passes (handles pickup mid-
 * window + delayed-spawn drops). `/soul dev grantDragonDrop` still works for offline test
 * scenarios.
 *
 * Layout follows the same pattern as [FishingHud]: header (title + Session/Total tabs),
 * scrollable list of drops, summary chips (Drops + Profit), footer (Filter by dragon,
 * Sort, Columns, Reset Session). Filter dropdown variants = all 7 [DragonType]s; selecting
 * one or more scopes both the row aggregation AND the profit chip to those buckets only.
 */
object DragonProfitHud {
    private const val HUD_ID = "dragon_profit_tracker"

    /** Aggregated row — one per unique drop across the currently-filtered buckets. */
    data class Row(
        val drop: DragonDrop,
        val amount: Long,
        /** Per-row gross value (price × amount). Eye cost is subtracted only at the chip level. */
        val value: Long,
    )

    private val spec: TrackerSpec<Row> by lazy {
        TrackerSpec(
            id = HUD_ID,
            title = "Dragon Profit",
            width = 280,
            // Tall enough to fit: header (title + Eyes placed line + tabs row, ~50 px),
            // divider, scrollable list (140), chips line (~14), divider, footer (4 rows:
            // Source / Filter+ShowAll / Sort+Columns / Reset, each ~27 px button + 6 px
            // gap = ~33). The Source picker pushed the previous 360 over the edge and cut
            // the Reset button. 420 leaves a few pixels of slack.
            height = 420,
            // Top-right by default — End-island players typically have the boss bar at top,
            // chat at bottom-left; right edge is the most consistent unused real estate.
            defaultAnchorX = 0.99,
            defaultAnchorY = 0.02,
            defaultHorizontalAnchor = HudHorizontalAnchor.End,
            defaultVerticalAnchor = HudVerticalAnchor.Top,
            settingsCategory = "combat",
            settingsSubcategory = "dragons",
            columns =
                listOf(
                    TrackerColumn(
                        id = "amount",
                        chipLabel = "Drops",
                        toggleLabel = "Amount",
                        defaultVisible = true,
                        cellValue = { row, _ -> row.amount },
                        totalValue = { rows, _ -> rows.sumOf { it.amount } },
                        formatCell = { String.format(Locale.ROOT, "%,d", it) },
                        formatChipTotal = { String.format(Locale.ROOT, "%,d", it) },
                        isCaption = false,
                        // Suppress the "Drops: N" chip in the totals line — the header's
                        // "Dragons: M" already tells the player the kill count, and the
                        // per-row counts are visible in the Amount column. The chip was
                        // redundant. Column visibility itself is untouched.
                        chipShouldRender = { false },
                    ),
                    TrackerColumn(
                        id = "value",
                        chipLabel = "Profit",
                        toggleLabel = "Coins",
                        defaultVisible = true,
                        cellValue = { row, _ -> row.value },
                        // Chip's total = sum of row gross values − eye cost. Eye cost only
                        // applies when the active source view includes SUMMONED kills (All
                        // or Summoned). The Lootshare-only view returns 0 eye cost since
                        // the player didn't place eyes for those kills.
                        totalValue = { rows, tab ->
                            val buckets = currentBucketFilter()
                            val eyeCost =
                                if (currentSourceFilter() == KillSource.LOOTSHARE) {
                                    0L
                                } else {
                                    eyeCostFor(tab, buckets)
                                }
                            rows.sumOf { it.value } - eyeCost
                        },
                        formatCell = ::formatCoinsShort,
                        formatChipTotal = ::formatCoinsShort,
                        cellWidth = 56f,
                        isCaption = false,
                    ),
                ),
            sorts =
                listOf(
                    TrackerSort(
                        id = "value",
                        label = "Coins",
                        comparator = compareByDescending { it.value },
                        requiresColumnId = "value",
                    ),
                    TrackerSort(
                        id = "amount",
                        label = "Amount",
                        comparator = compareByDescending { it.amount },
                        requiresColumnId = "amount",
                    ),
                    TrackerSort(
                        id = "rarity",
                        label = "Rarity",
                        comparator = compareByDescending<Row> { it.drop.rarity.ordinal }.thenBy { it.drop.displayName },
                    ),
                    TrackerSort(
                        id = "alpha",
                        label = "Alphabetical",
                        comparator = compareBy { it.drop.displayName },
                    ),
                ),
            rowsProvider = ::buildRows,
            rowKey = { it.drop.name },
            rowLabel = { it.drop.displayName },
            rowLabelColor = { it.drop.rarity.color },
            filterVariants = { DragonType.entries.map { it.displayName } },
            // No per-row filter — buildRows already narrows by filter. Returning true keeps
            // every aggregated row visible regardless of what the framework's filter map
            // would do.
            filterPredicate = { _, _ -> true },
            onResetSession = { DragonProfitTracker.resetSession() },
            isVisible = ::isHudVisible,
            headerExtra = { EyesPlacedLine() },
            footerExtra = { SourcePicker() },
        )
    }

    /**
     * Single-select dropdown picking the kill-source partition for the current view:
     *  - **All** (default): drops from both summoned + lootshare kills, eye cost subtracted.
     *  - **Summoned**: only kills the player contributed at least one eye to; eye cost
     *    subtracted normally.
     *  - **Lootshare**: only kills the player joined without placing eyes; no eye cost
     *    subtracted (you didn't pay for them).
     *
     * Selection persists in [DragonProfitHudSettings.sourceFilter] and feeds straight into
     * [DragonProfitTracker.countsFor] / [DragonProfitTracker.profitFor] via [currentSourceFilter].
     */
    @SoulComposable
    private fun SourcePicker() {
        val current = DragonProfitHudSettings.sourceFilter
        val options = SOURCE_OPTIONS
        val selectedIndex = options.indexOfFirst { it.value == current }.coerceAtLeast(0)
        Dropdown(
            triggerLabel = "Source: ${options[selectedIndex].label}",
            options = options.map { it.label },
            selectedIndex = selectedIndex,
            onSelect = { idx ->
                DragonProfitHudSettings.sourceFilter = options[idx].value
                DragonProfitHudSettings.scrollOffset = 0f
            },
            expanded = DragonProfitHudSettings.sourceDropdownOpen,
            onExpandedChange = { DragonProfitHudSettings.sourceDropdownOpen = it },
            modifier = SoulModifier.Empty.fillMaxWidth(),
            key = "$HUD_ID.source",
        )
    }

    private data class SourceOption(val label: String, val value: KillSource?)

    private val SOURCE_OPTIONS =
        listOf(
            SourceOption("All", null),
            SourceOption("Summoned", KillSource.SUMMONED),
            SourceOption("Lootshare", KillSource.LOOTSHARE),
        )

    private fun currentSourceFilter(): KillSource? = DragonProfitHudSettings.sourceFilter

    /**
     * Renders `Eyes placed: N · Dragons: M` under the title.
     *  - **Eyes placed** comes from [com.soulreturns.features.profit.dragon.EyePlacementTracker]
     *    via [DragonProfitTracker.eyesPlacedFor]. Always SUMMONED (eyes are by definition
     *    summoning), so the source filter doesn't affect this number.
     *  - **Dragons** is the sum of `killsFor` across the current bucket filter (empty
     *    filter = all 7 dragons) AND the current source filter — so the Lootshare-only
     *    view shows just lootshare kills.
     *
     * Both respect the active Session/Total tab.
     */
    @SoulComposable
    private fun EyesPlacedLine() {
        val tab = DragonProfitHudSettings.tab
        val buckets = currentBucketFilter().ifEmpty { DragonType.entries.toSet() }
        val eyes = buckets.sumOf { DragonProfitTracker.eyesPlacedFor(it, tab) }
        val sourceFilter = currentSourceFilter()
        val dragons = buckets.sumOf { DragonProfitTracker.killsFor(it, tab, sourceFilter) }
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = VerticalAlignment.Center,
            gap = 6f,
        ) {
            Text(
                text = "Eyes placed:",
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.textDim,
                font = SoulTheme.typography.body.font,
            )
            Text(
                text = String.format(Locale.ROOT, "%,d", eyes),
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.mono.font,
            )
            Text(
                text = "·",
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.textFaint,
                font = SoulTheme.typography.body.font,
            )
            Text(
                text = "Dragons:",
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.textDim,
                font = SoulTheme.typography.body.font,
            )
            Text(
                text = String.format(Locale.ROOT, "%,d", dragons),
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.mono.font,
            )
        }
    }

    fun register() {
        DragonProfitHudSettings.init()
        SoulHud.register(
            id = HUD_ID,
            width = spec.width,
            height = spec.height,
            defaultAnchorX = spec.defaultAnchorX,
            defaultAnchorY = spec.defaultAnchorY,
            defaultHorizontalAnchor = spec.defaultHorizontalAnchor,
            defaultVerticalAnchor = spec.defaultVerticalAnchor,
            settingsCategory = spec.settingsCategory,
            settingsSubcategory = spec.settingsSubcategory,
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        TrackerHud(spec, DragonProfitHudSettings)
    }

    private fun isHudVisible(): Boolean {
        return cfg.combat.dragons.showProfitHud() &&
            cfg.dev.trackers.profitTrackers() &&
            SkyblockApi.isOnSkyblock &&
            LocationApi.isInArea("The End")
    }

    private fun buildRows(tab: TrackerTab): List<Row> {
        val buckets = currentBucketFilter()
        val raw = DragonProfitTracker.countsFor(tab, sourceFilter = currentSourceFilter())
        val aggregated = HashMap<DragonDrop, Long>()
        for ((bucket, drops) in raw) {
            if (buckets.isNotEmpty() && bucket !in buckets) continue
            for ((drop, counts) in drops) {
                if (counts.amount <= 0L) continue
                aggregated.merge(drop, counts.amount, Long::plus)
            }
        }
        return aggregated.map { (drop, amount) ->
            val unitPrice = PriceCache.price(drop.itemId, PriceSource.BAZAAR_INSTANT_BUY)
            Row(drop = drop, amount = amount, value = unitPrice * amount)
        }
    }

    /** Translate the displayName-keyed filter set in settings into [DragonType] enum values. */
    private fun currentBucketFilter(): Set<DragonType> {
        val names = DragonProfitHudSettings.filter
        if (names.isEmpty()) return emptySet()
        return names.mapNotNull { display -> DragonType.entries.firstOrNull { it.displayName == display } }.toSet()
    }

    private fun eyeCostFor(
        tab: TrackerTab,
        bucketFilter: Set<DragonType>,
    ): Long {
        val buckets = if (bucketFilter.isEmpty()) DragonType.entries else bucketFilter.toList()
        var sum = 0L
        for (b in buckets) {
            val eyes = DragonProfitTracker.eyesPlacedFor(b, tab)
            if (eyes <= 0L) continue
            sum += eyes * PriceCache.price("SUMMONING_EYE", PriceSource.BAZAAR_INSTANT_BUY)
        }
        return sum
    }

    /**
     * Short coin formatter: `"1.2k"`, `"15.4M"`, `"-3.7M"`. Negative values get a leading
     * `"-"` (the Profit chip can go negative when eye cost > drop value, common early in
     * a session). Locale-safe (`Locale.ROOT`).
     */
    private fun formatCoinsShort(v: Long): String {
        val abs = kotlin.math.abs(v)
        val sign = if (v < 0) "-" else ""
        return when {
            abs < 1_000L -> String.format(Locale.ROOT, "%s%d", sign, abs)
            abs < 1_000_000L -> String.format(Locale.ROOT, "%s%.1fk", sign, abs / 1_000.0)
            abs < 1_000_000_000L -> String.format(Locale.ROOT, "%s%.1fM", sign, abs / 1_000_000.0)
            else -> String.format(Locale.ROOT, "%s%.1fB", sign, abs / 1_000_000_000.0)
        }
    }
}
