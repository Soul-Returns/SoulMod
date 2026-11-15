package com.soulreturns.features.mining.nucleus

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.NucleusConfig
import com.soulreturns.config.cfg
import com.soulreturns.data.prices.PriceMode
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerTab

/** The nucleus panel's UI state in `config.json`; scroll and popups in memory. */
object NucleusHudSettings : TrackerSettings {
    override var tab: TrackerTab
        get() = TrackerTab.entries.firstOrNull { it.name == cfg.mining.nucleus.hudTab } ?: TrackerTab.Session
        set(value) {
            // Guarded: the panel snaps the tab during both layout passes every frame.
            if (value.name != cfg.mining.nucleus.hudTab) update { copy(hudTab = value.name) }
        }

    override var sortId: String
        get() = cfg.mining.nucleus.hudSort
        set(value) {
            if (value != cfg.mining.nucleus.hudSort) update { copy(hudSort = value) }
        }

    @Volatile override var scrollOffset: Float = 0f

    override var filter: Set<String>
        get() = cfg.mining.nucleus.hudFilter
        set(value) {
            if (value != cfg.mining.nucleus.hudFilter) update { copy(hudFilter = value) }
        }

    override fun isColumnVisible(columnId: String, defaultVisible: Boolean): Boolean =
        cfg.mining.nucleus.hudColumns[columnId] ?: defaultVisible

    override fun setColumnVisible(columnId: String, visible: Boolean) = update { copy(hudColumns = hudColumns + (columnId to visible)) }

    /** Panel UI state like the dragon tracker's: flipped in the footer, re-values the whole history. */
    var priceMode: PriceMode
        get() = PriceMode.byIndex(cfg.mining.nucleus.priceMode)
        set(value) {
            if (value.ordinal != cfg.mining.nucleus.priceMode) update { copy(priceMode = value.ordinal) }
        }

    @Volatile override var sortDropdownOpen: Boolean = false

    @Volatile override var columnDropdownOpen: Boolean = false

    @Volatile override var filterDropdownOpen: Boolean = false

    @Volatile override var openSelectId: String? = null

    /** Every setter writes through [ConfigManager]. */
    override fun markDirty() = Unit

    private fun update(block: NucleusConfig.() -> NucleusConfig) {
        ConfigManager.update { copy(mining = mining.copy(nucleus = mining.nucleus.block())) }
    }
}
