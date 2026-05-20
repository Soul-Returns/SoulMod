package com.soulreturns.features.sacks

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
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative in-memory mirror of the player's sack contents, keyed by Hypixel
 * skyblock id (`BLAZE_ROD`, `ENCHANTED_GOLD_INGOT`, …). Persisted standalone at
 * `config/soul/sacks.json` because the map can grow to hundreds of entries and bloating
 * the shared `stats.json` blob would slow every save in that file.
 *
 * **Profile-keyed.** Mirrors [com.soulreturns.stats.PersistentStats]: each SkyBlock
 * profile gets its own [String, Long] map; writes that happen before [ProfileApi] has
 * reported a profile land in a [LEGACY_KEY] bucket and get promoted to the real
 * profile on the first [ProfileChanged] event.
 *
 * **Source of truth: the sack GUI reader.** [SackGuiReader] scans a sack's screen on
 * open and calls [applySnapshot] — that's the authoritative path. The future
 * (Deliverable 3) chat reader will call [applyDeltas] for incremental updates between
 * snapshots. State written from chat deltas drifts over time (missed lines, restarts,
 * non-sack pickups) and gets corrected on the next sack open.
 *
 * **No "missing key = deleted" semantics.** [applySnapshot] does NOT prune keys that
 * weren't present in the new snapshot. Hypixel exhaustively lists every supported
 * material in a sack's screen (even ones with count = 0), so a single sack's snapshot
 * only ever contains that sack's items; entries from OTHER sacks must survive
 * untouched. Items disappearing from Hypixel's sack list (rare) leave stale entries
 * here that the user can clear via `/soul dev sackReset`.
 */
object SackState {
    const val LEGACY_KEY: String = "_legacy"
    private const val SCHEMA_VERSION: Int = 1
    private const val SAVE_TICK_INTERVAL: Int = 20

    private val logger = SoulLogger("Soul/SackState")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/sacks.json")
    }

    private val profiles: ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> = ConcurrentHashMap()

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    @Volatile private var initialized: Boolean = false

    private data class Storage(
        var version: Int = SCHEMA_VERSION,
        var profiles: MutableMap<String, MutableMap<String, Long>> = mutableMapOf(),
    )

    fun init() {
        if (initialized) return
        initialized = true
        load()
        Events.subscribe(::onProfileChanged)
        var ticksSinceSave = 0
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                ticksSinceSave++
                if (dirty && !saving && ticksSinceSave >= SAVE_TICK_INTERVAL) {
                    ticksSinceSave = 0
                    saveAsync()
                }
            },
        )
    }

    fun get(itemId: String): Long = activeMap()[itemId] ?: 0L

    /** Defensive snapshot of the active profile's full state. */
    fun getAll(): Map<String, Long> = activeMap().toMap()

    /**
     * Replace each key from [snapshot] in the active profile. Keys not present in
     * [snapshot] are left untouched — see the class kdoc. [scannedSackTitle] is captured
     * for diagnostics only; it doesn't partition state.
     */
    fun applySnapshot(
        snapshot: Map<String, Long>,
        scannedSackTitle: String? = null,
    ) {
        if (snapshot.isEmpty()) return
        val map = activeMap()
        for ((id, count) in snapshot) {
            map[id] = count
        }
        dirty = true
        if (scannedSackTitle != null) {
            logger.info("Applied snapshot from '$scannedSackTitle': ${snapshot.size} item(s)")
        }
    }

    /**
     * Authoritative full replace of the active profile's state. Used by
     * [com.soulreturns.data.skyblock.SkyblockProfileLoader] when Hypixel's
     * `/skyblock/profiles` endpoint delivers the canonical snapshot — any local data
     * (partial GUI scans, accumulated chat deltas) is overwritten because the API tick
     * is the source of truth at that moment. [applySnapshot] does NOT do this: it
     * merges per-key because GUI scans only cover one sack at a time.
     */
    fun replaceActiveProfile(snapshot: Map<String, Long>) {
        val map = activeMap()
        map.clear()
        for ((id, count) in snapshot) {
            map[id] = count
        }
        dirty = true
        logger.info("Replaced active profile sack state: ${snapshot.size} entries")
    }

    /** Additive delta — used by the chat reader for `[Sacks] +N items` lines. */
    fun applyDeltas(deltas: Map<String, Long>) {
        if (deltas.isEmpty()) return
        val map = activeMap()
        for ((id, delta) in deltas) {
            map.merge(id, delta, Long::plus)
        }
        dirty = true
    }

    /** Wipe the active profile's state. Used by `/soul dev sackReset`. */
    fun resetActiveProfile() {
        activeMap().clear()
        dirty = true
    }

    fun knownProfiles(): Set<String> = profiles.keys.toSet()

    private fun activeMap(): ConcurrentHashMap<String, Long> = profiles.getOrPut(activeKey()) { ConcurrentHashMap() }

    private fun activeKey(): String = ProfileApi.currentProfile ?: LEGACY_KEY

    private fun onProfileChanged(event: ProfileChanged) {
        val newProfile = event.to ?: return
        val legacy = profiles[LEGACY_KEY] ?: return
        val existing = profiles[newProfile]
        if (existing == null) {
            profiles[newProfile] = legacy
            profiles.remove(LEGACY_KEY)
            logger.info("Promoted '$LEGACY_KEY' sack state → profile '$newProfile'")
            dirty = true
        } else {
            profiles.remove(LEGACY_KEY)
            logger.info("Dropped '$LEGACY_KEY' sack state — '$newProfile' already had data")
            dirty = true
        }
    }

    private fun load() {
        if (!file.exists()) {
            logger.info("No sack state on disk at ${file.absolutePath}; starting empty.")
            return
        }
        try {
            FileReader(file).use { reader ->
                val json = JsonParser.parseReader(reader)
                if (!json.isJsonObject) {
                    logger.warn("sacks.json is not a JSON object — starting empty")
                    return
                }
                val obj = json.asJsonObject
                if (!obj.has("profiles")) {
                    logger.warn("sacks.json missing 'profiles' key — starting empty")
                    return
                }
                val loaded = gson.fromJson(obj, Storage::class.java)
                profiles.clear()
                for ((k, v) in loaded.profiles) {
                    profiles[k] = ConcurrentHashMap(v)
                }
                logger.info("Loaded v${loaded.version} sack state (${loaded.profiles.size} profile slot(s))")
            }
        } catch (e: Exception) {
            logger.warn("Failed to read sacks.json — starting empty", e)
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        val snapshot =
            Storage(
                version = SCHEMA_VERSION,
                profiles = profiles.mapValuesTo(mutableMapOf()) { (_, m) -> m.toMutableMap() },
            )
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
                logger.warn("Failed to persist sacks.json", e)
                dirty = true
            } finally {
                saving = false
            }
        }
    }
}
