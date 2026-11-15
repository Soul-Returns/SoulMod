package com.soulreturns.features.fishing

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerTab

/**
 * The fishing panel's UI state — which tab, which sort, which filter, which columns.
 *
 * ### It lives in `config.json`, not in a file of its own
 * The legacy mod gave this its own `config/soul/fishing_hud.json`, with its own dirty flag, its own
 * debounce and its own atomic write — a fourth copy of machinery that already existed three times
 * over. These are user preferences, they are small, and `SoulConfig` is where this baseline keeps
 * user preferences. The dragon tracker gets its own block the same way.
 *
 * ### Scroll is deliberately not persisted
 * The legacy file stored `scrollOffset`, so a relaunch restored a list scrolled to wherever you left
 * it — which is a state nobody asks for and one that looks like a bug when the row you scrolled to
 * is no longer there. It is in-memory here. Everything else survives a restart.
 *
 * The three popup flags are transient by contract: a dropdown boots closed.
 */
object FishingHudSettings : TrackerSettings {
    override var tab: TrackerTab
        get() = TrackerTab.entries.firstOrNull { it.name == cfg.fishing.trackerTab } ?: TrackerTab.Session
        set(value) {
            // Guarded: the panel lays out twice per frame (measure, then draw) and snaps the tab to a
            // valid one as it goes, so an unguarded write here would be a config save every frame.
            if (value.name == cfg.fishing.trackerTab) return
            ConfigManager.update { copy(fishing = fishing.copy(trackerTab = value.name)) }
        }

    override var sortId: String
        get() = cfg.fishing.trackerSort
        set(value) {
            if (value == cfg.fishing.trackerSort) return
            ConfigManager.update { copy(fishing = fishing.copy(trackerSort = value)) }
        }

    /** In memory only — see the class doc. */
    @Volatile
    override var scrollOffset: Float = 0f

    override var filter: Set<String>
        get() = cfg.fishing.trackerFilter
        set(value) {
            if (value == cfg.fishing.trackerFilter) return
            ConfigManager.update { copy(fishing = fishing.copy(trackerFilter = value)) }
        }

    override fun isColumnVisible(
        columnId: String,
        defaultVisible: Boolean,
    ): Boolean = cfg.fishing.trackerColumns[columnId] ?: defaultVisible

    override fun setColumnVisible(
        columnId: String,
        visible: Boolean,
    ) {
        ConfigManager.update { copy(fishing = fishing.copy(trackerColumns = fishing.trackerColumns + (columnId to visible))) }
    }

    @Volatile override var sortDropdownOpen: Boolean = false

    @Volatile override var columnDropdownOpen: Boolean = false

    @Volatile override var filterDropdownOpen: Boolean = false

    /**
     * A no-op: every setter above writes through [ConfigManager] immediately, so there is nothing
     * left to flush. The framework calls this after each interaction because a tracker *may* need a
     * debounced save; this one does not.
     */
    override fun markDirty() = Unit
}
