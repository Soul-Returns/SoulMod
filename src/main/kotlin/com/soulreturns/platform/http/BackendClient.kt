package com.soulreturns.platform.http

import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * The outcome of a backend call. Deliberately a sealed hierarchy rather than a nullable return so
 * a caller cannot silently confuse "the backend said no" with "there is nothing there".
 *
 * Match with `if (result is BackendResult.Ok)` — **never** `when (result)`: Kotlin emits a synthetic
 * `$WhenMappings` class for a subject `when` over a sealed/enum type and Fabric's `KnotClassLoader`
 * can fail to resolve it at runtime as a deferred `NoClassDefFoundError` (see CLAUDE.md).
 */
sealed interface BackendResult<out T> {
    /**
     * @property fromCache true when this was served from the response cache without a request.
     * @property expiresAt when the cached copy stops being valid; `Instant.EPOCH` for an
     *   uncacheable response (`X-Backend-Expire-In: 0`, or the header absent).
     */
    data class Ok<T>(val value: T, val fromCache: Boolean, val expiresAt: Instant) : BackendResult<T>

    /**
     * @property status the HTTP status, or one of the synthetic codes below when the request never
     *   produced one: [TRANSPORT] (connect/read failure), [TIMEOUT], [NO_AUTH] (no bearer could be
     *   obtained), [MALFORMED] (2xx whose body did not deserialize), [ALPHA_BLOCKED] (refused before
     *   sending: the connection is on the Hypixel Alpha network, see [AlphaGate]).
     * @property retryAfter honoured `Retry-After` on a 429, else null.
     */
    data class Failure(val status: Int, val message: String, val retryAfter: Duration? = null) :
        BackendResult<Nothing> {
        companion object {
            const val TRANSPORT = -1
            const val TIMEOUT = -2
            const val NO_AUTH = -3
            const val MALFORMED = -4
            const val ALPHA_BLOCKED = -5
        }
    }
}

/** Convenience: the value on success, null on any failure. */
fun <T> BackendResult<T>.valueOrNull(): T? = if (this is BackendResult.Ok) value else null

/**
 * The shared, async, caching front door to the Soul backend. Every feature that reads or writes
 * backend state goes through here rather than through [SoulHttp] directly, so caching, retries,
 * auth refresh, request coalescing and usage attribution are solved exactly once.
 *
 * Layers below it: [SoulHttp] (base URL + shared JDK `HttpClient`) and [BackendAuth] (the bearer).
 *
 * ### Caching
 * Driven **only** by the `X-Backend-Expire-In` response header, whose value is in **milliseconds**.
 * An **absent** header means *do not cache*. The legacy mod defaulted to 60 s here, which silently
 * cached any endpoint whose author forgot the header — inverting the default turns that mistake
 * from a stale-data bug into a lost optimization. Entries are keyed by (endpoint, player uuid), so
 * an account switch mid-session never serves the previous account's data.
 *
 * ### Retries
 * - `429` — honour `Retry-After`; the endpoint is put on cooldown and further calls fail fast
 *   without touching the network until it expires. Never retried inside the window.
 * - `5xx` / transport error — one retry after 500 ms ± jitter, **GET only** (a failed POST may
 *   still have been applied server-side).
 * - `401` — clear the bearer, force **one** re-auth, retry once. Never more: `/authenticate` allows
 *   only 5 successful auths per uuid per hour, and [BackendAuth]'s own cooldowns are what keep a
 *   failing session from burning that budget. Do not add a second forced refresh here.
 * - any other `4xx` — no retry; the request itself is wrong.
 *
 * ### Single-flight
 * Concurrent identical GETs share one in-flight request. A burst of realtime invalidates therefore
 * produces one refetch, not one per event.
 *
 * ### Hypixel Alpha
 * While [com.soulreturns.api.HypixelNetwork.isAlpha], [AlphaGate] refuses every request it does not
 * allow before cache, cooldown or network ([BackendResult.Failure.ALPHA_BLOCKED], never retried).
 */
object BackendClient {
    private val logger = SoulLogger("Soul/Backend")

