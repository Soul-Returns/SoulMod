package com.soulreturns.features.profit.dragon.gui

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.Theme
import com.soulreturns.data.drops.DropCatalogClient
import com.soulreturns.data.drops.DropResolver
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceMode
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.profit.dragon.DragonLootScanner
import com.soulreturns.features.profit.dragon.DragonProfitAnnouncer
import com.soulreturns.features.profit.dragon.DragonProfitTracker
import com.soulreturns.features.profit.dragon.DragonType
import com.soulreturns.features.profit.dragon.DragonsNest
import com.soulreturns.features.profit.dragon.KillSource
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.tracker.TrackerColumn
import com.soulreturns.ui.hud.tracker.TrackerHud
import com.soulreturns.ui.hud.tracker.TrackerSegments
import com.soulreturns.ui.hud.tracker.TrackerSelect
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerSort
import com.soulreturns.ui.hud.tracker.TrackerSpec
import com.soulreturns.ui.hud.tracker.TrackerTab
import net.minecraft.client.gui.screens.Screen
import java.util.Locale

/**
 * The dragon profit panel — the second tracker built on the shared [TrackerHud] framework, and the
 * one that proves the "profit flavour is a spec, not a subclass" claim the framework was written on.
 *
 * A general tracker declares count columns; this declares an **amount** column and a **value**
 * column, widens the value cell so `12.5M` fits, and adds a footer selector for the kill-source
 * partition. Every pixel of the panel — header, tabs, scrollable list, chips, footer, popups,
 * hit-testing — belongs to the framework, exactly as it did in the legacy mod. This file is
 * declaration.
 *
 * ### Two tabs, not three
 * `Session` and `Total`. [TrackerTab.Event] exists in the framework and stays unused here: it
 * partitions by Hypixel mayor term, dragons have no mayor-term axis, and an Event tab would be a
 * second copy of Total forever.
 *
 * ### Values are computed at render, never stored
 * [DragonProfitTracker] holds counts only. Every coin figure on this panel is `count × price` read
 * fresh, so changing the price source recomputes the whole history immediately instead of leaving old
 * rows valued at whatever the market was doing when they dropped.
 *
 * That is only compatible with the framework's two-pass layout because [PriceCache.price] is a plain
 * synchronous map read. **Nothing in this file may await, and nothing may have a side effect** — the
 * panel lays out twice per frame, once with drawing suppressed to measure, and a value that differs
 * between the two passes makes the panel's width disagree with its contents.
 */
object DragonProfitHud : TrackerHud<DragonProfitHud.DropRow>("dragon_profit_tracker", "Dragon Profit", "combat") {
    const val COLUMN_AMOUNT = "amount"
    const val COLUMN_VALUE = "value"

    const val SORT_VALUE = "value"
    const val SORT_AMOUNT = "amount"
    const val SORT_RARITY = "rarity"
    const val SORT_ALPHABETICAL = "alpha"

    /** The Source picker's options. Index 0 is the no-filter sentinel. */
    private val SOURCE_OPTIONS: List<Pair<String, KillSource?>> = listOf(
        "All" to null,
        KillSource.SUMMONED.displayName to KillSource.SUMMONED,
        KillSource.LOOTSHARE.displayName to KillSource.LOOTSHARE,
    )

    /**
     * One aggregated row: a drop, summed across every dragon currently in view.
     *
     * @property value gross — price × amount. Eye cost is a panel-level number, not a per-row one:
     *   an eye is not any particular drop's cost.
     * @property rarity never null — [DropResolver] falls back to COMMON, which is also what an
     *   unrecognised drop reads as in game.
     */
    data class DropRow(
        val itemId: String,
        val displayName: String,
        val rarity: SkyblockRarity,
        val amount: Long,
        val value: Long,
    )

    override val settings: TrackerSettings get() = DragonProfitHudSettings

