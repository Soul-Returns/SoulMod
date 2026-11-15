package com.soulreturns.data.prices

import com.soulreturns.core.events.Events
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendAuthenticated
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.platform.realtime.SyncInvalidate
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.item.ItemStack
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One key of `GET /market/values`. Every field is nullable because the backend sends an explicit
 * null for anything unknown (kotlinx defaults cover only absent keys). Prices are coins with one
 * decimal: `buy` = bazaar instant buy (ask), `sell` = instant sell (bid), both null off the bazaar;
 * `lbin` null = nothing listed now. `trend24h` is a fraction, `liquidity` items/day, `tts50` seconds.
 */
@Serializable
data class MarketValue(
    val fair: Double? = null,
    val buy: Double? = null,
    val sell: Double? = null,
    val lbin: Double? = null,
    val trend24h: Double? = null,
    val liquidity: Double? = null,
    val tts50: Long? = null,
)

/** `GET /market/values` and the on-disk cache (always written as a full map). */
@Serializable
data class MarketValuesPayload(
    val schemaVersion: Int = 1,
    val generation: Long = 0,
    val full: Boolean = false,
    val items: Map<String, MarketValue> = emptyMap(),
)

/**
 * The live market map behind price tooltips: `/market/values` mirrored in memory, followed by deltas.
 *
 * Not a [com.soulreturns.platform.catalog.BackendCatalog]: a catalog swaps whole snapshots, this one
 * merges `?since=<generation>` deltas into the map it holds. Same rules otherwise: disk cache read
 * synchronously at [init], atomic writes, a failure keeps the map, no toggle, no bundled fallback.
 *
 * - **Full fetch** at start and on the first [BackendAuthenticated] if nothing succeeded yet. Until a
 *   network fetch succeeded this session every fetch is full: a disk cursor may come from another
 *   backend, and the cursor is that backend's build clock.
 * - **Delta** on `sync-invalidate` kind `market` (topic `sync/catalogs`), coalesced [COALESCE_MS] so
 *   the bazaar (~20 s) and AH (60 s) events of one moment cost one request. The cursor is the
 *   previous RESPONSE's `generation`, never the event's (that one is Hypixel's clock).
 * - **Backstop**: no fetch for [STALE_AFTER_MS] (realtime down, off Hypixel, or a lost event; the
 *   hub has no replay) → delta anyway.
 * - Every fetch drops `/market/values` from [BackendClient]'s HTTP cache afterwards: each `?since=`
 *   is a new cache key that would otherwise live forever.
 * - Disk writes after a full fetch and at most every [PERSIST_INTERVAL_MS] after deltas; the map is
 *   ~650 KB and changes every 20 s.
 *
 * [get] is a plain volatile map read, safe from the render thread; maps are never mutated after
 * publication.
 */
object MarketValues {
    private val logger = SoulLogger("Soul/Market")

    const val KIND: String = "market"
    private const val TOPIC = "sync/catalogs"
    const val ENDPOINT: String = "/market/values"
    const val CACHE_FILE: String = "market_values.json"

    private const val COALESCE_MS = 1_500L
    private const val STALE_AFTER_MS = 2L * 60 * 1000
    private const val BACKSTOP_CHECK_MS = 30_000L
    private const val PERSIST_INTERVAL_MS = 5L * 60 * 1000

    /** Compact on purpose: pretty-printing a 5 000-key map doubles the file for nobody. */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Volatile private var items: Map<String, MarketValue> = emptyMap()

    /** Cursor from the last network response; 0 = next fetch is full. */
    @Volatile private var generation: Long = 0L

    @Volatile private var diskGeneration: Long = 0L
    @Volatile private var source: String = "empty"
    @Volatile private var lastAttemptMs: Long = 0L
    @Volatile private var lastSuccessMs: Long = 0L
    @Volatile private var lastFullMs: Long = 0L
    @Volatile private var lastDeltaMs: Long = 0L
    @Volatile private var lastDeltaKeys: Int = 0
    @Volatile private var lastPersistMs: Long = 0L
    @Volatile private var lastEventMs: Long = 0L
    @Volatile private var lastError: String? = null

    private val deltaCount = AtomicLong()
    private val eventCount = AtomicLong()
    private val deltaPending = AtomicBoolean(false)
    private val fetchLock = Mutex()
    private val initialized = AtomicBoolean(false)

    /** The values for a market key (`HYPERION`, `ENDER_DRAGON;4+100`), or null when unknown. */
    fun get(key: String): MarketValue? = items[key]

