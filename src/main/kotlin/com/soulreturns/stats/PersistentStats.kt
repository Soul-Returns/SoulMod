package com.soulreturns.stats

import com.soulreturns.config.DataBackups
import com.soulreturns.core.events.Events
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The mod's local store for numbers a feature accumulates over time — catches, kills, visits.
 *
 * One file per dataset, `config/soul/stats.json` (Alpha: `config/soul/alpha/stats.json`, [PlayerDataset]):
 * ```json
 * { "schemaVersion": 2, "namespaces": { "fishing": { "Banana": { … }, "Mango": { … } } } }
 * ```
 * A feature claims a [namespace] and gets a [StatsHandle] over its own payload type. Two things are
 * deliberately *not* this layer's business: what the payload looks like (the feature owns a
 * `@Serializable` type) and how the buckets inside a namespace are keyed (the feature passes a `key`
 * function — fishing keys by SkyBlock profile, a future tracker may bucket differently).
 *
 * ### Why one file and one save loop
 * The legacy mod had a profile-keyed `stats.json` **plus** `mythological_mobs.json`,
 * `mythological_profit.json` and `fishing_hud.json`, each carrying its own copy of the same dirty
 * flag, debounce and atomic-write dance — four chances to get the write wrong instead of one. The
 * abstraction here is deliberately thin: a namespace map and a key function, not a plugin system.
 *
 * ### Unknown namespaces survive a round trip
 * Payloads are held as raw [JsonElement] and only decoded through the claiming handle's serializer,
 * so a namespace whose feature is not loaded this session is written back out unchanged rather than
 * being silently dropped.
 *
 * ### It never leaves the machine
 * There is no upload path here and there must not be one. Settings cloud-sync was retired on
 * 2026-08-20 and the backend's `/sync/{kind}` routes were deleted with it; `stats.json` is local,
 * forever. If numbers should be visible to another player, that is an opt-in `soul-stats` namespace
 * aggregated backend-side, not a file mirror.
 *
 * ### Threading
 * [StatsHandle.read] and [StatsHandle.update] are safe from any thread. Mutations only mark a dirty
 * flag; the write happens at most once a second on [SoulScope], never on the render thread. The
 * shutdown flush is the one exception — see [flushOnShutdown].
 */
object PersistentStats : PlayerDataset.Store {
    private val logger = SoulLogger("Soul/Stats")

    private const val SCHEMA_VERSION = 2

    /**
     * Bucket for writes made before the feature's key function could answer — for a profile-keyed
     * namespace, the first seconds of a session while the tab list is still empty. Promoted into the
     * real key on the first [ProfileChanged] that names one; see [promoteLegacy].
     */
    const val LEGACY_KEY: String = "_legacy"

    /** How often the dirty flag is checked, in client ticks (20/s). */
    private const val SAVE_INTERVAL_TICKS = 20

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Guards [raw] and [dirty]. Held only across map arithmetic — never across a file write. */
    private val lock = Any()

    /** namespace id → bucket key → the payload exactly as it sits in the file. */
    private val raw = HashMap<String, HashMap<String, JsonElement>>()

    private val handles = CopyOnWriteArrayList<StatsHandle<*>>()

    @Volatile private var dirty = false

    /** Numbers the encoded snapshots, so an older async write never lands over a newer one. */
    private var encodedSeq = 0L

    /** Guards [writtenSeq] and the file writes themselves. */
    private val writeLock = Any()

    private val writtenSeq = HashMap<Path, Long>()

    init {
        PlayerDataset.attach(this)
    }

    @Volatile private var loaded = false

    @Volatile private var registered = false

    private var ticksSinceSave = 0

    // ------------------------------------------------------------------ public API