    override val spec: TrackerSpec<DropRow> by lazy {
        TrackerSpec(
            id = "dragon_profit_tracker",
            title = "Dragon Profit",
            width = 280,
            columns = listOf(
                TrackerColumn(
                    id = COLUMN_AMOUNT,
                    chipLabel = "Drops",
                    toggleLabel = "Amount",
                    cellValue = { row, _ -> row.amount },
                    totalValue = { rows, _ -> rows.sumOf { it.amount } },
                ),
                TrackerColumn(
                    id = COLUMN_VALUE,
                    chipLabel = "Profit",
                    toggleLabel = "Coins",
                    cellValue = { row, _ -> row.value },
                    totalValue = { rows, tab -> rows.sumOf { it.value } - eyeCostFor(tab) },
                    formatCell = DragonProfitAnnouncer::formatCoins,
                    formatChipTotal = DragonProfitAnnouncer::formatCoins,
                    // 32 px is sized for a catch count. `12.5M` needs most of twice that.
                    cellWidth = 56f,
                ),
            ),
            sorts = listOf(
                TrackerSort(SORT_VALUE, "Coins", compareByDescending { it.value }, COLUMN_VALUE),
                TrackerSort(SORT_AMOUNT, "Amount", compareByDescending { it.amount }, COLUMN_AMOUNT),
                TrackerSort(SORT_RARITY, "Rarity", compareByDescending { it.rarity.ordinal }),
                TrackerSort(SORT_ALPHABETICAL, "Alphabetical", compareBy { it.displayName }),
            ),
            rowsProvider = ::buildRows,
            rowLabel = { it.displayName },
            rowLabelColor = { it.rarity.color },
            filterVariants = { DragonType.entries.map { it.displayName } },
            // buildRows has already narrowed to the selected dragons — a row is an aggregate across
            // them and carries no single dragon to test, so every row it produced is one to show.
            filterPredicate = { _, _ -> true },
            onResetSession = { DragonProfitTracker.resetSession() },
            isVisible = ::isHudVisible,
            headerExtra = ::headerLines,
            footerSelects = listOf(
                TrackerSelect(
                    id = "source",
                    labelPrefix = "Source",
                    options = SOURCE_OPTIONS.map { it.first },
                    selectedIndex = { SOURCE_OPTIONS.indexOfFirst { it.second == currentSourceFilter() }.coerceAtLeast(0) },
                    onSelect = { index -> DragonProfitHudSettings.sourceFilter = SOURCE_OPTIONS[index].second },
                ),
                // Not a config row on purpose: it is a thing you flip while reading the numbers, and
                // it re-values the whole history the moment you do. See DragonProfitHudSettings.
                TrackerSelect(
                    id = "priceMode",
                    labelPrefix = "Price",
                    options = PriceMode.labels,
                    selectedIndex = { priceMode().ordinal },
                    onSelect = { index -> DragonProfitHudSettings.priceMode = PriceMode.byIndex(index) },
                ),
            ),
            // Replaces the auto-generated chips wholesale, so neither column renders one: the header
            // already says how many dragons died, which made the legacy "Drops: N" chip redundant
            // beside "Profit".
            chipOverride = ::chipLine,
            // Two different answers, and the panel must not freeze whichever was true at startup: an
            // empty catalog can identify nothing, which is not the same as having killed nothing.
            emptyText = { if (DropCatalogClient.isEmpty()) "(drop catalog not loaded)" else "(no drops yet)" },
            scrollableList = { cfg.combat.dragons.profitScrollable },
            shouldOverrideList = DragonLootScanner::isScanActiveWithoutLoot,
            listOverride = ::scanBanner,
        )
    }

    // ------------------------------------------------------------------ MovableHud contract

