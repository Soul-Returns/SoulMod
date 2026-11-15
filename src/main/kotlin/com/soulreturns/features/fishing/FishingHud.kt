package com.soulreturns.features.fishing

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.Theme
import com.soulreturns.data.fishing.SeaCreatureCatalog
import com.soulreturns.data.fishing.toDisplayName
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.tracker.TimerInfo
import com.soulreturns.ui.hud.tracker.TrackerColumn
import com.soulreturns.ui.hud.tracker.TrackerHud
import com.soulreturns.ui.hud.tracker.TrackerSegments
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerSort
import com.soulreturns.ui.hud.tracker.TrackerSpec
import com.soulreturns.ui.hud.tracker.TrackerTab
import net.minecraft.client.gui.screens.Screen
import java.util.Locale

/**
 * The fishing tracker — the per-creature panel, rebuilt on the shared [TrackerHud] framework.
 *
 * This file declares only what is fishing-specific: the row type, the three columns, the five
 * sorts, where the numbers come from, the variant filter and the festival countdown. Every pixel of
 * layout — header, tabs, scrollable list, chips, footer, dropdowns, reset — belongs to the
 * framework, exactly as it did in the legacy mod.
 *
 * ### Session vs Total, and where each number lives
 * The Session tab reads [FishingTracker]'s in-memory counters; Total reads the profile-keyed
 * `fishing` namespace in the stats file. There is no Festival tab: the festival bucket is a third
 * *scope*, not a third tab, and it is surfaced through the header countdown and `/soul fishing`
 * instead of widening a strip that is already the panel's busiest row.
 */
object FishingHud : TrackerHud<FishingHud.CreatureRow>("fishing_tracker", "Fishing Tracker", "fishing") {
    const val COLUMN_CATCHES = "catches"
    const val COLUMN_DOUBLE_HOOKS = "doubleHooks"
    const val COLUMN_COCOONS = "cocoons"

    const val SORT_CATCHES = "catches"
    const val SORT_DOUBLE_HOOKS = "doubleHooks"
    const val SORT_COCOONS = "cocoons"
    const val SORT_RARITY = "rarity"
    const val SORT_ALPHABETICAL = "alpha"

    /** §6 — the colour Hypixel's own festival messages use. */
    private const val COLOR_FESTIVAL = 0xFFFFAA00.toInt()

    /**
     * One row of the list.
     *
     * @property rarityOrdinal higher is rarer, matching [SkyblockRarity]'s declaration order.
     *   Creatures the catalog does not know get -1 so they sink to the bottom of a rarity sort
     *   rather than floating to the top of it.
     */
    data class CreatureRow(
        val name: String,
        val nameColor: Int,
        val rarityOrdinal: Int,
        val variant: String?,
        val catches: Long,
        val doubleHooks: Long,
        val cocoons: Long,
    )

    override val settings: TrackerSettings get() = FishingHudSettings

    override val spec: TrackerSpec<CreatureRow> by lazy {
        TrackerSpec(
            id = "fishing_tracker",
            title = "Fishing",
            width = 280,
            columns = listOf(
                TrackerColumn(
                    id = COLUMN_COCOONS,
                    chipLabel = "CC",
                    toggleLabel = "Cocoons",
                    cellValue = { row, _ -> row.cocoons },
                    totalValue = { _, tab -> scope(tab).cocoons },
                    formatCell = { String.format(Locale.ROOT, "%,d CC", it) },
                    isCaption = true,
                    chipPercentageOfColumn = COLUMN_CATCHES,
                ),
                TrackerColumn(
                    id = COLUMN_DOUBLE_HOOKS,
                    chipLabel = "DH",
                    toggleLabel = "Double Hooks",
                    cellValue = { row, _ -> row.doubleHooks },
                    totalValue = { _, tab -> scope(tab).doubleHooks },
                    formatCell = { String.format(Locale.ROOT, "%,d DH", it) },
                    isCaption = true,
                    chipPercentageOfColumn = COLUMN_CATCHES,
                ),
                TrackerColumn(
                    id = COLUMN_CATCHES,
                    chipLabel = "Catches",
                    toggleLabel = "Catches",
                    cellValue = { row, _ -> row.catches },
                    // The chip is the scope's own total, not the sum of the visible rows: a filter
                    // narrows what you are looking at, it does not change how much you caught.
                    totalValue = { _, tab -> scope(tab).catches },
                ),
            ),
            sorts = listOf(
                TrackerSort(SORT_CATCHES, "Catches", compareByDescending { it.catches }, COLUMN_CATCHES),
                TrackerSort(SORT_DOUBLE_HOOKS, "Double Hooks", compareByDescending { it.doubleHooks }, COLUMN_DOUBLE_HOOKS),
                TrackerSort(SORT_COCOONS, "Cocoons", compareByDescending { it.cocoons }, COLUMN_COCOONS),
                TrackerSort(SORT_RARITY, "Rarity", compareByDescending { it.rarityOrdinal }),
                TrackerSort(SORT_ALPHABETICAL, "Alphabetical", compareBy { it.name }),
            ),
            rowsProvider = ::buildRows,
            rowLabel = { it.name },
            rowLabelColor = { it.nameColor },
            filterVariants = { SeaCreatureCatalog.variants().map { it.toDisplayName() } },
            // The dropdown offers friendly names; a row carries the raw variant key. Translate here
            // rather than storing display names on the row, so a relabelling never orphans a filter.
            filterPredicate = { row, selected -> row.variant?.toDisplayName() in selected },
            onResetSession = { FishingTracker.resetSession() },
            isVisible = { cfg.fishing.trackerHud && SkyblockState.onSkyblock && FishingVisibility.isVisible },
            timerLine = { TimerInfo(FishingTimer.formatTime(), FishingTimer.isPaused) },
            headerExtra = ::headerLines,
            // Two different answers, and the panel must not freeze whichever was true at startup: an
            // empty catalog matches nothing, which is not the same as having caught nothing.
            emptyText = { if (SeaCreatureCatalog.isEmpty()) "(catalog not loaded)" else "(no catches yet)" },
            scrollableList = { cfg.fishing.trackerScrollable },
        )
    }

