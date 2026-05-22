package com.soulreturns.ui.hud

import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.config.cfg
import com.soulreturns.data.drops.DropCatalogClient
import com.soulreturns.data.drops.DropResolver
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.features.diana.MythologicalActivityTimer
import com.soulreturns.features.diana.MythologicalProfitHudSettings
import com.soulreturns.features.diana.MythologicalProfitTracker
import com.soulreturns.gui.lib.HudHorizontalAnchor
import com.soulreturns.gui.lib.HudVerticalAnchor
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.weight
import com.soulreturns.ui.foundation.Button
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.hud.tracker.TimerInfo
import com.soulreturns.ui.hud.tracker.TrackerColumn
import com.soulreturns.ui.hud.tracker.TrackerHud
import com.soulreturns.ui.hud.tracker.TrackerSort
import com.soulreturns.ui.hud.tracker.TrackerSpec
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme
import java.util.Locale

/**
 * Mythological profit HUD — drops + coin value tracker for Mayor Diana's Mythological
 * Ritual event. Built on the [TrackerHud] framework, same skeleton as the Dragon HUD but
 * with a String-keyed bucket axis (backend `mythological.<mob>` / `mythological.treasure_burrow`
 * source ids) and a header line for the burrows count.
 *
 * **Drop detection.** Wired via [com.soulreturns.features.diana.MythologicalProfitChatListener]:
 *  - Burrow opens → bumps [MythologicalProfitTracker.grantBurrow]
 *  - Mob dig-outs → bumps the matching `mythological.<mob>` kill counter
 *  - `RARE DROP!` lines → grants the named item to `mythological.treasure_burrow`
 *
 * Per-mob drop attribution beyond the rare-drop case isn't wired yet — Hypixel's per-mob
 * drop chat lines aren't pinned down. Admins add the drop rows via `/admin/drops`; this
 * HUD will render them once an admin populates them and the live attribution code lands.
 */
object MythologicalProfitHud {
    private const val HUD_ID = "mythological_profit_tracker"

    /** One row per unique `(itemId, lootshare)` pair, aggregated across filtered buckets. */
    data class Row(
        val itemId: String,
        val displayName: String,
        val rarity: SkyblockRarity,
        val amount: Long,
        val value: Long,
        /** True when this row represents drops credited to the lootshare bucket. The
         *  display name carries a `" [LS]"` suffix to keep it visually distinct from the
         *  same item's own-kill row when both exist. */
        val lootshare: Boolean = false,
    )