    override fun configuredOn(): Boolean = cfg.combat.dragons.enabled && cfg.combat.dragons.profitHud

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "dragons")

    override fun readTransform(): HudTransform = cfg.combat.dragons.profitHudTransform

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(combat = combat.copy(dragons = combat.dragons.copy(profitHudTransform = transform))) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().combat.dragons.profitHudTransform

    override fun drawBackground(): Boolean = cfg.combat.dragons.profitHudBackground

    override fun backgroundColor(): Int = cfg.combat.dragons.profitHudBackgroundColor.effective()

    override fun menuToggles(): List<HudMenuToggle> = listOf(
        HudMenuToggle("Background", { cfg.combat.dragons.profitHudBackground }) {
            ConfigManager.update {
                copy(combat = combat.copy(dragons = combat.dragons.copy(profitHudBackground = !combat.dragons.profitHudBackground)))
            }
        },
        HudMenuToggle("Scrolling list", { cfg.combat.dragons.profitScrollable }) {
            ConfigManager.update {
                copy(combat = combat.copy(dragons = combat.dragons.copy(profitScrollable = !combat.dragons.profitScrollable)))
            }
        },
    )

    // ------------------------------------------------------------------ content

    /**
     * The panel is scoped to the nest by **sublocation**, not by area.
     *
     * The End has other sublocations (the Voidgloom slayer platform, the Dragon's Nest approach), and
     * Catacombs Master Mode F7 is a different area that also has dragons. `Dragon's Nest` is the one
     * unambiguous "we are fighting the seven End dragons" signal, and it is the same gate every
     * dragon chat listener uses.
     */
    private fun isHudVisible(): Boolean =
        cfg.combat.dragons.enabled &&
            cfg.combat.dragons.profitHud &&
            SkyblockState.onSkyblock &&
            SkyblockLocation.isInSubLocation(DragonsNest.SUBLOCATION)

    /**
     * Two header sub-lines: the kill counts split by how they were earned, and the eye ledger.
     *
     * Both counts are shown whatever the Source picker says, deliberately — the split is the thing
     * worth seeing at a glance, and hiding one because you filtered to the other loses it.
     */
    private fun headerLines(): List<TrackerSegments> {
        val tab = DragonProfitHudSettings.tab
        val buckets = effectiveBuckets()
        val summoned = buckets.sumOf { DragonProfitTracker.killsFor(it, tab, KillSource.SUMMONED) }
        val lootshared = buckets.sumOf { DragonProfitTracker.killsFor(it, tab, KillSource.LOOTSHARE) }
        val eyes = buckets.sumOf { DragonProfitTracker.eyesPlacedFor(it, tab) }
        val eyeCost = eyes * PriceCache.buyCost(DragonProfitTracker.SUMMONING_EYE_ID, priceMode())

        val counts: TrackerSegments = listOf(
            "Dragons: " to Theme.TEXT_DIM,
            String.format(Locale.ROOT, "%,d", summoned) to Theme.TEXT,
            "  LS: " to Theme.TEXT_DIM,
            String.format(Locale.ROOT, "%,d", lootshared) to Theme.TEXT,
        )
        // The cost is appended only when there is one — "Eyes placed: 0 (0)" is noise, and so is a
        // cost of 0 before the first price poll lands.
        val eyeLine: TrackerSegments = if (eyes > 0L && eyeCost > 0L) {
            listOf(
                "Eyes placed: " to Theme.TEXT_DIM,
                String.format(Locale.ROOT, "%,d", eyes) to Theme.TEXT,
                " (${DragonProfitAnnouncer.formatCoins(eyeCost)})" to Theme.TEXT_DIM,
            )
        } else {
            listOf("Eyes placed: " to Theme.TEXT_DIM, String.format(Locale.ROOT, "%,d", eyes) to Theme.TEXT)
        }
        return listOf(counts, eyeLine)
    }

    /**
     * The chip line: net profit, and what that works out to per dragon.
     *
     * The per-dragon average is omitted entirely at zero kills rather than printing a division by
     * nothing — and at that point there is no profit to average anyway.
     */
    private fun chipLine(): TrackerSegments {
        val tab = DragonProfitHudSettings.tab
        val buckets = DragonProfitHudSettings.filter.toDragonTypes()
        val source = currentSourceFilter()
        val net = DragonProfitTracker.profitFor(
            tab = tab,
            bucketFilter = buckets,
            sourceFilter = source,
            mode = priceMode(),
            useNpcFloor = cfg.combat.dragons.useNpcPriceIfHigher,
        )
        val kills = effectiveBuckets().sumOf { DragonProfitTracker.killsFor(it, tab, source) }
        val line = ArrayList<Pair<String, Int>>(4)
        line.add("Profit: " to Theme.TEXT_DIM)
        line.add(DragonProfitAnnouncer.formatCoins(net) to Theme.ACCENT)
        if (kills > 0L) {
            line.add("   Per dragon: " to Theme.TEXT_DIM)
            line.add(DragonProfitAnnouncer.formatCoins(net / kills) to Theme.ACCENT)
        }
        return line
    }

    /**
     * Shown instead of the row list while a scan window is open and nothing has rendered yet.
     *
     * Without it, killing a dragon from range shows an empty list for as long as it takes to walk
     * over, which reads as "the tracker did not see the kill" — and the second line is the part that
     * actually helps, because the reason is not something a player could guess.
     */
    private fun scanBanner(): List<TrackerSegments> = listOf(
        listOf("Go near the loot to track it" to Theme.ACCENT),
        listOf("every nametag has to be visible" to Theme.TEXT_DIM),
    )

    private fun priceMode(): PriceMode = DragonProfitHudSettings.priceMode

    private fun currentSourceFilter(): KillSource? = DragonProfitHudSettings.sourceFilter

    /** The dragons in view, with an empty filter meaning all of them. */
    private fun effectiveBuckets(): Set<DragonType> =
        DragonProfitHudSettings.filter.toDragonTypes().ifEmpty { DragonType.entries.toSet() }

    private fun eyeCostFor(tab: TrackerTab): Long {
        // A Lootshare-only view charges nothing: the player placed no eyes for those kills.
        if (currentSourceFilter() == KillSource.LOOTSHARE) return 0L
        return DragonProfitTracker.eyeCostFor(tab, DragonProfitHudSettings.filter.toDragonTypes(), priceMode())
    }

    /**
     * The filter set holds display names, so a row is matched by translating here rather than by
     * storing a display name on the row — a relabelled dragon would otherwise orphan a saved filter.
     */
    private fun Set<String>.toDragonTypes(): Set<DragonType> =
        if (isEmpty()) emptySet() else mapNotNull { DragonType.byDisplayName(it) }.toSet()

    private fun buildRows(tab: TrackerTab): List<DropRow> {
        val buckets = DragonProfitHudSettings.filter.toDragonTypes()
        val counts = DragonProfitTracker.countsFor(tab, currentSourceFilter())
        val aggregated = HashMap<String, Long>()
        // The same item id can in principle carry a different override per dragon; in practice the
        // dragon overrides are identical for shared drops. Remembering the first source an id was
        // seen under is enough to resolve it and avoids a second pass.
        val firstSourceId = HashMap<String, String>()
        for ((type, drops) in counts) {
            if (buckets.isNotEmpty() && type !in buckets) continue
            for ((itemId, amount) in drops) {
                if (amount <= 0L) continue
                aggregated[itemId] = (aggregated[itemId] ?: 0L) + amount
                firstSourceId.putIfAbsent(itemId, type.sourceId)
            }
        }
        val mode = priceMode()
        val floor = cfg.combat.dragons.useNpcPriceIfHigher
        return aggregated.map { (itemId, amount) ->
            val sourceId = firstSourceId[itemId] ?: ""
            val unit = PriceCache.sellValue(DropResolver.priceLookupId(itemId), mode, floor)
            DropRow(
                itemId = itemId,
                displayName = DropResolver.displayName(sourceId, itemId),
                rarity = DropResolver.rarity(sourceId, itemId),
                amount = amount,
                value = unit * amount,
            )
        }
    }
}
