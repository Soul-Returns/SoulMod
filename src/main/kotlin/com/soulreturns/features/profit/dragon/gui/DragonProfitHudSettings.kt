package com.soulreturns.features.profit.dragon.gui

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.data.prices.PriceMode
import com.soulreturns.features.profit.dragon.KillSource
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerTab

/**
 * The dragon panel's UI state — tab, sort, filter, columns, the Source picker and the price mode.
 *
 * ### It lives in `config.json`, not in a file of its own
 * The legacy mod gave this a `config/soul/dragon_profit_hud.json` with its own dirty flag, its own
 * tick debounce and its own atomic write — a fourth copy of machinery that already existed three
 * times over. These are small user preferences, and `SoulConfig` is where this baseline keeps user
 * preferences. `FishingHudSettings` made the same move; this is the second one, as that file said it
 * would be.
 *
 * ### Scroll is deliberately not persisted
 * The legacy file stored it, so a relaunch restored a list scrolled to wherever you left it — a state
 * nobody asks for, and one that looks like a bug when the row you had scrolled to is gone.
 *
 * The popup flags are transient by contract: a dropdown boots closed.
 */
object DragonProfitHudSettings : TrackerSettings {
    /** The no-filter sentinel. Not a [KillSource] entry, which is why the field is a string. */
    const val SOURCE_ALL: String = "ALL"

    override var tab: TrackerTab
        get() = TrackerTab.entries.firstOrNull { it.name == cfg.combat.dragons.profitTab } ?: TrackerTab.Session
        set(value) {
            // Guarded: the panel lays out twice per frame and snaps the tab to a valid one as it
            // goes, so an unguarded write here would be a config save on every frame.
            if (value.name == cfg.combat.dragons.profitTab) return
            update { copy(profitTab = value.name) }
        }

    override var sortId: String
        get() = cfg.combat.dragons.profitSort
        set(value) {
            if (value == cfg.combat.dragons.profitSort) return
            update { copy(profitSort = value) }
        }

    /** In memory only — see the class doc. */
    @Volatile
    override var scrollOffset: Float = 0f

    override var filter: Set<String>
        get() = cfg.combat.dragons.profitFilter
        set(value) {
            if (value == cfg.combat.dragons.profitFilter) return
            update { copy(profitFilter = value) }
        }

    override fun isColumnVisible(
        columnId: String,
        defaultVisible: Boolean,
    ): Boolean = cfg.combat.dragons.profitColumns[columnId] ?: defaultVisible

    override fun setColumnVisible(
        columnId: String,
        visible: Boolean,
    ) {
        update { copy(profitColumns = profitColumns + (columnId to visible)) }
    }

    /**
     * Which kill-source partition the panel is reading, or null for both.
     *
     * The `name` round-trip is deliberate and is not just style: the legacy version of this used a
     * `when (value: KillSource?)`, and Fabric's KnotClassLoader failed to resolve the synthetic
     * `DragonProfitHudSettings$WhenMappings` it generated, with a `NoClassDefFoundError` thrown from
     * the setter. Comparing names emits no synthetic at all.
     */
    var sourceFilter: KillSource?
        get() = KillSource.entries.firstOrNull { it.name == cfg.combat.dragons.profitSourceFilter }
        set(value) {
            val stored = value?.name ?: SOURCE_ALL
            if (stored == cfg.combat.dragons.profitSourceFilter) return
            update { copy(profitSourceFilter = stored) }
        }

    /**
     * How the player trades, driving every coin figure on the panel.
     *
     * It is **panel UI state, not a config row** — the user asked for it beside the Source picker
     * rather than buried in `/soul`, because it is a thing you flip while reading the numbers. Same
     * treatment as [tab] / [sortId] / [filter], and written the same way.
     */
    var priceMode: PriceMode
        get() = PriceMode.byIndex(cfg.combat.dragons.priceMode)
        set(value) {
            val stored = value.ordinal
            if (stored == cfg.combat.dragons.priceMode) return
            update { copy(priceMode = stored) }
        }

    @Volatile override var sortDropdownOpen: Boolean = false

    @Volatile override var columnDropdownOpen: Boolean = false

    @Volatile override var filterDropdownOpen: Boolean = false

    @Volatile override var openSelectId: String? = null

    /** A no-op: every setter above writes through [ConfigManager] already. */
    override fun markDirty() = Unit

    private inline fun update(crossinline block: com.soulreturns.config.DragonsConfig.() -> com.soulreturns.config.DragonsConfig) {
        ConfigManager.update { copy(combat = combat.copy(dragons = combat.dragons.block())) }
    }
}