    private val spec: TrackerSpec<Row> by lazy {
        TrackerSpec(
            id = HUD_ID,
            title = "Mythological Profit",
            width = 280,
            height = 400,
            defaultAnchorX = 0.99,
            defaultAnchorY = 0.30,
            defaultHorizontalAnchor = HudHorizontalAnchor.End,
            defaultVerticalAnchor = HudVerticalAnchor.Top,
            settingsCategory = "combat",
            settingsSubcategory = "diana",
            tabs = listOf(TrackerTab.Session, TrackerTab.Event, TrackerTab.Total),
            columns =
                listOf(
                    TrackerColumn(
                        id = MythologicalProfitHudSettings.COLUMN_AMOUNT,
                        chipLabel = "Drops",
                        toggleLabel = "Amount",
                        defaultVisible = true,
                        cellValue = { row, _ -> row.amount },
                        totalValue = { rows, _ -> rows.sumOf { it.amount } },
                        formatCell = { String.format(Locale.ROOT, "%,d", it) },
                        // chip is overridden below
                        chipShouldRender = { false },
                    ),
                    TrackerColumn(
                        id = MythologicalProfitHudSettings.COLUMN_VALUE,
                        chipLabel = "Profit",
                        toggleLabel = "Coins",
                        defaultVisible = true,
                        cellValue = { row, _ -> row.value },
                        totalValue = { rows, _ -> rows.sumOf { it.value } },
                        formatCell = ::formatCoinsShort,
                        formatChipTotal = ::formatCoinsShort,
                        cellWidth = 56f,
                    ),
                ),
            sorts =
                listOf(
                    TrackerSort(
                        id = MythologicalProfitHudSettings.SORT_VALUE,
                        label = "Coins",
                        comparator = compareByDescending { it.value },
                        requiresColumnId = MythologicalProfitHudSettings.COLUMN_VALUE,
                    ),
                    TrackerSort(
                        id = MythologicalProfitHudSettings.SORT_AMOUNT,
                        label = "Amount",
                        comparator = compareByDescending { it.amount },
                        requiresColumnId = MythologicalProfitHudSettings.COLUMN_AMOUNT,
                    ),
                    TrackerSort(
                        id = MythologicalProfitHudSettings.SORT_RARITY,
                        label = "Rarity",
                        comparator = compareByDescending<Row> { it.rarity.ordinal }.thenBy { it.displayName },
                    ),
                    TrackerSort(
                        id = MythologicalProfitHudSettings.SORT_ALPHABETICAL,
                        label = "Alphabetical",
                        comparator = compareBy { it.displayName },
                    ),
                ),
            rowsProvider = ::buildRows,
            rowKey = { if (it.lootshare) "${it.itemId}:LS" else it.itemId },
            rowLabel = { it.displayName },
            rowLabelColor = { it.rarity.color },
            filterVariants = ::bucketDisplayNames,
            filterPredicate = { _, _ -> true },
            onResetSession = { MythologicalProfitTracker.resetSession() },
            scrollableList = { cfg.combat.diana.scrollableList() },
            isVisible = ::isHudVisible,
            timerLine = {
                // Timer text mirrors the active tab — same pattern as the mob HUD. Session
                // shows the live session clock; Event / Total render their persistent
                // cumulative active-time clocks. (Paused) follows the underlying timer state
                // on every tab so the user can see whether things are accumulating.
                val tab = MythologicalProfitHudSettings.tab
                val ms =
                    if (tab == TrackerTab.Session) {
                        MythologicalActivityTimer.totalMs
                    } else if (tab == TrackerTab.Event) {
                        com.soulreturns.features.diana.MythologicalMobTracker.eventActiveMs()
                    } else {
                        com.soulreturns.features.diana.MythologicalMobTracker.totalActiveMs()
                    }
                TimerInfo(
                    text = MythologicalActivityTimer.formatMs(ms),
                    paused = MythologicalActivityTimer.isPaused,
                )
            },
            headerExtra = { BurrowsCountLine() },
            footerExtra = { PriceModeRow() },
            chipOverride = { ProfitChipLine() },
        )
    }

