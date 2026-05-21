package com.soulreturns.features.diana

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
 * Persisted UI state for the Mythological mob HUD — active tab, sort, scroll offset, column
 * visibility, and rarity filter. Stored at `config/soul/mythological_hud.json` with the
 * same tick-debounced atomic-write loop as the Fishing / Dragon HUD settings.
 *
 * Not profile-keyed — UI preferences live across SkyBlock profiles.
 */
object MythologicalHudSettings : TrackerSettings {
    private val logger = SoulLogger("Soul/Diana")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/mythological_hud.json")
    }

    /** Stable column ids — keep in sync with the column declarations in `MythologicalHud.kt`. */
    const val COLUMN_COUNT = "count"
    const val COLUMN_COCOONS = "cocoons"
    const val COLUMN_PERCENT = "percent"

    /** Stable sort ids — keep in sync with the sort declarations in `MythologicalHud.kt`. */
    const val SORT_COUNT = "count"
    const val SORT_COCOONS = "cocoons"
    const val SORT_PERCENT = "percent"
    const val SORT_RARITY = "rarity"
    const val SORT_ALPHABETICAL = "alpha"

    private data class Data(
        var tab: TrackerTab = TrackerTab.Session,
        var sortId: String = SORT_COUNT,
        var scrollOffset: Float = 0f,
        var filter: Set<String> = emptySet(),
        var columnVisibility: MutableMap<String, Boolean> =
            mutableMapOf(
                COLUMN_COUNT to true,
                COLUMN_COCOONS to true,
                COLUMN_PERCENT to true,
            ),
    )

    @Volatile private var data = Data()

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    @Volatile override var sortDropdownOpen: Boolean = false

    @Volatile override var columnDropdownOpen: Boolean = false

    @Volatile override var filterDropdownOpen: Boolean = false

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
            logger.info("No mythological_hud.json — starting from defaults")
            return
        }
        try {
            val json = JsonParser.parseReader(file.reader())
            if (!json.isJsonObject) {
                logger.warn("mythological_hud.json is not a JSON object — using defaults")
                return
            }
            data = gson.fromJson(json, Data::class.java) ?: Data()
            data.columnVisibility.putIfAbsent(COLUMN_COUNT, true)
            data.columnVisibility.putIfAbsent(COLUMN_COCOONS, true)
            data.columnVisibility.putIfAbsent(COLUMN_PERCENT, true)
        } catch (e: Exception) {
            logger.warn("Failed to read mythological_hud.json — using defaults", e)
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
                logger.warn("Failed to persist mythological_hud.json", e)
                dirty = true
            } finally {
                saving = false
            }
        }
    }
}
