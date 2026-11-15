package com.soulreturns.features.mining.metaldetector

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.MetalDetectorConfig
import com.soulreturns.config.cfg
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerTab

/** The Metal Detector panel's UI state, in `config.json` like the other trackers; scroll and popups in memory. */
object MetalDetectorHudSettings : TrackerSettings {
    /** Stored filter marker for "no groups"; never a real group name. */
    const val NONE = "none"

    override var tab: TrackerTab
        get() = TrackerTab.entries.firstOrNull { it.name == cfg.mining.metalDetector.hudTab } ?: TrackerTab.Session
        set(value) {
            // Guarded: the panel snaps the tab during both layout passes every frame.
            if (value.name != cfg.mining.metalDetector.hudTab) update { copy(hudTab = value.name) }
        }

    override var sortId: String
        get() = cfg.mining.metalDetector.hudSort
        set(value) {
            if (value != cfg.mining.metalDetector.hudSort) update { copy(hudSort = value) }
        }

    @Volatile override var scrollOffset: Float = 0f

    /**
     * Groups filter. Stored in `hudFilter`: empty = all groups (what "Show All" has always stored),
     * [NONE] = no item rows (the default: header only), otherwise the chosen group names. Exposed to
     * the panel with [NONE] mixed into partial selections, because the panel turns "uncheck the last
     * group" into an empty set, which would otherwise read as "all".
     */
    override var filter: Set<String>
        get() {
            val stored = cfg.mining.metalDetector.hudFilter
            return if (stored.isEmpty()) stored else stored + NONE
        }
        set(value) {
            val groups = value - NONE
            val stored = if (value.isEmpty()) emptySet() else if (groups.isEmpty()) setOf(NONE) else groups
            if (stored != cfg.mining.metalDetector.hudFilter) update { copy(hudFilter = stored) }
        }

    /** No group selected: the panel shows only its header. */
    fun showsNoRows(): Boolean = (cfg.mining.metalDetector.hudFilter - NONE).isEmpty() && cfg.mining.metalDetector.hudFilter.isNotEmpty()
    override fun isColumnVisible(columnId: String, defaultVisible: Boolean): Boolean =
        cfg.mining.metalDetector.hudColumns[columnId] ?: defaultVisible

    override fun setColumnVisible(columnId: String, visible: Boolean) = update { copy(hudColumns = hudColumns + (columnId to visible)) }

    @Volatile override var sortDropdownOpen: Boolean = false

    @Volatile override var columnDropdownOpen: Boolean = false

    @Volatile override var filterDropdownOpen: Boolean = false

    /** Every setter writes through [ConfigManager]. */
    override fun markDirty() = Unit

    private fun update(block: MetalDetectorConfig.() -> MetalDetectorConfig) {
        ConfigManager.update { copy(mining = mining.copy(metalDetector = mining.metalDetector.block())) }
    }
}