    fun register() {
        MythologicalProfitHudSettings.init()
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
            TrackerHud(spec, MythologicalProfitHudSettings)
        }
    }

    // ───────────────────── footer extra ─────────────────────

    /**
     * Single-button row that cycles the loot pricing mode: `Sell Offer → Instant Sell →
     * NPC → Sell Offer`. Mirrors the Dragon HUD's Loot button minus the eye toggle (no
     * eyes in Mythological).
     */
    @SoulComposable
    private fun PriceModeRow() {
        val lootLabel =
            when {
                cfg.combat.diana.lootPriceUseNpc() -> "NPC"
                cfg.combat.diana.lootPriceSellOffer() -> "Sell Offer"
                else -> "Instant Sell"
            }
        Row(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f) {
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

    private fun cycleLootPriceMode() {
        val diana = cfg.combat.diana
        if (diana.lootPriceUseNpc()) {
            diana.lootPriceUseNpc(false)
            diana.lootPriceSellOffer(true)
        } else if (diana.lootPriceSellOffer()) {
            diana.lootPriceSellOffer(false)
        } else {
            diana.lootPriceUseNpc(true)
        }
    }

    // ───────────────────── header extra ─────────────────────

    @SoulComposable
    private fun BurrowsCountLine() {
        val tab = MythologicalProfitHudSettings.tab
        val burrows = MythologicalProfitTracker.totalBurrowsValue(tab)
        // Match the active-time clock to the tab. Session = live session timer; Event =
        // per-mayor-term active-time accumulator (`MythologicalMobTracker.eventActiveMs`);
        // Total = persisted cumulative active ms. perHour() returns 0 until ≥60 s of
        // active time accumulate, which hides the suffix in that case.
        val activeMs =
            if (tab == TrackerTab.Session) {
                com.soulreturns.features.diana.MythologicalActivityTimer.totalMs
            } else if (tab == TrackerTab.Event) {
                com.soulreturns.features.diana.MythologicalMobTracker.eventActiveMs()
            } else {
                com.soulreturns.features.diana.MythologicalMobTracker.totalActiveMs()
            }
        val perHour = com.soulreturns.features.diana.MythologicalActivityTimer.perHour(burrows, activeMs)
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = VerticalAlignment.Center,
            gap = 6f,
        ) {
            Text(
                text = "Burrows:",
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.textDim,
                font = SoulTheme.typography.body.font,
            )
            Text(
                text = String.format(Locale.ROOT, "%,d", burrows),
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.mono.font,
            )
            if (perHour > 0L) {
                Text(
                    text = "[${String.format(Locale.ROOT, "%,d", perHour)}/hr]",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.mono.font,
                )
            }
        }
    }

    // ───────────────────── chip override ─────────────────────

    /**
     * `Coins: <short>` left, `Profit: <short>` right via SpaceBetween. Coins = total coins
     * dug from treasure burrows (chat-fed via the `"You dug out N coins!"` parser);
     * Profit = gross across the filtered buckets at the current bazaar pricing source
     * (no eye-cost subtraction — Mythological has no per-kill consumable).
     */
    @SoulComposable
    private fun ProfitChipLine() {
        val tab = MythologicalProfitHudSettings.tab
        val buckets = currentBucketFilter()
        val totalCoins =
            MythologicalProfitTracker.countsFor(tab, buckets).values
                .sumOf { drops -> drops["COINS"]?.amount ?: 0L }
        val profit = MythologicalProfitTracker.profitFor(tab, buckets, lootPriceSource(), npcFloorEnabled())
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = VerticalAlignment.Center,
            gap = 6f,
        ) {
            Text(
                text = "Coins: ${formatCoinsShort(totalCoins)}",
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.accent,
                font = SoulTheme.typography.mono.font,
            )
            Text(
                text = "Profit: ${formatCoinsShort(profit)}",
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.accent,
                font = SoulTheme.typography.mono.font,
            )
        }
    }

    // ───────────────────── filter helpers ─────────────────────

    /** Friendly bucket names for the filter dropdown — pulled from `DropSource.displayName`. */
    private fun bucketDisplayNames(): List<String> =
        MythologicalProfitTracker.knownBucketIds()
            .mapNotNull { DropCatalogClient.sourceById(it)?.displayName ?: bucketIdFallbackName(it) }
            .distinct()

    /** Display fallback for bucket ids whose source isn't loaded yet (offline). */
    private fun bucketIdFallbackName(id: String): String =
        id.removePrefix("mythological.")
            .replace('_', ' ')
            .split(' ')
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

    private fun currentBucketFilter(): Set<String> {
        val displayNames = MythologicalProfitHudSettings.filter
        if (displayNames.isEmpty()) return emptySet()
        return MythologicalProfitTracker.knownBucketIds()
            .filter { id ->
                val display = DropCatalogClient.sourceById(id)?.displayName ?: bucketIdFallbackName(id)
                display in displayNames
            }
            .toSet()
    }

    // ───────────────────── row assembly ─────────────────────

    private fun buildRows(tab: TrackerTab): List<Row> {
        val buckets = currentBucketFilter()
        val raw = MythologicalProfitTracker.countsFor(tab, buckets)
        // Partition lootshare from everything else — lootshare gets its own row line per
        // item with a "[LS]" display suffix, separate from the player's own-kill drops.
        val ownAggregated = HashMap<String, Long>()
        val ownSourceId = HashMap<String, String>()
        val lootshareAggregated = HashMap<String, Long>()
        for ((bucket, drops) in raw) {
            for ((itemId, counts) in drops) {
                if (counts.amount <= 0L) continue
                // Coins are surfaced via the "Coins:" chip in the bottom-left of the panel;
                // duplicating them as a row in the scrollable list is redundant. The Profit
                // chip + the tracker's profitFor() still factor them in.
                if (itemId == "COINS") continue
                if (bucket == MythologicalProfitTracker.LOOTSHARE_BUCKET) {
                    lootshareAggregated.merge(itemId, counts.amount, Long::plus)
                } else {
                    ownAggregated.merge(itemId, counts.amount, Long::plus)
                    ownSourceId.putIfAbsent(itemId, bucket)
                }
            }
        }
        val priceSource = lootPriceSource()
        val floor = npcFloorEnabled()
        val rows = ArrayList<Row>(ownAggregated.size + lootshareAggregated.size)
        for ((itemId, amount) in ownAggregated) {
            val sourceId = ownSourceId[itemId] ?: ""
            val unitPrice = PriceCache.priceWithNpcFloor(DropResolver.priceLookupId(itemId), priceSource, floor)
            rows.add(
                Row(
                    itemId = itemId,
                    displayName = DropResolver.displayName(sourceId, itemId),
                    rarity = DropResolver.rarity(sourceId, itemId),
                    amount = amount,
                    value = unitPrice * amount,
                    lootshare = false,
                ),
            )
        }
        for ((itemId, amount) in lootshareAggregated) {
            val unitPrice = PriceCache.priceWithNpcFloor(DropResolver.priceLookupId(itemId), priceSource, floor)
            // Resolve the display name against the lootshare bucket so any per-source
            // override curated under it wins; the [LS] suffix sits at the very end of
            // the label.
            val baseName = DropResolver.displayName(MythologicalProfitTracker.LOOTSHARE_BUCKET, itemId)
            rows.add(
                Row(
                    itemId = itemId,
                    displayName = "$baseName [LS]",
                    rarity = DropResolver.rarity(MythologicalProfitTracker.LOOTSHARE_BUCKET, itemId),
                    amount = amount,
                    value = unitPrice * amount,
                    lootshare = true,
                ),
            )
        }
        return rows
    }

    // ───────────────────── visibility + pricing ─────────────────────

    private fun isHudVisible(): Boolean =
        cfg.combat.diana.showProfitHud() &&
            cfg.dev.trackers.mythologicalTracker() &&
            SkyblockApi.isOnSkyblock &&
            LocationApi.isInArea("Hub")

    /**
     * Pricing source for Mythological loot drops — three states cycled by the HUD's
     * "Loot:" button. Mirrors `DragonProfitHud.lootPriceSource()` but reads the
     * Mythological-specific config keys.
     */
    private fun lootPriceSource(): com.soulreturns.data.prices.PriceSource =
        if (cfg.combat.diana.lootPriceUseNpc()) {
            com.soulreturns.data.prices.PriceSource.NPC
        } else if (cfg.combat.diana.lootPriceSellOffer()) {
            com.soulreturns.data.prices.PriceSource.BAZAAR_INSTANT_BUY
        } else {
            com.soulreturns.data.prices.PriceSource.BAZAAR_INSTANT_SELL
        }

    private fun npcFloorEnabled(): Boolean = cfg.dev.trackers.useNpcPriceIfHigher()

    /** Short coin formatter — mirrors `DragonProfitHud.formatCoinsShort`. */
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
