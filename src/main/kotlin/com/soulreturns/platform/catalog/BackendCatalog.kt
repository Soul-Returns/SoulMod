package com.soulreturns.platform.catalog

import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * How many rows a snapshot holds and when it was last edited — the only two things
 * [BackendCatalog.status] needs from inside a payload it otherwise treats as opaque.
 *
 * A catalog hands the base a `describe` lambda that produces this, rather than the base learning what
 * `creatures` or `items` are. That is the whole seam: reporting, not plumbing.
 */
data class CatalogContents(val entries: Int, val updatedAt: Long?)

/** One catalog's diagnostics, for `/soul dev catalogs`. */
data class CatalogStatus(
    val kind: String,
    val endpoint: String,
    val entries: Int,
    val updatedAt: Long?,
    val source: String,
    val lastRefreshAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastError: String?,
    /** This catalog's own staleness backstop, in ms — see [BackendCatalog.staleAfterMs]. */
    val staleAfterMs: Long,
)

/**
 * The client mirror of one backend catalog endpoint: a snapshot held in memory, cached on disk, and
 * refreshed whenever the backend says it changed.
 *
 * ### Where this came from
 * Doc 06 wrote a single bespoke `SeaCreatureCatalogClient` and stated the escape hatch in its own
 * KDoc: *"if a second catalog consumer ever lands, generalise then — a 30-minute refactor with one
 * working implementation in front of you."* Doc 07 added two more (`/drops`, `/items`), so this is
 * that refactor. Everything below is that file's load / refresh / persist path with the six
 * catalog-specific declarations lifted into constructor parameters; the sea-creature catalog is now
 * one declaration among three.
 *
 * ### Rules that are load-bearing — every one of them was earned
 * - **`bypassCache = true` on every refresh.** A catalog keeps its own snapshot, so
 *   [BackendClient]'s HTTP cache — an hour long, per the endpoint's `X-Backend-Expire-In` — would
 *   otherwise serve the pre-edit payload and silently mask the very edit the invalidate announced.
 * - **A failure leaves the existing snapshot untouched.** Offline, unauthenticated or handed a body
 *   that will not parse, a catalog keeps what it had (or stays empty on a first run) and its
 *   consumers simply match nothing. There is deliberately **no config toggle** and **no bundled
 *   fallback snapshot** for any catalog: this is data the feature cannot work without, and a toggle
 *   would only add a state in which the feature looks broken.
 * - **The disk cache is read synchronously at [init]**, so a lookup arriving before the first network
 *   round trip still answers.
 * - **Atomic disk writes.** A truncated cache file poisons the next launch, which then looks like a
 *   backend problem.
 * - **Listeners are notified with the new snapshot as an argument**, after the volatile swap. They
 *   build their indices locally and publish them with their own volatile assignment, so an index is
 *   never observed half-built. Reading [snapshot] back inside a listener would be a race for nothing.
 *
 * Invalidate routing, the debounce and the staleness *tick* live one level up, in [CatalogRegistry]
 * — there is one subscription and one tick for every catalog, not one per catalog. The **interval**
 * is per catalog, because a catalog's tolerable staleness is a property of what it holds: see
 * [staleAfterMs].
 *
 * @param kind the `sync-invalidate` kind the backend publishes for this catalog. Also the registry
 *   key and the suffix of the `X-Intent` header (`catalog-refresh:<kind>`), so the three cannot drift.
 * @param describe reads the entry count and `updatedAt` out of a snapshot, for [status] only.
 * @param staleAfterMs refresh anyway when the last success is older than this. The default is the two
 *   hours every catalog used to share, which is right for an admin-curated one: it is double the
 *   endpoint's own TTL, and the data changes when a human edits it. It is badly wrong for a catalog
 *   the backend rebuilds on a timer — prices carry a 2 min TTL, and a client whose realtime
 *   connection has dropped would otherwise quote a two-hour-old market while looking perfectly
 *   healthy. Set it near the endpoint's TTL for anything that regenerates on its own.
 */