    /**
     * Claim [id] as this feature's namespace.
     *
     * @param serializer the payload's serializer; also what a malformed stored bucket is measured
     *   against, in which case the bucket is reported and replaced by [default] rather than crashing.
     * @param default a fresh payload for a key that has nothing stored yet. Called per key.
     * @param key which bucket inside the namespace a read/write goes to, evaluated at call time so a
     *   profile switch redirects immediately. Return [LEGACY_KEY] when the answer is not known yet.
     */
    fun <T : Any> namespace(
        id: String,
        serializer: KSerializer<T>,
        default: () -> T,
        key: () -> String,
    ): StatsHandle<T> {
        load()
        val handle = StatsHandle(id, serializer, default, key)
        handles.add(handle)
        return handle
    }

    /**
     * Wire the save loop and the shutdown flush. Called once from
     * [com.soulreturns.Soul.onInitializeClient]; [namespace] does not need it to have run.
     */
    fun register() {
        if (registered) return
        registered = true
        load()
        Events.subscribe(::promoteLegacy)
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick {
                if (++ticksSinceSave < SAVE_INTERVAL_TICKS) return@EndTick
                ticksSinceSave = 0
                if (dirty && PlayerDataset.writable()) saveAsync()
            },
        )
        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping { flushOnShutdown() })
    }

    /** Bucket keys currently stored under [namespaceId] — for `/soul dev fishing` and friends. */
    fun keysIn(namespaceId: String): Set<String> = synchronized(lock) { raw[namespaceId]?.keys?.toSet() ?: emptySet() }

    /** Absolute path of the open dataset's stats file, for diagnostics. */
    fun file(): Path = PlayerDataset.file("stats.json")

    private val backedUp = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Backs stats.json up at most once per [reason] per dataset and session, before a reset bucket is saved over it. */
    fun backupOnce(reason: String) {
        if (backedUp.add("${PlayerDataset.active}/$reason")) DataBackups.backup(file(), reason)
    }

    /** Writes unsaved changes to the open dataset's file now (dataset switch, join, disconnect). */
    override fun flush() {
        if (!dirty) return
        dirty = false
        val (path, seq, payload) = encode()
        if (!write(path, seq, payload)) dirty = true
    }

    /** Drops every bucket, unsaved ones included, and reads [dataset]'s file. */
    override fun open(dataset: String) {
        synchronized(lock) {
            raw.clear()
            dirty = false
            loaded = false
        }
        load()
        handles.forEach { it.invalidateCache() }
    }

    // ------------------------------------------------------------------ internals used by StatsHandle

    internal fun readRaw(
        namespaceId: String,
        key: String,
    ): JsonElement? = synchronized(lock) { raw[namespaceId]?.get(key) }

    internal fun writeRaw(
        namespaceId: String,
        key: String,
        value: JsonElement,
    ) {
        synchronized(lock) {
            raw.getOrPut(namespaceId) { HashMap() }[key] = value
            dirty = true
        }
    }

    // ------------------------------------------------------------------ load / promote / save

    /** Read the file once. Idempotent, and safe to call before [register]. */
    private fun load() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val path = file()
            if (!Files.isRegularFile(path)) {
                logger.info("No stats file at $path — starting from defaults")
                return
            }
            try {
                val root = json.parseToJsonElement(Files.readString(path)) as? JsonObject
                if (root == null) {
                    DataBackups.backup(path, "unreadable")
                    logger.warn("stats.json is not a JSON object — starting from defaults (backup kept)")
                    return
                }
                val version = (root["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
                val namespaces = root["namespaces"] as? JsonObject
                if (namespaces == null) {
                    // No `namespaces` key at all: either an empty file or a shape from a future
                    // version that restructured the root. Either way there is nothing to read, and
                    // overwriting it is better than guessing at its contents.
                    DataBackups.backup(path, "unreadable")
                    logger.warn("stats.json has no 'namespaces' object (schemaVersion=$version) — starting from defaults (backup kept)")
                    return
                }
                if ((version ?: 0) > SCHEMA_VERSION) DataBackups.backup(path, "newer-v$version")
                var buckets = 0
                val readable = namespaces.entries.mapNotNull { (id, payloads) -> (payloads as? JsonObject)?.let { id to it.toMap() } }.toMap()
                val migrated = StatsMigrations.migrate(version ?: 1, readable)
                if (migrated != readable) {
                    DataBackups.backup(path, "migrate-v${version ?: 1}")
                    dirty = true
                }
                for ((namespaceId, obj) in migrated) {
                    val slot = raw.getOrPut(namespaceId) { HashMap() }
                    for ((key, payload) in obj) {
                        slot[key] = payload
                        buckets++
                    }
                }
                logger.info("Loaded stats.json: ${raw.size} namespace(s), $buckets bucket(s)")
            } catch (t: Throwable) {
                // A stats file we cannot read is not worth crashing a client over, but it IS worth
                // saying loudly: the next save overwrites it, so this is the only notice the user gets.
                DataBackups.backup(path, "unreadable")
                logger.warn("Failed to read stats.json — starting from defaults (backup kept; the file will be overwritten)", t)
            }
        }
    }

    /**
     * Move the [LEGACY_KEY] bucket into the profile that just became known, in **every** namespace.
     *
     * Ported verbatim in behaviour from the legacy mod, including the drop case: if the profile
     * already has a bucket, the legacy one is discarded rather than merged. Merging would double-count
     * on the second session, and keeping it would leak one profile's numbers into the next switch.
     *
     * Getting this wrong loses everything a feature recorded in the first seconds of a session, which
     * is exactly the window where the tab list has not populated and the key function cannot answer.
     */
    private fun promoteLegacy(event: ProfileChanged) {
        val profile = event.current ?: return
        if (profile == LEGACY_KEY) return
        val touched = ArrayList<String>()
        synchronized(lock) {
            for ((namespaceId, buckets) in raw) {
                val legacy = buckets.remove(LEGACY_KEY) ?: continue
                if (buckets.containsKey(profile)) {
                    logger.info("Dropped '$LEGACY_KEY' bucket in '$namespaceId' — '$profile' already had data")
                } else {
                    buckets[profile] = legacy
                    logger.info("Promoted '$LEGACY_KEY' bucket in '$namespaceId' → profile '$profile'")
                }
                touched.add(namespaceId)
                dirty = true
            }
        }
        // Outside the lock: a handle's cache still points at whatever it decoded under either key.
        if (touched.isNotEmpty()) handles.forEach { it.invalidateCache() }
    }

    private fun saveAsync() {
        val (path, seq, payload) = encode()
        dirty = false
        SoulScope.io.launch {
            if (!write(path, seq, payload)) dirty = true // retry on the next tick
        }
    }

    /**
     * Write synchronously on `CLIENT_STOPPING`, on the client thread.
     *
     * It cannot go through [SoulScope]: `SoulScope.register()` runs first in
     * [com.soulreturns.Soul.onInitializeClient], so its own `CLIENT_STOPPING` handler has already
     * cancelled the scope by the time this one runs and a launched write would simply never happen.
     * A local file write of a few kB is milliseconds, so unlike the presence push there is nothing
     * here worth a bounded thread — the only way this blocks a quit is a disk that is already wedged.
     */
    private fun flushOnShutdown() {
        if (!dirty) return
        dirty = false
        val (path, seq, payload) = encode()
        write(path, seq, payload)
    }

    /** Serialize the current state with its target file. Called under no lock beyond its own — cheap, a few kB. */
    private fun encode(): Triple<Path, Long, String> {
        val (path, seq, root) = synchronized(lock) {
            Triple(
                file(),
                ++encodedSeq,
                JsonObject(
                    mapOf(
                        "schemaVersion" to JsonPrimitive(SCHEMA_VERSION),
                        "namespaces" to JsonObject(raw.mapValues { (_, buckets) -> JsonObject(buckets.toMap()) }),
                    ),
                ),
            )
        }
        return Triple(path, seq, json.encodeToString(JsonObject.serializer(), root))
    }

    /** Temp file + move, so a crash mid-write cannot leave a truncated stats file behind. */
    private fun write(path: Path, seq: Long, payload: String): Boolean = synchronized(writeLock) {
        if ((writtenSeq[path] ?: 0L) > seq) return true
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("stats.json.tmp")
            Files.writeString(tmp, payload)
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            writtenSeq[path] = seq
            true
        } catch (t: Throwable) {
            logger.warn("Failed to persist $path", t)
            false
        }
    }
}