    /** The backend's contract is explicitly additive-only, so unknown keys are normal. */
    /**
     * `ignoreUnknownKeys` because the backend contract is additive-only, and `coerceInputValues`
     * because it is the same tolerance applied to the other half of the problem.
     *
     * A kotlinx default covers a key that is **absent**. It does not cover a key that is present and
     * explicitly `null` against a non-nullable property — that throws, and it fails the whole
     * response, not the one field. `/items` hit exactly this: `npcSellPrice` is null on well over half
     * of Hypixel's catalog, so a single row cost all 5651 of them. Coercion substitutes the declared
     * default instead, so a column the backend makes nullable later degrades one value rather than
     * taking a feature down.
     *
     * It coerces null to a declared default and nothing else — a type mismatch or malformed JSON
     * still fails loudly, which is what [BackendResult.Failure.MALFORMED] is for.
     */
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Default ceiling for a whole call including its retry. The JDK request timeout is 15 s. */
    private const val DEFAULT_TIMEOUT_MS = 35_000L

    private const val RETRY_DELAY_MS = 500L

    /** Cooldown applied to a 429 that arrived without a usable `Retry-After`. */
    private const val DEFAULT_RATE_LIMIT_COOLDOWN = 60L

    // ------------------------------------------------------------------ state

    private class CacheEntry(val body: String, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Raw>>()

    /** endpoint → the instant its rate-limit window ends. */
    private val cooldowns = ConcurrentHashMap<String, Instant>()

    /** endpoint → the last failure seen on it, for `/soul dev backend`. */
    private val lastErrors = ConcurrentHashMap<String, String>()

    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val requests = AtomicLong()
    private val failures = AtomicLong()

    data class Status(
        val cacheEntries: Int,
        val cacheHits: Long,
        val cacheMisses: Long,
        val requests: Long,
        val failures: Long,
        /** Requests [AlphaGate] refused this session. */
        val alphaRefused: Long,
        val inFlight: Int,
        /** endpoint → when its rate-limit cooldown ends (only entries still in the future). */
        val cooldowns: Map<String, Instant>,
        val lastErrors: Map<String, String>,
    )

    fun status(): Status {
        val now = Instant.now()
        return Status(
            cacheEntries = cache.size,
            cacheHits = cacheHits.get(),
            cacheMisses = cacheMisses.get(),
            requests = requests.get(),
            failures = failures.get(),
            alphaRefused = AlphaGate.refused,
            inFlight = inFlight.size,
            cooldowns = cooldowns.filterValues { it.isAfter(now) },
            lastErrors = lastErrors.toMap(),
        )
    }

    // ------------------------------------------------------------------ public API

    /**
     * `GET [endpoint]`, deserialized with [serializer].
     *
     * @param endpoint path only, e.g. `/sea-creatures` (a leading slash is optional).
     * @param intent the `X-Intent` header. **Required** — the backend's `UsageRecorder` stores it and
     *   the admin dashboard groups by it, so an unlabelled call is invisible when diagnosing load.
     * @param bypassCache skip the cache for this call (the response is still cached for others).
     *   Catalog clients that keep their own snapshot use this so an HTTP cache hit can't mask a
     *   realtime-driven refresh.
     */
    suspend fun <T> get(
        endpoint: String,
        serializer: KSerializer<T>,
        intent: String,
        bypassCache: Boolean = false,
        authenticated: Boolean = true,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): BackendResult<T> {
        val path = normalize(endpoint)
        AlphaGate.refusal("GET", path)?.let { return it }
        val key = cacheKey(path)

        if (!bypassCache) {
            val hit = cache[key]?.takeIf { Instant.now().isBefore(it.expiresAt) }
            if (hit != null) {
                cacheHits.incrementAndGet()
                return decode(path, hit.body, serializer, fromCache = true, expiresAt = hit.expiresAt)
            }
        }
        cacheMisses.incrementAndGet()

        val raw = fetchCoalesced(key, timeoutMs) {
            request("GET", path, body = null, intent = intent, authenticated = authenticated)
        } ?: return BackendResult.Failure(BackendResult.Failure.TIMEOUT, "timed out after ${timeoutMs}ms")

        if (raw.status !in 200..299) return failure(path, raw)

        val expiresAt = raw.expiresAt()
        if (expiresAt.isAfter(Instant.now())) cache[key] = CacheEntry(raw.body, expiresAt)
        return decode(path, raw.body, serializer, fromCache = false, expiresAt = expiresAt)
    }

    /** `POST [endpoint]` with a serialized [body]; the response body is parsed as a JSON object. */
    suspend fun <B> post(
        endpoint: String,
        body: B,
        bodySerializer: KSerializer<B>,
        intent: String,
        authenticated: Boolean = true,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): BackendResult<JsonObject> {
        val path = normalize(endpoint)
        AlphaGate.refusal("POST", path)?.let { return it }
        val encoded = try {
            json.encodeToString(bodySerializer, body)
        } catch (t: Throwable) {
            return BackendResult.Failure(BackendResult.Failure.MALFORMED, "could not encode body: ${t.message}")
        }
        // POSTs are not coalesced (that would drop writes), but they still must not run their
        // blocking send on whatever dispatcher the caller happened to be on.
        val raw = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { request("POST", path, encoded, intent, authenticated) }
        } ?: return BackendResult.Failure(BackendResult.Failure.TIMEOUT, "timed out after ${timeoutMs}ms")

        if (raw.status !in 200..299) return failure(path, raw)
        if (raw.body.isBlank()) return BackendResult.Ok(JsonObject(emptyMap()), fromCache = false, Instant.EPOCH)
        return try {
            BackendResult.Ok(json.parseToJsonElement(raw.body) as JsonObject, fromCache = false, Instant.EPOCH)
        } catch (t: Throwable) {
            fail(path, "malformed response: ${t.message}")
            BackendResult.Failure(BackendResult.Failure.MALFORMED, "response was not a JSON object")
        }
    }

