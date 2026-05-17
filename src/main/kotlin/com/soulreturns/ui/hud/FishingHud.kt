package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.fishing.SeaCreatureCatalog
import com.soulreturns.data.fishing.SeaCreatureCatalog.toDisplayName
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.features.fishing.FishingHudSettings
import com.soulreturns.features.fishing.FishingTimer
import com.soulreturns.features.fishing.FishingTracker
import com.soulreturns.features.fishing.FishingVisibility
import com.soulreturns.stats.PersistentStats
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.fillMaxWidth
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
 * Fishing HUD — per-creature sea-creature tracker. Built on the generic [TrackerHud]
 * framework introduced alongside the dragon profit tracker; this file declares only the
 * Fishing-specific [TrackerSpec] and the festival-countdown header extra. All layout
 * (header, scrollable list, chips, footer, dropdowns, reset) comes from [TrackerHud].
 */
object FishingHud {
    private const val HUD_ID = "fishing_tracker"
    private const val COLOR_FESTIVAL_GOLD = 0xFFFFAA00.toInt() // §6 — matches Hypixel's festival msg colour

    /** One row in the scrollable list. */
    data class CreatureRow(
        val name: String,
        val nameColor: Int,
        /**
         * Sort key for rarity. Higher = rarer (matches [SkyblockRarity] ordinal where
         * COMMON=0 and ULTIMATE=9). Creatures missing from the catalog get -1 so they sink
         * to the bottom of a rarity sort.
         */
        val rarityOrdinal: Int,
        val variant: String?,
        val catches: Long,
        val doubleHooks: Long,
        val cocoons: Long,
    )

    private val spec: TrackerSpec<CreatureRow> by lazy {
        TrackerSpec(
            id = HUD_ID,
            title = "Fishing",
            width = 280,
            height = 380,
            defaultAnchorX = 0.01,
            defaultAnchorY = 0.02,
            settingsCategory = "fishing",
            settingsSubcategory = "fishingHud",
            columns =
                listOf(
                    TrackerColumn(
                        id = FishingHudSettings.COLUMN_COCOONS,
                        chipLabel = "CC",
                        toggleLabel = "Cocoons",
                        defaultVisible = true,
                        cellValue = { row, _ -> row.cocoons },
                        // Chip total = grand total cocoons (session vs total). Includes
                        // creatures that wouldn't match the active filter — denominator stays
                        // the global catches so chip percentages remain self-consistent
                        // with FishingHud v1.
                        totalValue = { _, tab -> totalCocoons(tab) },
                        formatCell = { String.format(Locale.ROOT, "%,d CC", it) },
                        isCaption = true,
                        chipPercentageOfColumn = FishingHudSettings.COLUMN_CATCHES,
                    ),
                    TrackerColumn(
                        id = FishingHudSettings.COLUMN_DOUBLE_HOOKS,
                        chipLabel = "DH",
                        toggleLabel = "Double Hooks",
                        defaultVisible = true,
                        cellValue = { row, _ -> row.doubleHooks },
                        totalValue = { _, tab -> totalDoubleHooks(tab) },
                        formatCell = { String.format(Locale.ROOT, "%,d DH", it) },
                        isCaption = true,
                        chipPercentageOfColumn = FishingHudSettings.COLUMN_CATCHES,
                    ),
                    TrackerColumn(
                        id = FishingHudSettings.COLUMN_CATCHES,
                        chipLabel = "Catches",
                        toggleLabel = "Catches",
                        defaultVisible = true,
                        cellValue = { row, _ ->
                            // Per-row catches with the "add DH / Cocoon to catches" toggles
                            // applied (matches the v1 FishingHud display behaviour). Toggles
                            // live in cfg.fishing.fishingHud so they react live at point of
                            // use, no caching.
                            val hudCfg = cfg.fishing.fishingHud
                            row.catches +
                                (if (hudCfg.addDoubleHookToCatches()) row.doubleHooks else 0L) +
                                (if (hudCfg.addCocoonToCatches()) row.cocoons else 0L)
                        },
                        totalValue = { _, tab -> totalCatchesRolledUp(tab) },
                        formatCell = { String.format(Locale.ROOT, "%,d", it) },
                    ),
                ),
            sorts =
                listOf(
                    TrackerSort(
                        id = FishingHudSettings.SORT_CATCHES,
                        label = "Catches",
                        comparator = compareByDescending { it.catches },
                        requiresColumnId = FishingHudSettings.COLUMN_CATCHES,
                    ),
                    TrackerSort(
                        id = FishingHudSettings.SORT_DOUBLE_HOOKS,
                        label = "Double Hooks",
                        comparator = compareByDescending { it.doubleHooks },
                        requiresColumnId = FishingHudSettings.COLUMN_DOUBLE_HOOKS,
                    ),
                    TrackerSort(
                        id = FishingHudSettings.SORT_COCOONS,
                        label = "Cocoons",
                        comparator = compareByDescending { it.cocoons },
                        requiresColumnId = FishingHudSettings.COLUMN_COCOONS,
                    ),
                    TrackerSort(
                        id = FishingHudSettings.SORT_RARITY,
                        label = "Rarity",
                        comparator = compareByDescending { it.rarityOrdinal },
                    ),
                    TrackerSort(
                        id = FishingHudSettings.SORT_ALPHABETICAL,
                        label = "Alphabetical",
                        comparator = compareBy { it.name },
                    ),
                ),
            rowsProvider = ::buildRows,
            rowKey = { it.name },
            rowLabel = { it.name },
            rowLabelColor = { it.nameColor },
            filterVariants = { SeaCreatureCatalog.variants().map { it.toDisplayName() } },
            // Dropdown shows friendly variant display names ("Lava Crimson Isle"); rows
            // carry the raw variant key. Translate at the filter step.
            filterPredicate = { row, sel ->
                if (sel.isEmpty()) {
                    true
                } else {
                    val display = row.variant?.toDisplayName()
                    display != null && display in sel
                }
            },
            onResetSession = { FishingTracker.resetSession() },
            isVisible = {
                cfg.fishing.fishingHud.showHud() &&
                    cfg.dev.trackers.fishingTracker() &&
                    SkyblockApi.isOnSkyblock &&
                    FishingVisibility.isVisible
            },
            timerLine = {
                TimerInfo(text = FishingTimer.formatTime(), paused = FishingTimer.isPaused)
            },
            headerExtra = { FestivalCountdown() },
        )
    }