/**
 * One feature's view of [PersistentStats] — its namespace, its payload type, its bucket key.
 *
 * Reads are cheap enough for a HUD to call per frame: the decoded payload is cached per bucket key
 * and only re-decoded when the key changes or the bucket is rewritten from underneath (a profile
 * promotion). Writes are copy-on-write, so a reader never observes a half-applied update.
 */
class StatsHandle<T : Any> internal constructor(
    private val namespaceId: String,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
    private val key: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val lock = Any()

    private var cachedKey: String? = null

    @Volatile private var cached: T? = null

    private var peekedKey: String? = null

    private var peeked: T? = null

    /** Which bucket reads and writes currently go to. */
    fun currentKey(): String = key().ifBlank { PersistentStats.LEGACY_KEY }

    /** This namespace's payload for the current bucket, or a fresh default if it has none. */
    fun read(): T {
        val k = currentKey()
        synchronized(lock) {
            cached?.let { if (cachedKey == k) return it }
            val value = decode(k)
            cachedKey = k
            cached = value
            return value
        }
    }

    /**
     * Bucket [k]'s payload (another profile's, for the Profile Viewer), or a fresh default. Read-only;
     * the current bucket comes from [read]'s cache, one other bucket is kept decoded.
     */
    fun read(k: String): T {
        if (k == currentKey()) return read()
        synchronized(lock) {
            peeked?.let { if (peekedKey == k) return it }
            val value = decode(k)
            peekedKey = k
            peeked = value
            return value
        }
    }

    /**
     * Replace this bucket's payload with `block(current)` and mark the file dirty.
     *
     * [block] must be pure — it can run against a payload another thread just wrote, and it holds the
     * handle's lock while it runs.
     */
    fun update(block: T.() -> T) {
        val k = currentKey()
        synchronized(lock) {
            val next = (cached?.takeIf { cachedKey == k } ?: decode(k)).block()
            cachedKey = k
            cached = next
            if (peekedKey == k) {
                peekedKey = null
                peeked = null
            }
            PersistentStats.writeRaw(namespaceId, k, json.encodeToJsonElement(serializer, next))
        }
    }

    /**
     * Apply [block] to every stored bucket of this namespace (every profile, `_legacy` included), as
     * a launch-time session reset does. Buckets that do not decode are left as they are.
     */
    fun updateAll(block: T.() -> T) {
        synchronized(lock) {
            for (k in knownKeys()) {
                val stored = PersistentStats.readRaw(namespaceId, k) ?: continue
                val current = runCatching { json.decodeFromJsonElement(serializer, stored) }.getOrNull() ?: continue
                val next = current.block()
                if (next != current) PersistentStats.writeRaw(namespaceId, k, json.encodeToJsonElement(serializer, next))
            }
            cachedKey = null
            cached = null
            peekedKey = null
            peeked = null
        }
    }

    /** Bucket keys stored under this namespace — every profile that has fished, for a dev dump. */
    fun knownKeys(): Set<String> = PersistentStats.keysIn(namespaceId)

    internal fun invalidateCache() {
        synchronized(lock) {
            cachedKey = null
            cached = null
            peekedKey = null
            peeked = null
        }
    }

    private fun decode(k: String): T {
        val stored = PersistentStats.readRaw(namespaceId, k) ?: return default()
        return try {
            json.decodeFromJsonElement(serializer, stored)
        } catch (t: Throwable) {
            // A bucket we cannot read is data loss either way; starting it over is the only forward
            // move, and saying so is what makes it diagnosable rather than a counter that silently
            // reset itself.
            SoulLogger("Soul/Stats").warn("Bucket '$namespaceId/$k' did not decode — starting it from defaults (backup kept)", t)
            PersistentStats.backupOnce("bucket-$namespaceId")
            default()
        }
    }
}