class BackendCatalog<T : Any>(
    val kind: String,
    val endpoint: String,
    private val cacheFile: String,
    private val serializer: KSerializer<T>,
    private val empty: T,
    private val describe: (T) -> CatalogContents,
    internal val staleAfterMs: Long = CatalogRegistry.DEFAULT_STALE_AFTER_MS,
) {
    private val logger = SoulLogger("Soul/Catalog")

    /**
     * `coerceInputValues` for the same reason [BackendClient] sets it: this reads a cache a *previous
     * build* wrote, so a property that has since become non-nullable would otherwise throw and
     * discard the whole file rather than the one value.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val intent = "catalog-refresh:$kind"

    private val initialized = AtomicBoolean(false)

    /** Bumped by every scheduled refresh; a delayed one that is no longer the newest drops itself. */
    private val refreshGeneration = AtomicLong()

    private val listeners = CopyOnWriteArrayList<(T) -> Unit>()

    @Volatile
    var snapshot: T = empty
        private set

    @Volatile private var source: String = "empty"

    @Volatile private var lastRefreshAt: Instant? = null

    @Volatile private var lastSuccessMs: Long = 0L

    @Volatile private var lastError: String? = null

    /** Epoch ms of the last successful refresh, or 0. Read by [CatalogRegistry]'s staleness backstop. */
    internal val lastSuccessAtMs: Long get() = lastSuccessMs

    /**
     * Called after every snapshot swap, with the new snapshot — an index-holder rebuilds its lookup
     * maps here rather than re-walking the entry list per query.
     *
     * Registering does **not** fire immediately: [init] loads from disk before consumers exist, so a
     * consumer registering afterwards reads [snapshot] once itself and then waits for changes.
     */
    fun addListener(listener: (T) -> Unit) {
        listeners.add(listener)
    }

    /** Load the disk cache, then schedule the first network refresh. Idempotent. */
    internal fun init() {
        if (!initialized.compareAndSet(false, true)) return
        loadFromDisk()
        scheduleRefresh("startup", delayMs = 0L)
    }

    /**
     * Fetch the catalog and, on success only, swap the snapshot and rewrite the disk cache.
     *
     * @return a one-line outcome, for the dev commands that force a refresh.
     */
    suspend fun refresh(): String {
        lastRefreshAt = Instant.now()
        val result = BackendClient.get(endpoint, serializer, intent = intent, bypassCache = true)
        // `if (result is …)`, never `when (result)` — see BackendResult's own KDoc.
        if (result is BackendResult.Ok) {
            val fresh = result.value
            snapshot = fresh
            source = "network"
            lastSuccessMs = System.currentTimeMillis()
            lastError = null
            notifyListeners(fresh)
            persistToDisk(fresh)
            val contents = describe(fresh)
            val outcome = "refreshed: ${contents.entries} entries, updatedAt=${contents.updatedAt}"
            logger.info("$endpoint $outcome")
            return outcome
        }
        val failure = result as BackendResult.Failure
        lastError = "HTTP ${failure.status}: ${failure.message}"
        if (failure.status == BackendResult.Failure.MALFORMED) {
            // Categorically different from the failures below, and worth a warning: a body that will
            // not deserialize means the contract broke, and unlike being offline it will not fix
            // itself on the next poll. It costs the whole catalog, so the feature looks merely empty.
            logger.warn("$endpoint returned a body this build cannot read — the catalog stays empty: $lastError")
        } else {
            // Offline, or before the first authenticated session: the normal state, and the previous
            // snapshot is still perfectly usable.
            logger.info("$endpoint refresh failed (snapshot kept): $lastError")
        }
        return "refresh failed: $lastError"
    }

    fun status(): CatalogStatus {
        val contents = describe(snapshot)
        return CatalogStatus(
            kind = kind,
            endpoint = endpoint,
            entries = contents.entries,
            updatedAt = contents.updatedAt,
            source = source,
            lastRefreshAt = lastRefreshAt,
            lastSuccessAt = lastSuccessMs.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it) },
            lastError = lastError,
            staleAfterMs = staleAfterMs,
        )
    }

    // ------------------------------------------------------------------ internals

    /** Debounced refresh. A newer schedule supersedes a pending one instead of queueing behind it. */
    internal fun scheduleRefresh(
        reason: String,
        delayMs: Long = CatalogRegistry.INVALIDATE_DEBOUNCE_MS,
    ) {
        val generation = refreshGeneration.incrementAndGet()
        SoulScope.io.launch {
            if (delayMs > 0) delay(delayMs)
            if (refreshGeneration.get() != generation) return@launch
            logger.debug("Refreshing $endpoint ($reason)")
            refresh()
        }
    }

    private fun notifyListeners(fresh: T) {
        for (listener in listeners) {
            try {
                listener(fresh)
            } catch (t: Throwable) {
                logger.warn("Catalog listener threw for '$kind'", t)
            }
        }
    }

    private fun cacheFilePath(): Path = FabricLoader.getInstance().configDir.resolve("soul").resolve(cacheFile)

    private fun loadFromDisk() {
        val path = cacheFilePath()
        if (!Files.isRegularFile(path)) {
            logger.info("No $cacheFile cache — starting empty until the first refresh")
            return
        }
        try {
            val parsed = json.decodeFromString(serializer, Files.readString(path))
            snapshot = parsed
            source = "disk"
            notifyListeners(parsed)
            val contents = describe(parsed)
            logger.info("Loaded $cacheFile from disk: ${contents.entries} entries, updatedAt=${contents.updatedAt}")
        } catch (t: Throwable) {
            // Truncated or hand-edited. Start empty and let the network refresh repair it — the file
            // is a cache, so there is nothing here that is not recoverable.
            lastError = "cache unreadable: ${t.message ?: t.javaClass.simpleName}"
            logger.warn("Could not read $cacheFile — starting empty, the next refresh will rewrite it", t)
        }
    }

    private fun persistToDisk(value: T) {
        SoulScope.io.launch {
            try {
                val path = cacheFilePath()
                Files.createDirectories(path.parent)
                val tmp = path.resolveSibling("$cacheFile.tmp")
                Files.writeString(tmp, json.encodeToString(serializer, value))
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (t: Throwable) {
                logger.warn("Failed to persist $cacheFile", t)
            }
        }
    }
}