    // ------------------------------------------------------------------ MovableHud contract

    override fun configuredOn(): Boolean = cfg.fishing.trackerHud

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "fishtracker")

    override fun readTransform(): HudTransform = cfg.fishing.trackerHudTransform

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(fishing = fishing.copy(trackerHudTransform = transform)) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().fishing.trackerHudTransform

    override fun drawBackground(): Boolean = cfg.fishing.trackerHudBackground

    override fun backgroundColor(): Int = cfg.fishing.trackerHudBackgroundColor.effective()

    override fun menuToggles(): List<HudMenuToggle> = listOf(
        HudMenuToggle("Background", { cfg.fishing.trackerHudBackground }) {
            ConfigManager.update { copy(fishing = fishing.copy(trackerHudBackground = !fishing.trackerHudBackground)) }
        },
        HudMenuToggle("Scrolling list", { cfg.fishing.trackerScrollable }) {
            ConfigManager.update { copy(fishing = fishing.copy(trackerScrollable = !fishing.trackerScrollable)) }
        },
    )

    // ------------------------------------------------------------------ content

    /**
     * The header lines under the title: the festival countdown, and the optional rate line.
     *
     * Both are omitted rather than blanked when they have nothing to say, so the panel is exactly the
     * legacy one when neither applies.
     */
    private fun headerLines(): List<TrackerSegments> {
        val lines = ArrayList<TrackerSegments>(2)
        if (cfg.fishing.trackerFestivalTimer && FishingFestivalState.active) {
            val seconds = FishingFestivalState.remainingMs() / 1000
            lines.add(
                listOf(
                    "Fishing Festival" to COLOR_FESTIVAL,
                    "  —  " to Theme.TEXT_DIM,
                    String.format(Locale.ROOT, "%dm %02ds left", seconds / 60, seconds % 60) to Theme.TEXT,
                ),
            )
        }
        if (cfg.fishing.trackerShowRates) {
            // Always the SESSION counters, whichever tab is open: the stopwatch only measures this
            // session, so dividing it into an all-time total would be meaningless.
            //
            // XP is worth showing now that it comes from the action bar rather than from the
            // catalog's base values — it is what the account actually earned, multipliers and all,
            // which is exactly the number a grinder is optimizing.
            val session = FishingTracker.session
            FishingTimer.perHour(session.catches)?.let { catchesPerHour ->
                val line = ArrayList<Pair<String, Int>>(4)
                line.add(String.format(Locale.ROOT, "%,.0f", catchesPerHour) to Theme.ACCENT)
                line.add(" catches/h" to Theme.TEXT_DIM)
                FishingTimer.perHour(session.fishingXp.toLong())?.let { xpPerHour ->
                    line.add("  " + String.format(Locale.ROOT, "%,.0f", xpPerHour) to Theme.ACCENT)
                    line.add(" XP/h" to Theme.TEXT_DIM)
                }
                lines.add(line)
            }
        }
        return lines
    }

    /**
     * Which counters a tab reads. `if/else` rather than `when (tab)`: a subject `when` over an enum
     * emits a synthetic `$WhenMappings` class that Fabric's classloader can fail to resolve.
     *
     * [TrackerTab.Event] is not in this tracker's tab list, so anything that is not Session is Total.
     */
    private fun scope(tab: TrackerTab): FishingCounts =
        if (tab == TrackerTab.Session) FishingTracker.session else FishingTracker.allTime()

    private fun buildRows(tab: TrackerTab): List<CreatureRow> {
        val counts = scope(tab)
        return counts.creatureNames().map { name ->
            val creature = SeaCreatureCatalog.byName(name)
            CreatureRow(
                name = creature?.name ?: name,
                nameColor = creature?.rarityColor() ?: Theme.TEXT,
                rarityOrdinal = creature?.rarityOrdinal() ?: -1,
                variant = creature?.variant,
                catches = counts.catchesByCreature[name] ?: 0L,
                doubleHooks = counts.doubleHooksByCreature[name] ?: 0L,
                cocoons = counts.cocoonsByCreature[name] ?: 0L,
            )
        }
    }
}
