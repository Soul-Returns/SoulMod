package com.soulreturns.features.profit.dragon

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * HUD-local UI state for [com.soulreturns.ui.hud.DragonProfitHud]. Stored at
 * `config/soul/dragon_profit_hud.json` with the same tick-debounced atomic-write pattern
 * as `fishing_hud.json` — see [com.soulreturns.features.fishing.FishingHudSettings] for
 * the canonical implementation notes.
 *
 * Not profile-keyed (same rationale as the Fishing HUD settings — UI preferences are
 * cross-profile).
 */
object DragonProfitHudSettings : TrackerSettings {
    private val logger = SoulLogger("Soul/DragonProfitHud")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/dragon_profit_hud.json")
    }

    private data class Data(
        var tab: TrackerTab = TrackerTab.Session,
        var sortId: String = "value",
        var scrollOffset: Float = 0f,
        var filter: Set<String> = emptySet(),
        var columnVisibility: MutableMap<String, Boolean> = mutableMapOf(),
        /**
         * Persisted kill-source view: `"ALL"`, `"SUMMONED"`, or `"LOOTSHARE"`. String-keyed
         * (not the enum directly) because Gson defaults Kotlin enums to their `name` strings
         * and "ALL" doesn't have a [KillSource] enum entry — it's the no-filter sentinel.
         * The picker UI in [com.soulreturns.ui.hud.DragonProfitHud] reads/writes this and
         * translates to `KillSource?` for the tracker queries.
         */
        var sourceFilter: String = "ALL",
    )

    @Volatile private var data = Data()

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    @Volatile override var sortDropdownOpen: Boolean = false

    @Volatile override var columnDropdownOpen: Boolean = false

    @Volatile override var filterDropdownOpen: Boolean = false

    /** Transient — the kill-source picker's open/closed state. Never persisted. */
    @Volatile var sourceDropdownOpen: Boolean = false

    /**
     * Persisted source-filter accessor — translates the `"ALL"` sentinel to `null`. Both
     * branches deliberately avoid `when (value: KillSource?)`: Kotlin compiles enum-`when`
     * into a synthetic `<Class>$WhenMappings` inner class with an `int[]` ordinal table.
     * Fabric's KnotClassLoader has shipped at least one case where that synthetic isn't
     * resolved at runtime under remap/refMap conditions (`NoClassDefFoundError:
     * DragonProfitHudSettings$WhenMappings` on `setSourceFilter`). Reducing to a `name`
     * round-trip removes the enum-`when` entirely and the synthetic class isn't emitted.
     * The String-keyed `when` in older versions was fine too — only the enum side blew up.
     */
    var sourceFilter: KillSource?
        get() = KillSource.entries.firstOrNull { it.name == data.sourceFilter }
        set(value) {
            data.sourceFilter = value?.name ?: "ALL"
            markDirty()
        }

    override var tab: TrackerTab
        get() = data.tab
        set(value) {
            data.tab = value
        }

    override var sortId: String
        get() = data.sortId
        set(value) {
            data.sortId = value
        }

    override var scrollOffset: Float
        get() = data.scrollOffset
        set(value) {
            data.scrollOffset = value
        }

    override var filter: Set<String>
        get() = data.filter
        set(value) {
            data.filter = value
        }

    override fun isColumnVisible(
        columnId: String,
        defaultVisible: Boolean,
    ): Boolean = data.columnVisibility[columnId] ?: defaultVisible

    override fun setColumnVisible(
        columnId: String,
        visible: Boolean,
    ) {
        data.columnVisibility[columnId] = visible
        markDirty()
    }

    override fun markDirty() {
        dirty = true
    }

    fun init() {
        load()
        var ticksSinceSave = 0
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                ticksSinceSave++
                if (dirty && !saving && ticksSinceSave >= 20) {
                    ticksSinceSave = 0
                    saveAsync()
                }
            },
        )
    }

    private fun load() {
        if (!file.exists()) {
            logger.info("No dragon_profit_hud.json — starting from defaults")
            return
        }
        try {
            val json = JsonParser.parseReader(file.reader())
            if (!json.isJsonObject) {
                logger.warn("dragon_profit_hud.json is not a JSON object — using defaults")
                return
            }
            data = gson.fromJson(json, Data::class.java) ?: Data()
            logger.info("Loaded dragon_profit_hud settings: tab=${data.tab} sort=${data.sortId}")
        } catch (e: Exception) {
            logger.warn("Failed to read dragon_profit_hud.json — using defaults", e)
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        val snapshot = data.copy(columnVisibility = data.columnVisibility.toMutableMap())
        SoulExecutor.executor.submit {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(gson.toJson(snapshot))
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    logger.warn("Could not rename ${tmp.name} → ${file.name}; falling back to direct write")
                    file.writeText(gson.toJson(snapshot))
                    tmp.delete()
                }
            } catch (e: Exception) {
                logger.warn("Failed to persist dragon_profit_hud.json", e)
                dirty = true
            } finally {
                saving = false
            }
        }
    }
}
