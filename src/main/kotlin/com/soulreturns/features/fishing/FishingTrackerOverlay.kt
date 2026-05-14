package com.soulreturns.features.fishing

import com.soulreturns.config.cfg
import com.soulreturns.data.fishing.SeaCreatureCatalog
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.gui.lib.GuiLayoutApi
import com.soulreturns.gui.lib.tracker.TrackerOverlay
import com.soulreturns.gui.lib.tracker.TrackerOverlayRegistry
import com.soulreturns.gui.lib.tracker.TrackerRow
import com.soulreturns.gui.lib.tracker.TrackerSettings
import com.soulreturns.gui.lib.tracker.TrackerSortOption
import com.soulreturns.gui.lib.tracker.TrackerTab
import com.soulreturns.stats.PersistentStats
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Fishing HUD — the new tracker-overlay-based replacement for the old text-only FishingHud.
 *
 * Surfaces session + total per-creature counts in a tabbed, sortable, scrollable panel; a
 * separate small text block above the panel shows the festival countdown when active.
 *
 * Per-creature data sources:
 *  - **Session** tab → [FishingTracker.sessionCatchesByCreature] / `sessionDoubleHooksByCreature`
 *  - **Total** tab → [PersistentStats.current.catchesByCreature] / `doubleHooksByCreature`
 */
object FishingTrackerOverlay {
    private const val OVERLAY_ID = "fishing_tracker"
    private const val FESTIVAL_STICKER_ID = "fishing_festival_sticker"

    private const val SORT_CATCHES = "catches"
    private const val SORT_DOUBLE_HOOKS = "double_hooks"

    fun register() {
        TrackerOverlayRegistry.register(buildOverlay())
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ -> updateHud() })
    }

    private fun buildOverlay(): TrackerOverlay =
        TrackerOverlay(
            id = OVERLAY_ID,
            title = "Fishing",
            tabs =
                listOf(
                    TrackerTab(name = "Session", rowsProvider = ::sessionRows),
                    TrackerTab(name = "Total", rowsProvider = ::totalRows),
                ),
            sortOptions =
                listOf(
                    TrackerSortOption(key = SORT_CATCHES, label = "Catches"),
                    TrackerSortOption(key = SORT_DOUBLE_HOOKS, label = "Double Hooks"),
                ),
            defaults =
                TrackerSettings(
                    activeTab = "Session",
                    sortKey = SORT_CATCHES,
                    limit = 10,
                ),
            showResetButton = true,
            onReset = { FishingTracker.resetSession() },
        )

    private fun updateHud() {
        val hudCfg = cfg.fishing.fishingHud
        val showHud =
            hudCfg.showHud() &&
                cfg.fishing.fishingTracker.enableTracker() &&
                SkyblockApi.isOnSkyblock

        GuiLayoutApi.updateTrackerOverlay(
            id = OVERLAY_ID,
            enabled = showHud,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.45,
            defaultScale = 1.0f,
        )

        val festivalActive = FishingFestivalState.active
        val showSticker = showHud && hudCfg.showFestivalTimer() && festivalActive
        val stickerLines =
            if (showSticker) {
                listOf("§6Fishing Festival §7— §f${formatDuration(FishingFestivalState.remainingMs())} left")
            } else {
                emptyList()
            }
        GuiLayoutApi.updateTextBlock(
            id = FESTIVAL_STICKER_ID,
            lines = stickerLines,
            color = 0xFFFFFFFF.toInt(),
            enabled = showSticker,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.42,
            defaultScale = 1.0f,
        )
    }

    private fun sessionRows(): List<TrackerRow> =
        buildRows(
            catches = FishingTracker.sessionCatchesByCreature,
            doubleHooks = FishingTracker.sessionDoubleHooksByCreature,
        )

    private fun totalRows(): List<TrackerRow> {
        val stats = PersistentStats.current
        return buildRows(catches = stats.catchesByCreature, doubleHooks = stats.doubleHooksByCreature)
    }

    private fun buildRows(
        catches: Map<String, Long>,
        doubleHooks: Map<String, Long>,
    ): List<TrackerRow> {
        val creatureNames = catches.keys + doubleHooks.keys
        return creatureNames.map { name ->
            val c = catches[name] ?: 0L
            val dh = doubleHooks[name] ?: 0L
            val displayName = SeaCreatureCatalog.byName(name)?.name ?: name
            TrackerRow(
                label = displayName,
                primaryValue = c,
                secondaryValue = if (dh > 0L) dh else null,
                secondaryLabel = "DH",
                sortValues =
                    mapOf(
                        SORT_CATCHES to c,
                        SORT_DOUBLE_HOOKS to dh,
                    ),
            )
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%dm %02ds".format(m, s)
    }
}
