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

    /** Possible row-limit options for the "Show: …" button. -1 = "All". */
    val LIMIT_OPTIONS: List<Int> = listOf(5, 10, 15, -1)

    enum class Tab { Session, Total }

    enum class Sort(val label: String) { Catches("Catches"), DoubleHooks("Double Hooks") }

    /** Live mutable record. UI mutates fields directly, then calls [markDirty]. */
    data class Settings(
        var tab: Tab = Tab.Session,
        var sort: Sort = Sort.Catches,
        var limit: Int = 10,
        var scrollOffset: Float = 0f,
    )

    @Volatile private var settings: Settings = Settings()

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

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

    /** Cycle to the next limit value in [LIMIT_OPTIONS]; wraps to first after the last. */
    fun cycleLimit() {
        val idx = LIMIT_OPTIONS.indexOf(settings.limit).coerceAtLeast(0)
        settings.limit = LIMIT_OPTIONS[(idx + 1) % LIMIT_OPTIONS.size]
        settings.scrollOffset = 0f
        markDirty()
    }

    /** Cycle to the next [Sort] enum value. */
    fun cycleSort() {
        val values = Sort.values()
        val idx = values.indexOf(settings.sort).coerceAtLeast(0)
        settings.sort = values[(idx + 1) % values.size]
        settings.scrollOffset = 0f
        markDirty()
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
            logger.info("Loaded fishing_hud settings: tab=${settings.tab} sort=${settings.sort} limit=${settings.limit}")
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
