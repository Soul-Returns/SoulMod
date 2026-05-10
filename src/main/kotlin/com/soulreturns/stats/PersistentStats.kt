package com.soulreturns.stats

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ProfileChanged
import com.soulreturns.data.profile.ProfileApi
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.io.FileReader

/**
 * Profile-keyed persistence for tracked numeric stats (seasonings, kills, sack counts, ...).
 *
 * Stored at `config/soul/stats.json`. Each Hypixel SkyBlock profile gets its own [Data] slot;
 * [current] returns the active profile's slot, falling back to a session-local "_legacy" slot
 * before [ProfileApi] has reported a profile.
 *
 * Mutations go through [update] on whatever thread; the dirty flag is checked once per
 * second and a write to disk is enqueued on [SoulExecutor] (off the render thread).
 *
 * **Schema migration:** v1 was a flat `Data` object. On first load of a v1 file we wrap
 * the existing values into the [LEGACY_KEY] slot. On the first [ProfileChanged] event with
 * a non-null profile, the legacy slot promotes into that profile (if its slot is empty)
 * or is dropped (if the slot already has data).
 */
object PersistentStats {
    private val logger = SoulLogger("Soul/Stats")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/stats.json")
    }

    private const val SCHEMA_VERSION = 2
    /** Bucket holding stats accumulated before [ProfileApi] knew which profile we were on. */
    const val LEGACY_KEY = "_legacy"

    /**
     * All persisted per-profile stats. Adding a `var x: Long = 0L` field is a no-migration
     * extension — Gson will fill it with the default for any older profile slot on read.
     */
    data class Data(
        var seasonings: Long = 0L,
        /** Sorted ascending Y values from the Harvest Feast milestones (e.g. `[5, 25, 75, 150, 250]`). */
        var milestoneTargets: List<Long> = emptyList(),
    )

    /** Top-level v2 storage shape: schema version + per-profile slots. */
    private data class Storage(
        var version: Int = SCHEMA_VERSION,
        var profiles: MutableMap<String, Data> = mutableMapOf(),
    )

    @Volatile private var storage: Storage = Storage()
    @Volatile private var dirty: Boolean = false
    @Volatile private var saving: Boolean = false

    fun init() {
        load()
        Events.subscribe(::onProfileChanged)
        // Persist no more than once per second; coalesces bursts of mutations into one write.
        var lastSaveTick = 0
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ ->
            lastSaveTick++
            if (dirty && !saving && lastSaveTick >= 20) {
                lastSaveTick = 0
                saveAsync()
            }
        })
    }

    /**
     * Active profile's [Data] slot, creating it on demand if missing. Falls back to the
     * [LEGACY_KEY] slot if no profile is known yet (early-session writes), then to a fresh
     * default slot.
     */
    val current: Data
        get() = synchronized(this) {
            storage.profiles.getOrPut(activeKey()) { Data() }
        }

    /** Mutate the active profile's stats. Marks dirty so the next tick triggers a save. */
    inline fun update(block: Data.() -> Unit) {
        synchronized(this) {
            current.block()
            markDirty()
        }
    }

    @PublishedApi internal fun markDirty() { dirty = true }

    /** Profiles currently in storage. Useful for debug commands. */
    fun knownProfiles(): Set<String> = storage.profiles.keys.toSet()

    // ───────────────────── internals ─────────────────────

    private fun activeKey(): String {
        val profile = ProfileApi.currentProfile
        if (profile != null) return profile
        // Pre-profile: route writes to the legacy slot so they migrate on first detection.
        return LEGACY_KEY
    }

    private fun onProfileChanged(event: ProfileChanged) {
        val newProfile = event.to ?: return
        synchronized(this) {
            val legacy = storage.profiles[LEGACY_KEY] ?: return
            val existing = storage.profiles[newProfile]
            if (existing == null) {
                storage.profiles[newProfile] = legacy
                storage.profiles.remove(LEGACY_KEY)
                logger.info("Promoted '$LEGACY_KEY' stats → profile '$newProfile'")
            } else {
                // Slot already has data — trust it and drop the legacy bucket to prevent leakage.
                storage.profiles.remove(LEGACY_KEY)
                logger.info("Dropped '$LEGACY_KEY' stats — '$newProfile' already had data")
            }
            markDirty()
        }
    }

    private fun load() {
        if (!file.exists()) {
            logger.info("No stats file at ${file.absolutePath}; starting from defaults.")
            return
        }
        try {
            FileReader(file).use { reader ->
                val json = JsonParser.parseReader(reader)
                if (!json.isJsonObject) {
                    logger.warn("stats.json is not a JSON object — using defaults")
                    return
                }
                val obj = json.asJsonObject
                if (obj.has("profiles")) {
                    // v2 format
                    val loaded = gson.fromJson(obj, Storage::class.java)
                    storage = loaded
                    logger.info("Loaded v${loaded.version} stats (${loaded.profiles.size} profile slot(s))")
                } else {
                    // v1 (legacy flat) format — wrap into the legacy slot.
                    val legacyData = gson.fromJson(obj, Data::class.java)
                    storage = Storage(
                        version = SCHEMA_VERSION,
                        profiles = mutableMapOf(LEGACY_KEY to legacyData),
                    )
                    logger.info("Migrated legacy stats.json (v1 → v2). Bound to '$LEGACY_KEY' until profile detected.")
                    markDirty()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to read stats.json — using defaults", e)
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        val snapshot = synchronized(this) {
            // Deep-copy: each Data value is itself mutable so a shallow Storage.copy() isn't enough.
            Storage(
                version = storage.version,
                profiles = storage.profiles.mapValuesTo(mutableMapOf()) { (_, d) -> d.copy() },
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
                logger.warn("Failed to persist stats.json", e)
                dirty = true // retry next tick
            } finally {
                saving = false
            }
        }
    }
}