    /** The values for [stack] via [MarketItemKey], or null for a non-SkyBlock or unpriced item. */
    fun get(stack: ItemStack): MarketValue? = MarketItemKey.of(stack)?.let { items[it] }

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        loadFromDisk()
        Events.subscribe<SyncInvalidate> { onInvalidate(it) }
        // The startup fetch may itself trigger the handshake that publishes this; fetch() skips
        // an "auth" run once anything succeeded.
        Events.subscribe<BackendAuthenticated> { SoulScope.io.launch { fetch(REASON_AUTH) } }
        SoulScope.io.launch {
            fetch("startup")
            while (true) {
                delay(BACKSTOP_CHECK_MS)
                if (System.currentTimeMillis() - lastAttemptMs >= STALE_AFTER_MS) fetch("backstop")
            }
        }
    }

    /** Dev command: [full] drops the cursor first. */
    suspend fun refreshNow(full: Boolean): String {
        if (full) generation = 0L
        return fetch("manual")
    }

    private const val REASON_AUTH = "auth"

    private fun onInvalidate(event: SyncInvalidate) {
        if (event.topic != TOPIC || event.kind != KIND) return
        eventCount.incrementAndGet()
        lastEventMs = System.currentTimeMillis()
        if (!deltaPending.compareAndSet(false, true)) return
        SoulScope.io.launch {
            delay(COALESCE_MS)
            deltaPending.set(false)
            fetch("realtime")
        }
    }

    private suspend fun fetch(reason: String): String = fetchLock.withLock {
        if (reason == REASON_AUTH && lastSuccessMs > 0L) return "skipped: already loaded"
        val cursor = generation
        val path = if (cursor > 0L) "$ENDPOINT?since=$cursor" else ENDPOINT
        lastAttemptMs = System.currentTimeMillis()
        val result = try {
            BackendClient.get(
                path,
                MarketValuesPayload.serializer(),
                intent = if (cursor > 0L) "market-values:delta" else "market-values:full",
                bypassCache = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            BackendResult.Failure(BackendResult.Failure.TRANSPORT, t.message ?: t.javaClass.simpleName)
        } finally {
            BackendClient.invalidate(ENDPOINT)
        }
        // `if (result is …)`, never `when (result)`: see BackendResult.
        if (result is BackendResult.Ok) {
            val payload = result.value
            val full = cursor == 0L || payload.full
            items = merge(items, payload.items, full)
            generation = payload.generation
            source = "network"
            val now = System.currentTimeMillis()
            lastSuccessMs = now
            lastError = null
            val outcome = if (full) {
                lastFullMs = now
                "full: ${items.size} keys, generation ${payload.generation}"
            } else {
                deltaCount.incrementAndGet()
                lastDeltaMs = now
                lastDeltaKeys = payload.items.size
                "delta: ${payload.items.size} keys, generation ${payload.generation}"
            }
            if (full) logger.info("$ENDPOINT $outcome ($reason)") else logger.debug("$ENDPOINT $outcome ($reason)")
            if (full || now - lastPersistMs >= PERSIST_INTERVAL_MS) persist(now)
            return outcome
        }
        val failure = result as BackendResult.Failure
        lastError = "HTTP ${failure.status}: ${failure.message}"
        // 400 = invalid_since: the cursor is unusable, start over with a full map.
        if (failure.status == 400) generation = 0L
        if (failure.status == BackendResult.Failure.MALFORMED) {
            logger.warn("$ENDPOINT returned a body this build cannot read (map kept): $lastError")
        } else {
            logger.debug("$ENDPOINT fetch failed (map kept, $reason): $lastError")
        }
        return "failed: $lastError"
    }

    /**
     * Apply one response: a full answer replaces the map, a delta replaces the returned entries whole.
     * Always returns a fresh map (or an unchanged one), never mutates [current].
     */
    internal fun merge(
        current: Map<String, MarketValue>,
        changed: Map<String, MarketValue>,
        full: Boolean,
    ): Map<String, MarketValue> {
        if (full) return changed
        if (changed.isEmpty()) return current
        val next = HashMap<String, MarketValue>(((current.size + changed.size) / 0.75f).toInt() + 1)
        next.putAll(current)
        next.putAll(changed)
        return next
    }

    private fun cachePath(): Path = FabricLoader.getInstance().configDir.resolve("soul").resolve(CACHE_FILE)

    private fun loadFromDisk() {
        val path = cachePath()
        if (!Files.isRegularFile(path)) {
            logger.info("No $CACHE_FILE cache: empty until the first fetch")
            return
        }
        try {
            val parsed = json.decodeFromString(MarketValuesPayload.serializer(), Files.readString(path))
            items = parsed.items
            diskGeneration = parsed.generation
            source = "disk"
            logger.info("Loaded $CACHE_FILE from disk: ${parsed.items.size} keys, generation ${parsed.generation}")
        } catch (t: Throwable) {
            lastError = "cache unreadable: ${t.message ?: t.javaClass.simpleName}"
            logger.warn("Could not read $CACHE_FILE, starting empty; the next fetch rewrites it", t)
        }
    }

    private fun persist(now: Long) {
        lastPersistMs = now
        val snapshot = MarketValuesPayload(generation = generation, full = true, items = items)
        SoulScope.io.launch {
            try {
                val path = cachePath()
                Files.createDirectories(path.parent)
                val tmp = path.resolveSibling("$CACHE_FILE.tmp")
                Files.writeString(tmp, json.encodeToString(MarketValuesPayload.serializer(), snapshot))
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (t: Throwable) {
                logger.warn("Failed to persist $CACHE_FILE", t)
            }
        }
    }

    data class Status(
        val keys: Int,
        val source: String,
        val generation: Long,
        val diskGeneration: Long,
        val lastAttemptMs: Long,
        val lastSuccessMs: Long,
        val lastFullMs: Long,
        val lastDeltaMs: Long,
        val lastDeltaKeys: Int,
        val deltas: Long,
        val events: Long,
        val lastEventMs: Long,
        val lastPersistMs: Long,
        val lastError: String?,
        val staleAfterMs: Long,
    )

    fun status(): Status = Status(
        keys = items.size,
        source = source,
        generation = generation,
        diskGeneration = diskGeneration,
        lastAttemptMs = lastAttemptMs,
        lastSuccessMs = lastSuccessMs,
        lastFullMs = lastFullMs,
        lastDeltaMs = lastDeltaMs,
        lastDeltaKeys = lastDeltaKeys,
        deltas = deltaCount.get(),
        events = eventCount.get(),
        lastEventMs = lastEventMs,
        lastPersistMs = lastPersistMs,
        lastError = lastError,
        staleAfterMs = STALE_AFTER_MS,
    )
}
