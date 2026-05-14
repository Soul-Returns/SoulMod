package com.soulreturns.gui.lib.tracker

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * Disk-backed store of [TrackerSettings] keyed by tracker id.
 *
 * Stored at `config/soul/tracker_settings.json`. Writes are debounced one-per-second via a tick
 * listener (mirrors [com.soulreturns.stats.PersistentStats]'s pattern) and dispatched onto
 * [SoulExecutor] so the render thread never blocks on disk. Atomic temp+rename keeps the file
 * intact if the JVM dies mid-write.
 *
 * Public API:
 *  - [init] — must be called once during mod init (before any tracker registers).
 *  - [getOrCreate] — returns the live [TrackerSettings] for an id, seeded from [defaults] if
 *    no entry exists yet. Mutations on the returned object are observed by [markDirty].
 *  - [markDirty] — called by input handlers after mutating a settings record.
 */
object TrackerSettingsStore {
    private val logger = SoulLogger("Soul/Tracker")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/tracker_settings.json")
    }

    private data class Storage(
        var version: Int = 1,
        var trackers: MutableMap<String, TrackerSettings> = mutableMapOf(),
    )

    @Volatile private var storage: Storage = Storage()

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    fun init() {
        load()
        var lastSaveTick = 0
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                lastSaveTick++
                if (dirty && !saving && lastSaveTick >= 20) {
                    lastSaveTick = 0
                    saveAsync()
                }
            }
        )
    }

    /**
     * Returns the live [TrackerSettings] record for [trackerId], creating it from [defaults]
     * if no entry exists yet. Returned reference is shared — mutating it then calling
     * [markDirty] is the canonical update path.
     */
    @Synchronized
    fun getOrCreate(
        trackerId: String,
        defaults: TrackerSettings
    ): TrackerSettings {
        val existing = storage.trackers[trackerId]
        if (existing != null) return existing
        val fresh = defaults.copy()
        storage.trackers[trackerId] = fresh
        markDirty()
        return fresh
    }

    fun markDirty() {
        dirty = true
    }

    /** Force a synchronous reload from disk. Used by future cloud-sync onAfterPull hooks. */
    @Synchronized
    fun reload() {
        dirty = false
        storage = Storage()
        load()
    }

    /** Path to the underlying JSON file. Used by future cloud-sync registration. */
    fun settingsFile(): File = file

    private fun load() {
        if (!file.exists()) {
            logger.info("No tracker settings file at ${file.absolutePath}; starting empty.")
            return
        }
        try {
            val json = JsonParser.parseReader(file.reader())
            if (!json.isJsonObject) {
                logger.warn("tracker_settings.json is not a JSON object — using defaults")
                return
            }
            val loaded = gson.fromJson(json, Storage::class.java) ?: Storage()
            storage = loaded
            logger.info("Loaded v${loaded.version} tracker settings (${loaded.trackers.size} tracker(s))")
        } catch (e: Exception) {
            logger.warn("Failed to read tracker_settings.json — using defaults", e)
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        val snapshot =
            synchronized(this) {
                Storage(
                    version = storage.version,
                    trackers = storage.trackers.mapValuesTo(mutableMapOf()) { (_, s) -> s.copy() },
                )
            }
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
                logger.warn("Failed to persist tracker_settings.json", e)
                dirty = true
            } finally {
                saving = false
            }
        }
    }
}