    /** `POST [endpoint]` for the 204 endpoints (`/dungeon/sync`, `/presence/state`, …). */
    suspend fun <B> postUnit(
        endpoint: String,
        body: B,
        bodySerializer: KSerializer<B>,
        intent: String,
        authenticated: Boolean = true,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): BackendResult<Unit> {
        val result = post(endpoint, body, bodySerializer, intent, authenticated, timeoutMs)
        if (result is BackendResult.Ok) return BackendResult.Ok(Unit, fromCache = false, Instant.EPOCH)
        return result as BackendResult.Failure
    }

    /** What [download] wrote: byte count and the lowercase hex sha256 of exactly those bytes. */
    data class Downloaded(val bytes: Long, val sha256: String)

    /**
     * Streams `GET [endpoint]` into [target] (created or truncated), hashing on the fly. Never
     * cached, never coalesced, never authenticated (the only caller is the public update jar).
     *
     * Honours the shared 429 cooldown like [get]; a transport error or 5xx is retried once (the
     * file is rewritten from scratch). A body longer than [maxBytes], or a `Content-Length` above it,
     * fails without writing past the limit. On failure [target] may hold a partial file; the caller
     * deletes it.
     *
     * @param onProgress (bytesSoFar, totalOrMinus1) after each chunk, on the download thread.
     */
    suspend fun download(
        endpoint: String,
        target: java.nio.file.Path,
        intent: String,
        maxBytes: Long,
        timeoutMs: Long = 10 * 60_000L,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): BackendResult<Downloaded> {
        val path = normalize(endpoint)
        AlphaGate.refusal("GET", path)?.let { return it }
        cooldowns[path]?.let { until ->
            val remaining = Duration.between(Instant.now(), until)
            if (!remaining.isNegative && !remaining.isZero) {
                return BackendResult.Failure(429, "rate limited — ${remaining.seconds}s remaining", remaining)
            }
            cooldowns.remove(path)
        }
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                var result = streamOnce(path, target, intent, maxBytes, onProgress)
                if (result is BackendResult.Failure && (result.status >= 500 || result.status == BackendResult.Failure.TRANSPORT)) {
                    delay(RETRY_DELAY_MS + Random.nextLong(0, 250))
                    result = streamOnce(path, target, intent, maxBytes, onProgress)
                }
                if (result is BackendResult.Failure) {
                    if (result.status == 429) {
                        val seconds = result.retryAfter?.seconds?.takeIf { it > 0 } ?: DEFAULT_RATE_LIMIT_COOLDOWN
                        cooldowns[path] = Instant.now().plusSeconds(seconds)
                    }
                    fail(path, result.message)
                }
                result
            }
        } ?: BackendResult.Failure(BackendResult.Failure.TIMEOUT, "timed out after ${timeoutMs}ms")
    }

    private suspend fun streamOnce(
        path: String,
        target: java.nio.file.Path,
        intent: String,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): BackendResult<Downloaded> {
        requests.incrementAndGet()
        val response = try {
            SoulHttp.stream(SoulHttp.backendBaseUrl() + path, mapOf("X-Intent" to intent))
        } catch (t: Throwable) {
            return BackendResult.Failure(BackendResult.Failure.TRANSPORT, "${t.javaClass.simpleName}: ${t.message ?: "no message"}")
        }
        response.body().use { input ->
            val status = response.statusCode()
            if (status !in 200..299) {
                val snippet = input.readNBytes(160).toString(Charsets.UTF_8).replace('\n', ' ').trim()
                val retryAfter = response.headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.let { Duration.ofSeconds(it) }
                return BackendResult.Failure(status, "HTTP $status" + if (snippet.isEmpty()) "" else " — $snippet", retryAfter)
            }
            val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            if (total > maxBytes) return BackendResult.Failure(BackendResult.Failure.MALFORMED, "body of $total bytes exceeds $maxBytes")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var written = 0L
            try {
                java.nio.file.Files.newOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > maxBytes) return BackendResult.Failure(BackendResult.Failure.MALFORMED, "body exceeds $maxBytes bytes")
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        onProgress(written, total)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                return BackendResult.Failure(BackendResult.Failure.TRANSPORT, "${t.javaClass.simpleName}: ${t.message ?: "no message"}")
            }
            return BackendResult.Ok(Downloaded(written, java.util.HexFormat.of().formatHex(digest.digest())), fromCache = false, Instant.EPOCH)
        }
    }

    /**
     * Blocking bridge for the few call sites that genuinely cannot suspend — the `/soul dev backend`
     * probe on its own thread, and shutdown flushes. **Never call this on the client thread**: it
     * parks the caller until the request completes.
     */
    fun <T> getBlocking(
        endpoint: String,
        serializer: KSerializer<T>,
        intent: String,
        bypassCache: Boolean = false,
        authenticated: Boolean = true,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): BackendResult<T> = runBlocking { get(endpoint, serializer, intent, bypassCache, authenticated, timeoutMs) }

    /** Drop every cached response whose endpoint starts with [prefix] (`""` clears everything). */
    fun invalidate(prefix: String) {
        val needle = if (prefix.isEmpty()) "" else normalize(prefix)
        val removed = cache.keys.filter { it.substringAfter('|', it).startsWith(needle) }
        removed.forEach { cache.remove(it) }
        if (removed.isNotEmpty()) logger.debug("Invalidated ${removed.size} cache entr(ies) for '$needle'")
    }

    /** Forget everything: cache, cooldowns, error history. Used when the backend URL changes. */
    fun reset() {
        cache.clear()
        cooldowns.clear()
        lastErrors.clear()
    }

    // ------------------------------------------------------------------ internals

    /**
     * The raw HTTP outcome, before deserialization. [expireInMs] is null when the response carried
     * no `X-Backend-Expire-In` header at all — which means *do not cache*, same as `0`.
     */
    private class Raw(
        val status: Int,
        val body: String,
        val expireInMs: Long?,
        val retryAfter: Duration?,
        val transportError: String? = null,
        val bearer: String? = null,
    ) {
        fun expiresAt(): Instant {
            val ms = expireInMs ?: return Instant.EPOCH
            return if (ms <= 0) Instant.EPOCH else Instant.now().plusMillis(ms)
        }

        /** Human-readable reason this response is a failure. */
        fun failureMessage(): String {
            transportError?.let { return it }
            val snippet = body.take(160).replace('\n', ' ').trim()
            return "HTTP $status" + if (snippet.isEmpty()) "" else " — $snippet"
        }
    }

    /** Share one in-flight request between concurrent identical calls. */
    private suspend fun fetchCoalesced(key: String, timeoutMs: Long, block: suspend () -> Raw): Raw? {
        val deferred = inFlight.computeIfAbsent(key) {
            SoulScope.io.async { block() }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    /**
     * One logical request: cooldown gate → send → retry policy. Always returns a [Raw]; it never
     * throws, so the shared [Deferred] can be awaited by several callers safely.
     */
    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        intent: String,
        authenticated: Boolean,
    ): Raw {
        cooldowns[path]?.let { until ->
            val remaining = Duration.between(Instant.now(), until)
            if (!remaining.isNegative && !remaining.isZero) {
                return Raw(429, "", null, remaining, "rate limited — ${remaining.seconds}s remaining")
            }
            cooldowns.remove(path)
        }

        var attempt = send(method, path, body, intent, authenticated, rejectedBearer = null)

        // 401 → the bearer is stale. Clear it, force exactly one re-auth, retry once.
        if (attempt.status == 401 && authenticated && attempt.bearer != null) {
            logger.debug("$path returned 401 — forcing one re-auth")
            attempt = send(method, path, body, intent, authenticated, rejectedBearer = attempt.bearer)
        } else if (method == "GET" && (attempt.status >= 500 || attempt.status == BackendResult.Failure.TRANSPORT)) {
            // Only GET is retried on a server/transport failure. A POST that failed at the transport
            // may still have been applied, and this layer cannot know whether the endpoint is
            // idempotent — re-sending it is the caller's decision, not ours. (The 401 path above is
            // different: a rejected request provably did nothing.)
            delay(RETRY_DELAY_MS + Random.nextLong(0, 250))
            attempt = send(method, path, body, intent, authenticated, rejectedBearer = null)
        }

        if (attempt.status == 429) {
            val seconds = attempt.retryAfter?.seconds?.takeIf { it > 0 } ?: DEFAULT_RATE_LIMIT_COOLDOWN
            cooldowns[path] = Instant.now().plusSeconds(seconds)
            logger.warn("$path rate limited — backing off ${seconds}s")
        }
        return attempt
    }

    private fun send(
        method: String,
        path: String,
        body: String?,
        intent: String,
        authenticated: Boolean,
        rejectedBearer: String?,
    ): Raw {
        val headers = HashMap<String, String>()
        headers["X-Intent"] = intent
        var bearer: String? = null
        if (authenticated) {
            bearer = (if (rejectedBearer != null) BackendAuth.reauthenticate(rejectedBearer) else BackendAuth.ensureAuthenticated())
                ?: return Raw(
                    BackendResult.Failure.NO_AUTH, "", null, null,
                    "no bearer token (auth failed or is cooling down)",
                )
            headers["Authorization"] = bearer
        }
        if (body != null) headers["Content-Type"] = "application/json"

        val url = SoulHttp.backendBaseUrl() + path
        requests.incrementAndGet()
        return try {
            val response = if (body == null) SoulHttp.get(url, headers) else SoulHttp.post(url, body, headers)
            Raw(
                status = response.statusCode(),
                body = response.body() ?: "",
                expireInMs = longHeader(response, "X-Backend-Expire-In"),
                retryAfter = longHeader(response, "Retry-After")?.let { Duration.ofSeconds(it) },
                bearer = bearer,
            )
        } catch (t: Throwable) {
            Raw(
                BackendResult.Failure.TRANSPORT, "", null, null,
                "${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                bearer,
            )
        }
    }

    private fun longHeader(response: HttpResponse<String>, name: String): Long? =
        response.headers().firstValue(name).orElse(null)?.trim()?.toLongOrNull()

    private fun <T> decode(
        path: String,
        body: String,
        serializer: KSerializer<T>,
        fromCache: Boolean,
        expiresAt: Instant,
    ): BackendResult<T> = try {
        BackendResult.Ok(json.decodeFromString(serializer, body), fromCache, expiresAt)
    } catch (t: Throwable) {
        // A body we cannot read is not a usable cache entry, whatever the header said.
        cache.remove(cacheKey(path))
        val message = "malformed body: ${t.message ?: t.javaClass.simpleName}"
        fail(path, message)
        BackendResult.Failure(BackendResult.Failure.MALFORMED, message)
    }

    /** Record [raw] as this endpoint's latest failure and turn it into a [BackendResult.Failure]. */
    private fun failure(path: String, raw: Raw): BackendResult.Failure {
        val message = raw.failureMessage()
        fail(path, message)
        return BackendResult.Failure(raw.status, message, raw.retryAfter)
    }

    private fun fail(path: String, message: String) {
        failures.incrementAndGet()
        lastErrors[path] = message
        logger.debug("$path failed: $message")
    }

    private fun normalize(endpoint: String): String =
        if (endpoint.startsWith("/")) endpoint else "/$endpoint"

    /**
     * Cache key. The player uuid is part of it because `/player/{uuid}/soul-stats`,
     * `/skyblock/profiles` and friends are per-player and DevAuth makes an account switch
     * mid-session possible.
     */
    private fun cacheKey(path: String): String {
        val uuid = try {
            Minecraft.getInstance().user?.profileId?.toString()
        } catch (_: Throwable) {
            null
        }
        return "${uuid ?: "anon"}|$path"
    }
}