    fun register() {
        FishingHudSettings.init()
        SoulHud.register(
            id = HUD_ID,
            width = spec.width,
            height = spec.height,
            defaultAnchorX = spec.defaultAnchorX,
            defaultAnchorY = spec.defaultAnchorY,
            settingsCategory = spec.settingsCategory,
            settingsSubcategory = spec.settingsSubcategory,
        ) {
            TrackerHud(spec, FishingHudSettings)
        }
    }

    // ───────────────────── header extra ─────────────────────

    @SoulComposable
    private fun FestivalCountdown() {
        if (!cfg.fishing.fishingHud.showFestivalTimer() || !FishingFestivalState.active) return
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = VerticalAlignment.Center,
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

    private fun formatFestivalDuration(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.ROOT, "%dm %02ds", m, s)
    }

    // ───────────────────── row assembly ─────────────────────

    private fun buildRows(tab: TrackerTab): List<CreatureRow> {
        val catches: Map<String, Long>
        val doubleHooks: Map<String, Long>
        val cocoons: Map<String, Long>
        when (tab) {
            TrackerTab.Session -> {
                catches = FishingTracker.sessionCatchesByCreature
                doubleHooks = FishingTracker.sessionDoubleHooksByCreature
                cocoons = FishingTracker.sessionCocoonsByCreature
            }
            TrackerTab.Total -> {
                val stats = PersistentStats.current
                catches = stats.catchesByCreature
                doubleHooks = stats.doubleHooksByCreature
                cocoons = stats.cocoonsByCreature
            }
        }
        val names = (catches.keys + doubleHooks.keys + cocoons.keys).toSet()
        return names.map { name ->
            val creature = SeaCreatureCatalog.byName(name)
            CreatureRow(
                name = creature?.name ?: name,
                nameColor = creature?.rarityColor() ?: SoulTheme.colors.text,
                rarityOrdinal = SkyblockRarity.forName(creature?.rarity)?.ordinal ?: -1,
                variant = creature?.variant,
                catches = catches[name] ?: 0L,
                doubleHooks = doubleHooks[name] ?: 0L,
                cocoons = cocoons[name] ?: 0L,
            )
        }
    }

    // ───────────────────── totals for chips ─────────────────────

    private fun totalCatchesRolledUp(tab: TrackerTab): Long {
        val base =
            when (tab) {
                TrackerTab.Session -> FishingTracker.sessionCatches
                TrackerTab.Total -> PersistentStats.current.catchesAllTime
            }
        val dh = totalDoubleHooks(tab)
        val cc = totalCocoons(tab)
        val hudCfg = cfg.fishing.fishingHud
        return base +
            (if (hudCfg.addDoubleHookToCatches()) dh else 0L) +
            (if (hudCfg.addCocoonToCatches()) cc else 0L)
    }

    private fun totalDoubleHooks(tab: TrackerTab): Long =
        when (tab) {
            TrackerTab.Session -> FishingTracker.sessionDoubleHooks
            TrackerTab.Total -> PersistentStats.current.doubleHooksAllTime
        }

    private fun totalCocoons(tab: TrackerTab): Long =
        when (tab) {
            TrackerTab.Session -> FishingTracker.sessionCocoons
            TrackerTab.Total -> PersistentStats.current.cocoonsAllTime
        }
}
