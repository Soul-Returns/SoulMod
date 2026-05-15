package com.soulreturns.features.fishing

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * User-controlled UI state for the Fishing HUD — which tab is open, what sort order, how
 * many rows to display, current scroll position.
 *
 * Stored at `config/soul/fishing_hud.json`. Tick-debounced one-write-per-second on a
 * dedicated daemon thread (mirrors `PersistentStats`), so toggling tabs / cycling sort
 * mid-fishing never blocks render. Atomic temp+rename keeps the file consistent if the JVM
 * dies mid-write.
 *
 * **Not** profile-keyed (unlike `PersistentStats`) — the user's HUD preferences are the
 * same regardless of which SkyBlock profile is active.
 *
 * The previous tracker framework had a generic `TrackerSettingsStore` for this; it was
 * retired alongside `TrackerOverlay` in P3 — this is the feature-local replacement.
 */
object FishingHudSettings {
    private val logger = SoulLogger("Soul/FishingHud")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/fishing_hud.json")
    }

    enum class Tab { Session, Total }

    enum class Sort(val label: String) {
        Catches("Catches"),
        DoubleHooks("Double Hooks"),
        Cocoons("Cocoons"),
        Rarity("Rarity"),
        Alphabetical("Alphabetical"),
    }

    /**
     * Live mutable record. UI mutates fields directly, then calls [markDirty].
     *
     * `showCatches` / `showDoubleHooks` / `showCocoons` are per-column visibility toggles.
     * Hiding a column removes it from every row in the list AND from the [Sort] cycle's
     * effective options. There's always at least one column showing because the toggle row
     * refuses to flip the last enabled column off (see `FishingHud.toggleColumn`).
     */
    data class Settings(
        var tab: Tab = Tab.Session,
        var sort: Sort = Sort.Catches,
        var scrollOffset: Float = 0f,
        var showCatches: Boolean = true,
        var showDoubleHooks: Boolean = true,
        var showCocoons: Boolean = true,
        /**
         * Filter for the per-creature list. `null` = show every variant. Otherwise it's the
         * raw `SeaCreature.variant` key (e.g. `"WATER"`, `"LAVA_CRIMSON_ISLE"`) and only
         * rows whose creature lives in that variant render. Persisted so the user's last
         * picked category sticks across sessions.
         */
        var category: String? = null,
    ) {
        /**
         * Whether [sort] is currently selectable. Column-bound sorts (`Catches` / `DH` /
         * `Cocoons`) hide when their column is toggled off — sorting by an invisible column
         * silently shuffles the row order. `Rarity` and `Alphabetical` derive from the
         * creature itself, not a column, so they're always visible.
         */
        fun isSortVisible(sort: Sort): Boolean =
            when (sort) {
                Sort.Catches -> showCatches
                Sort.DoubleHooks -> showDoubleHooks
                Sort.Cocoons -> showCocoons
                Sort.Rarity, Sort.Alphabetical -> true
            }

        /** First visible sort key, falling back to [Sort.Alphabetical] (always visible). */
        fun firstVisibleSort(): Sort = Sort.values().firstOrNull { isSortVisible(it) } ?: Sort.Alphabetical
    }

    @Volatile private var settings: Settings = Settings()

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    /**
     * Transient UI flags — whether each dropdown popup is currently open. Kept off the
     * persisted [Settings] data class so they never serialize into `fishing_hud.json`; all
     * dropdowns boot closed on a fresh client launch.
     */
    @Volatile var columnDropdownOpen: Boolean = false

    @Volatile var sortDropdownOpen: Boolean = false

    @Volatile var categoryDropdownOpen: Boolean = false

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

    fun get(): Settings = settings

    fun markDirty() {
        dirty = true
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
            settings = gson.fromJson(json, Settings::class.java) ?: Settings()
            logger.info("Loaded fishing_hud settings: tab=${settings.tab} sort=${settings.sort}")
        } catch (e: Exception) {
            logger.warn("Failed to read fishing_hud.json — using defaults", e)
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        val snapshot = settings.copy()
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
