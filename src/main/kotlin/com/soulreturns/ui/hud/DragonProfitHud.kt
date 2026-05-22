package com.soulreturns.ui.hud

import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.config.cfg
import com.soulreturns.data.drops.DropResolver
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceSource
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.features.profit.dragon.DragonLootScanner
import com.soulreturns.features.profit.dragon.DragonProfitHudSettings
import com.soulreturns.features.profit.dragon.DragonProfitTracker
import com.soulreturns.features.profit.dragon.DragonType
import com.soulreturns.features.profit.dragon.KillSource
import com.soulreturns.gui.lib.HudHorizontalAnchor
import com.soulreturns.gui.lib.HudVerticalAnchor
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.HorizontalAlignment
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.height
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.composer.tooltip
import com.soulreturns.ui.composer.weight
import com.soulreturns.ui.foundation.Button
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Dropdown
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.hud.tracker.TRACKER_LIST_HEIGHT
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
        val itemId: String,
        /** Resolved display name from [DropResolver] — per-source override → catalog → itemId. */
        val displayName: String,
        /** Resolved rarity from [DropResolver]. */
        val rarity: SkyblockRarity,
        val amount: Long,
        /** Per-row gross value (price × amount). Eye cost is subtracted only at the chip level. */
        val value: Long,
    )

    private val spec: TrackerSpec<Row> by lazy {
        TrackerSpec(
            id = HUD_ID,
            title = "Dragon Profit",
            width = 280,
            // Tall enough to fit: header (title + Dragons/LS row + Eyes-placed row + tabs
            // row, ~64 px after the eye-line split), divider, scrollable list (140), chips
            // line (~14), divider, footer (5 rows: Source / Eye+Loot price toggles /
            // Filter+ShowAll / Sort+Columns / Reset, each ~27 px button + 6 px gap = ~33).
            // Bumped from 440 → 475 when the price-toggle row was added; one footer row =
            // ~33 px + a small buffer.
            height = 475,
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
                        // formatChipTotal is unused — the spec's [chipOverride] (ProfitChipLine)
                        // renders the chip line directly. Keeping the field non-null for API
                        // shape; ::formatCoinsShort is the natural fallback if chipOverride
                        // is ever removed.
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
                        comparator = compareByDescending<Row> { it.rarity.ordinal }.thenBy { it.displayName },
                    ),
                    TrackerSort(
                        id = "alpha",
                        label = "Alphabetical",
                        comparator = compareBy { it.displayName },
                    ),
                ),
            rowsProvider = ::buildRows,
            rowKey = { it.itemId },
            rowLabel = { it.displayName },
            rowLabelColor = { it.rarity.color },
            filterVariants = { DragonType.entries.map { it.displayName } },
            // No per-row filter — buildRows already narrows by filter. Returning true keeps
            // every aggregated row visible regardless of what the framework's filter map
            // would do.
            filterPredicate = { _, _ -> true },
            onResetSession = { DragonProfitTracker.resetSession() },
            scrollableList = { cfg.combat.dragons.scrollableList() },
            isVisible = ::isHudVisible,
            headerExtra = {
                // Two stacked sub-lines — Dragons/LS counts on the first row, the eye-count
                // summary on the second (`Eyes placed:` moved below the dragon counts per
                // user preference, gives it room to grow with the cost annotation).
                DragonsCountLine()
                EyesPlacedLine()
            },
            footerExtra = {
                SourcePicker()
                PriceModeRow()
            },
            shouldOverrideList = DragonLootScanner::isScanActiveWithoutLoot,
            listOverride = { ScanInstructionBanner() },
            chipOverride = { ProfitChipLine() },
        )
    }

    /**
     * Replaces the scrollable list while a dragon-death scan is open but no loot stand has
     * rendered yet — Hypixel only sends loot armor-stand packets when the player is within
     * ~20 blocks of their pile, so far-away tag-killers see an empty list and would
     * otherwise wonder if the kill registered. Sized to [TRACKER_LIST_HEIGHT] so the panel
     * footprint stays stable across the toggle.
     */
    @SoulComposable
    private fun ScanInstructionBanner() {
        Column(
            modifier =
                SoulModifier.Empty
                    .fillMaxWidth()
                    .height(TRACKER_LIST_HEIGHT)
                    .padding(horizontal = 12f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = HorizontalAlignment.Center,
            gap = 4f,
        ) {
            Text(
                text = "Go near the loot",
                size = SoulTheme.typography.title.size,
                color = SoulTheme.colors.accent,
                font = SoulTheme.typography.title.font,
            )
            Text(
                text = "to track it",
                size = SoulTheme.typography.title.size,
                color = SoulTheme.colors.accent,
                font = SoulTheme.typography.title.font,
            )
            // Smaller dim hint — explains *why* the player needs to get close: Hypixel
            // renders each loot armor-stand only when the local client is within ~20 blocks
            // of it, and the pile is spread in a circle around the death point, so the far
            // side won't show until the player walks deeper in.
            Text(
                text = "All loot nametags must be visible",
                size = SoulTheme.typography.caption.size,
                color = SoulTheme.colors.textDim,
                font = SoulTheme.typography.body.font,
            )
        }
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
     * Bazaar pricing source for items the player **buys** (Summoning Eyes). Driven by
     * `cfg.combat.dragons.eyePriceInstantBuy`: `true` → instant-buy (ASK side, higher),
     * `false` → buy-order (BID side, lower). Read at point of use everywhere — no caching.
     */
    private fun eyePriceSource(): PriceSource =
        if (cfg.combat.dragons.eyePriceInstantBuy()) {
            PriceSource.BAZAAR_INSTANT_BUY
        } else {
            PriceSource.BAZAAR_INSTANT_SELL
        }

    /**
     * Pricing source for items the player **sells** (loot drops). Three states picked by
     * the HUD's "Loot:" cycle button (and the matching config fields):
     *  - `lootPriceUseNpc=true` → NPC sell price (Ironman-focused).
     *  - else `lootPriceSellOffer=true` → BAZAAR_INSTANT_BUY (ASK side, sell-offer fills).
     *  - else → BAZAAR_INSTANT_SELL (BID side, instant-sell).
     */
    private fun lootPriceSource(): PriceSource =
        if (cfg.combat.dragons.lootPriceUseNpc()) {
            PriceSource.NPC
        } else if (cfg.combat.dragons.lootPriceSellOffer()) {
            PriceSource.BAZAAR_INSTANT_BUY
        } else {
            PriceSource.BAZAAR_INSTANT_SELL
        }

    /** Convenience read of the global "NPC floor" toggle — passed to price helpers. */
    private fun npcFloorEnabled(): Boolean = cfg.dev.trackers.useNpcPriceIfHigher()

    /**
     * Footer row of two button-toggles: one picks the eye pricing mode (cost side), one
     * picks the loot pricing mode (revenue side). Each button shows its current state in
     * the label and flips it on click. Backed by `cfg.combat.dragons.eyePriceInstantBuy`
     * / `lootPriceSellOffer` so the choice persists across sessions and feeds the chat
     * announcer too.
     */
    @SoulComposable
    private fun PriceModeRow() {
        val eyeLabel = if (cfg.combat.dragons.eyePriceInstantBuy()) "Instant Buy" else "Buy Order"
        val lootLabel =
            when {
                cfg.combat.dragons.lootPriceUseNpc() -> "NPC"
                cfg.combat.dragons.lootPriceSellOffer() -> "Sell Offer"
                else -> "Instant Sell"
            }
        Row(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f) {
            Button(
                label = "Eye: $eyeLabel",
                onClick = {
                    // Eye stays a 2-way toggle — there's no "Ironman buys eyes from NPC"
                    // path (eyes aren't sold by an NPC), so no NPC option on this button.
                    cfg.combat.dragons.eyePriceInstantBuy(!cfg.combat.dragons.eyePriceInstantBuy())
                    SoulConfigHolder.INSTANCE.save()
                },
                modifier = SoulModifier.Empty.weight(1f),
                centerLabel = true,
                key = "$HUD_ID.eyePriceMode",
            )
            Button(
                label = "Loot: $lootLabel",
                onClick = {
                    cycleLootPriceMode()
                    SoulConfigHolder.INSTANCE.save()
                },
                modifier = SoulModifier.Empty.weight(1f),
                centerLabel = true,
                key = "$HUD_ID.lootPriceMode",
            )
        }
    }

    /**
     * Cycle the loot pricing through three states by toggling the right bool pair:
     * `Sell Offer → Instant Sell → NPC → Sell Offer`. Mirrors what the user sees in the
     * "Loot:" button label.
     */
    private fun cycleLootPriceMode() {
        val dragons = cfg.combat.dragons
        if (dragons.lootPriceUseNpc()) {
            // NPC → Sell Offer
            dragons.lootPriceUseNpc(false)
            dragons.lootPriceSellOffer(true)
        } else if (dragons.lootPriceSellOffer()) {
            // Sell Offer → Instant Sell
            dragons.lootPriceSellOffer(false)
        } else {
            // Instant Sell → NPC
            dragons.lootPriceUseNpc(true)
        }
    }

    /**
     * Top header sub-line: `Dragons: X · LS: Y`. Always shows both partitions regardless of
     * the Source-filter dropdown — gives at-a-glance own-vs-lootshare attribution. Bucket
     * filter + active Session/Total tab are respected.
     */
    @SoulComposable
    private fun DragonsCountLine() {
        val tab = DragonProfitHudSettings.tab
        val buckets = currentBucketFilter().ifEmpty { DragonType.entries.toSet() }
        val summonedDragons = buckets.sumOf { DragonProfitTracker.killsFor(it, tab, KillSource.SUMMONED) }
        val lootshareDragons = buckets.sumOf { DragonProfitTracker.killsFor(it, tab, KillSource.LOOTSHARE) }
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = VerticalAlignment.Center,
            gap = 6f,
        ) {
            HeaderLabel("Dragons:")
            HeaderValue(String.format(Locale.ROOT, "%,d", summonedDragons))
            HeaderSeparator()
            HeaderLabel("LS:")
            HeaderValue(String.format(Locale.ROOT, "%,d", lootshareDragons))
        }
    }

    /**
     * Second header sub-line: `Eyes placed: N (X)`. Parenthesized cost is `count × price(
     * SUMMONING_EYE)` at instant-buy; omitted when count = 0 or the eye price hasn't loaded
     * so the line never reads `"Eyes placed: 0 (0)"`. Always SUMMONED-only by definition
     * (eyes are placed during summoning); source filter doesn't affect it.
     */
    @SoulComposable
    private fun EyesPlacedLine() {
        val tab = DragonProfitHudSettings.tab
        val buckets = currentBucketFilter().ifEmpty { DragonType.entries.toSet() }
        val eyes = buckets.sumOf { DragonProfitTracker.eyesPlacedFor(it, tab) }
        val eyeCost = eyes * PriceCache.price("SUMMONING_EYE", eyePriceSource())
        val eyesText =
            if (eyes > 0L && eyeCost > 0L) {
                String.format(Locale.ROOT, "%,d (%s)", eyes, formatCoinsShort(eyeCost))
            } else {
                String.format(Locale.ROOT, "%,d", eyes)
            }
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = VerticalAlignment.Center,
            gap = 6f,
        ) {
            HeaderLabel("Eyes placed:")
            HeaderValue(eyesText)
        }
    }

    /**
     * Replacement for the framework's auto-generated chip line. Renders `Per dragon: <avg>`
     * flush-left and `Profit: <total>` flush-right via SpaceBetween — the previous inline
     * `Profit: X · Per dragon: Y` single-chip got swapped + split so each value gets the
     * edge of the panel and reads cleaner at a glance. Both numbers respect the Source-
     * filter dropdown and bucket filter via [currentSourceFilter] / [currentBucketFilter].
     *
     * Per-dragon arithmetic is the same as before: `total / killsFor(currentSourceFilter)`,
     * with the suffix omitted entirely when the active partition has zero kills.
     */
    @SoulComposable
    private fun ProfitChipLine() {
        val tab = DragonProfitHudSettings.tab
        val buckets = currentBucketFilter()
        val bucketsExpanded = buckets.ifEmpty { DragonType.entries.toSet() }
        val source = currentSourceFilter()
        val kills = bucketsExpanded.sumOf { DragonProfitTracker.killsFor(it, tab, source) }
        // Reuse the same total math the value column's totalValue exposes: gross sum minus
        // eye cost only when the active source view includes SUMMONED kills.
        val floor = npcFloorEnabled()
        val grossValue =
            bucketsExpanded.sumOf { bucket ->
                DragonProfitTracker.countsFor(tab, source)[bucket]?.entries?.sumOf { (itemId, counts) ->
                    PriceCache.priceWithNpcFloor(
                        DropResolver.priceLookupId(itemId),
                        lootPriceSource(),
                        floor,
                    ) * counts.amount
                } ?: 0L
            }
        val eyeCost = if (source == KillSource.LOOTSHARE) 0L else eyeCostFor(tab, buckets)
        val total = grossValue - eyeCost
        // Hover-tooltip breakdown for the Profit chip — shows the gross / eye-cost components
        // that produced the net figure. Two lines: `Total: <gross>` and `Cost: <eyeCost>`.
        // Empty tooltip when both are zero so we don't pop a meaningless box on a fresh
        // session; the framework's `.tooltip("")` is a no-op so this collapses naturally.
        val profitTooltip =
            if (grossValue > 0L || eyeCost > 0L) {
                "Total: ${formatCoinsShort(grossValue)}\nCost: ${formatCoinsShort(eyeCost)}"
            } else {
                ""
            }
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = VerticalAlignment.Center,
            gap = 6f,
        ) {
            // Left: Per dragon average. Skip entirely (empty Box) when the active partition
            // has 0 kills, so the line still has the Profit cell anchored right via the
            // SpaceBetween arrangement (otherwise SpaceBetween with one child = left-pin).
            if (kills > 0L) {
                ChipText("Per dragon: ${formatCoinsShort(total / kills)}")
            } else {
                Text(
                    text = "",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.accent,
                    font = SoulTheme.typography.mono.font,
                )
            }
            ChipText("Profit: ${formatCoinsShort(total)}", tooltip = profitTooltip)
        }
    }

    @SoulComposable
    private fun ChipText(
        text: String,
        tooltip: String = "",
    ) {
        Text(
            text = text,
            size = SoulTheme.typography.body.size,
            color = SoulTheme.colors.accent,
            font = SoulTheme.typography.mono.font,
            modifier = SoulModifier.Empty.tooltip(tooltip),
        )
    }

    @SoulComposable
    private fun HeaderLabel(text: String) {
        Text(
            text = text,
            size = SoulTheme.typography.body.size,
            color = SoulTheme.colors.textDim,
            font = SoulTheme.typography.body.font,
        )
    }

    @SoulComposable
    private fun HeaderValue(text: String) {
        Text(
            text = text,
            size = SoulTheme.typography.body.size,
            color = SoulTheme.colors.text,
            font = SoulTheme.typography.mono.font,
        )
    }

    @SoulComposable
    private fun HeaderSeparator() {
        Text(
            text = "·",
            size = SoulTheme.typography.body.size,
            color = SoulTheme.colors.textFaint,
            font = SoulTheme.typography.body.font,
        )
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
        // Sublocation, not area: the HUD must stay hidden in other End sublocations
        // (Voidgloom / enderman slayer, etc.) AND M7 Catacombs (which is a different
        // area but also has dragons). Dragon's Nest is the unambiguous "we are fighting
        // the seven End Dragons" signal — same gate every dragon chat listener uses.
        return cfg.combat.dragons.showProfitHud() &&
            cfg.dev.trackers.profitTrackers() &&
            SkyblockApi.isOnSkyblock &&
            LocationApi.isInSublocation("Dragon's Nest")
    }

    private fun buildRows(tab: TrackerTab): List<Row> {
        val buckets = currentBucketFilter()
        val raw = DragonProfitTracker.countsFor(tab, sourceFilter = currentSourceFilter())
        // Aggregate per (sourceId, itemId) — the same itemId can carry different display
        // overrides per source in theory; in practice dragon overrides are identical across
        // sources for shared drops. Track first-seen sourceId for the resolution lookup so
        // we don't have to re-iterate.
        val aggregated = HashMap<String, Long>()
        val firstSeenSourceId = HashMap<String, String>()
        for ((bucket, drops) in raw) {
            if (buckets.isNotEmpty() && bucket !in buckets) continue
            for ((itemId, counts) in drops) {
                if (counts.amount <= 0L) continue
                aggregated.merge(itemId, counts.amount, Long::plus)
                firstSeenSourceId.putIfAbsent(itemId, bucket.sourceId)
            }
        }
        val floor = npcFloorEnabled()
        return aggregated.map { (itemId, amount) ->
            val sourceId = firstSeenSourceId[itemId] ?: ""
            val unitPrice =
                PriceCache.priceWithNpcFloor(DropResolver.priceLookupId(itemId), lootPriceSource(), floor)
            Row(
                itemId = itemId,
                displayName = DropResolver.displayName(sourceId, itemId),
                rarity = DropResolver.rarity(sourceId, itemId),
                amount = amount,
                value = unitPrice * amount,
            )
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
            sum += eyes * PriceCache.price("SUMMONING_EYE", eyePriceSource())
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
