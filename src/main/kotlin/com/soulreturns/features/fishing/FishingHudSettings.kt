package com.soulreturns.features.fishing

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.soulreturns.data.fishing.SeaCreatureCatalog.toDisplayName
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * User-controlled UI state for the Fishing HUD — which tab is open, what sort order, scroll
 * position, per-column visibility, and the per-creature variant filter.
 *
 * Stored at `config/soul/fishing_hud.json` with tick-debounced atomic writes (mirrors
 * `PersistentStats`). **Not** profile-keyed — UI preferences live across SkyBlock profiles.
 *
 * **Migrated to the [TrackerSettings] interface in the tracker-framework refactor.** The
 * old persisted shape used explicit `showCatches` / `showDoubleHooks` / `showCocoons`
 * booleans plus a `Sort` enum value; the new shape stores a `columnVisibility: Map<String,
 * Boolean>` + a string `sortId`. The load path detects the legacy shape and migrates on
 * first read so existing users keep their preferences without manual intervention.
 */
object FishingHudSettings : TrackerSettings {
    private val logger = SoulLogger("Soul/FishingHud")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/fishing_hud.json")
    }

    /** Stable column ids — keep in sync with the column declarations in `FishingHud.kt`. */
    const val COLUMN_CATCHES = "catches"
    const val COLUMN_DOUBLE_HOOKS = "doubleHooks"
    const val COLUMN_COCOONS = "cocoons"

    /** Stable sort ids — keep in sync with the sort declarations in `FishingHud.kt`. */
    const val SORT_CATCHES = "catches"
    const val SORT_DOUBLE_HOOKS = "doubleHooks"
    const val SORT_COCOONS = "cocoons"
    const val SORT_RARITY = "rarity"
    const val SORT_ALPHABETICAL = "alpha"

    private data class Data(
        var tab: TrackerTab = TrackerTab.Session,
        var sortId: String = SORT_CATCHES,
        var scrollOffset: Float = 0f,
        var filter: Set<String> = emptySet(),
        var columnVisibility: MutableMap<String, Boolean> =
            mutableMapOf(
                COLUMN_CATCHES to true,
                COLUMN_DOUBLE_HOOKS to true,
                COLUMN_COCOONS to true,
            ),
    )

    @Volatile private var data = Data()

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    /**
     * Transient — popups boot closed every client launch. Kept off the persisted [Data]
     * class so they never serialize.
     */
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
            logger.info("No fishing_hud.json — starting from defaults")
            return
        }
        try {
            val json = JsonParser.parseReader(file.reader())
            if (!json.isJsonObject) {
                logger.warn("fishing_hud.json is not a JSON object — using defaults")
                return
            }
            val obj = json.asJsonObject
            // Legacy shape: had explicit `showCatches` / `showDoubleHooks` / `showCocoons`
            // booleans + a `sort: "Catches"` enum-name string. Detect via the column flag
            // existence and migrate to the new columnVisibility / sortId shape on the fly.
            val isLegacy =
                !obj.has("columnVisibility") && (obj.has("showCatches") || obj.has("showDoubleHooks") || obj.has("showCocoons"))
            if (isLegacy) {
                data = parseLegacy(obj)
                dirty = true
                logger.info(
                    "Migrated legacy fishing_hud.json " +
                        "(tab=${data.tab} sort=${data.sortId} cols=${data.columnVisibility})",
                )
                return
            }
            data = gson.fromJson(json, Data::class.java) ?: Data()
            // Backfill any missing column ids in case a future version adds a column —
            // unknown keys stay at their declared default for the affected column.
            for (id in listOf(COLUMN_CATCHES, COLUMN_DOUBLE_HOOKS, COLUMN_COCOONS)) {
                data.columnVisibility.putIfAbsent(id, true)
            }
            logger.info("Loaded fishing_hud settings: tab=${data.tab} sort=${data.sortId}")
        } catch (e: Exception) {
            logger.warn("Failed to read fishing_hud.json — using defaults", e)
        }
    }

    private fun parseLegacy(obj: com.google.gson.JsonObject): Data {
        val out = Data()
        // tab (enum name)
        out.tab = obj.get("tab")?.asString?.let { runCatching { TrackerTab.valueOf(it) }.getOrNull() } ?: TrackerTab.Session
        // sort (legacy enum names: Catches / DoubleHooks / Cocoons / Rarity / Alphabetical)
        out.sortId =
            when (obj.get("sort")?.asString) {
                "Catches" -> SORT_CATCHES
                "DoubleHooks" -> SORT_DOUBLE_HOOKS
                "Cocoons" -> SORT_COCOONS
                "Rarity" -> SORT_RARITY
                "Alphabetical" -> SORT_ALPHABETICAL
                else -> SORT_CATCHES
            }
        out.scrollOffset = obj.get("scrollOffset")?.asFloat ?: 0f
        val categories = obj.getAsJsonArray("categories")
        // Legacy stored raw variant keys (e.g. `"LAVA_CRIMSON_ISLE"`); the framework now
        // compares against display names from the dropdown (e.g. `"Lava Crimson Isle"`).
        // Convert at migration time so the user's previous filter still matches rows.
        out.filter =
            if (categories != null) {
                categories.mapNotNull { it.asString?.toDisplayName() }.toSet()
            } else {
                emptySet()
            }
        out.columnVisibility =
            mutableMapOf(
                COLUMN_CATCHES to (obj.get("showCatches")?.asBoolean ?: true),
                COLUMN_DOUBLE_HOOKS to (obj.get("showDoubleHooks")?.asBoolean ?: true),
                COLUMN_COCOONS to (obj.get("showCocoons")?.asBoolean ?: true),
            )
        return out
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
                logger.warn("Failed to persist fishing_hud.json", e)
                dirty = true
            } finally {
                saving = false
            }
        }
    }
}
