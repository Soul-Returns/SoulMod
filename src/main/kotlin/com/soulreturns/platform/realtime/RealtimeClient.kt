package com.soulreturns.platform.realtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.platform.http.SoulHttp
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Long-lived Server-Sent Events subscriber to the Mercure hub. Owns a single daemon thread
 * (`soul-realtime`) that loops:
 *
 *   1. Fetch a fresh Mercure JWT + topic list from `/realtime/token`.
 *   2. Open an SSE connection to the hub with the JWT.
 *   3. Read events line-by-line ([MercureSseReader]), parse the JSON envelope, dispatch onto
 *      the in-process [Events] bus.
 *   4. On disconnect / error / 401, sleep with exponential backoff and retry from step 1.
 *
 * **Thread model:** the SSE read blocks on this dedicated thread, never on
 * [SoulExecutor][com.soulreturns.platform.concurrent.SoulExecutor]. The actual HTTP I/O
 * happens on the JDK `HttpClient`'s internal selector threads (managed by `SoulHttp.client`);
 * the executor is only used briefly for response-completion callbacks.
 *
 * **Wire envelope** (per SSE event `data:`):
 * ```
 * { "type": "sync-invalidate" | "notification", "data": { ... } }
 * ```
 *
 * Unknown `type` values are logged and dropped, so the backend can add new message types
 * without a coordinated mod release.
 */
object RealtimeClient {
    private val logger = SoulLogger("Soul/Realtime")

    /** Backoff bounds — clamped per attempt; resets to [MIN_BACKOFF_MS] after a successful connection. */
    private const val MIN_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val thread = AtomicReference<Thread?>(null)

    // ─── Observable state for `/soul dev realtimeStatus` ───
    @Volatile private var lastConnectedAt: Long = 0L

    @Volatile private var lastDisconnectedAt: Long = 0L

    @Volatile private var lastConnectError: String? = null

    @Volatile private var currentHubUrl: String? = null

    @Volatile private var currentTopics: List<String> = emptyList()

    @Volatile private var eventsReceived: Long = 0L

    @Volatile private var lastEventType: String? = null

    @Volatile private var lastEventAt: Long = 0L

    /**
     * Public-read accessor for `/soul dev realtimeStatus`. Snapshots the connection
     * lifecycle so an operator can tell if the mod is connected, what topics it's
     * subscribed to, and whether anything has been received.
     */
    data class Status(
        val started: Boolean,
        val running: Boolean,
        val hubUrl: String?,
        val topics: List<String>,
        val lastConnectedAt: Long,
        val lastDisconnectedAt: Long,
        val lastConnectError: String?,
        val eventsReceived: Long,
        val lastEventType: String?,
        val lastEventAt: Long,
    )

    fun status(): Status =
        Status(
            started = started.get(),
            running = running.get(),
            hubUrl = currentHubUrl,
            topics = currentTopics,
            lastConnectedAt = lastConnectedAt,
            lastDisconnectedAt = lastDisconnectedAt,
            lastConnectError = lastConnectError,
            eventsReceived = eventsReceived,
            lastEventType = lastEventType,
            lastEventAt = lastEventAt,
        )

    fun start(enabled: () -> Boolean) {
        if (!started.compareAndSet(false, true)) return
        if (!enabled()) {
            logger.info("Realtime disabled at startup; engine idle.")
        } else {
            running.set(true)
            val t =
                Thread({ runLoop(enabled) }, "soul-realtime").apply {
                    isDaemon = true
                }
            thread.set(t)
            t.start()
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register(
            ClientLifecycleEvents.ClientStopping { _ -> stop() }
        )
    }

    fun stop() {
        running.set(false)
        thread.getAndSet(null)?.interrupt()
    }

    /**
     * Debug-level info.
     *
     * **Console:** gated on `dev.debug.debugMode AND dev.debug.logging.logRealtime` for noisy
     * connection lifecycle events (connect, disconnect, dispatched message). **File:** always
     * written so the on-disk log retains every realtime trace regardless of toggles.
     *
     * Warnings — real errors — always go through `logger.warn` unconditionally.
     */
    private fun debugInfo(msg: String) {
        val consoleOn =
            try {
                SoulConfigHolder.isConfigReady() &&
                    cfg.dev.debug.debugMode() &&
                    cfg.dev.debug.logging.logRealtime()
            } catch (_: Throwable) {
                false
            }
        if (consoleOn) {
            // SoulLogger.info dispatches both SLF4J console and SoulFileLog tee.
            logger.info(msg)
        } else {
            // Toggles suppress console; file still gets the entry.
            com.soulreturns.util.SoulFileLog.offer("INFO", "Soul/Realtime", msg, null)
        }
    }

    // ───────────────────── loop ─────────────────────

    private fun runLoop(enabled: () -> Boolean) {
        var backoffMs = MIN_BACKOFF_MS
        while (running.get()) {
            if (!enabled()) {
                sleepInterruptibly(MIN_BACKOFF_MS)
                continue
            }
            val token = RealtimeAuth.fetch()
            if (token == null) {
                if (!running.get()) return
                logger.warn("Realtime: failed to fetch token; backing off $backoffMs ms.")
                sleepInterruptibly(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            try {
                connectAndRead(token)
                // Clean disconnect (server closed). Reset backoff and reconnect quickly.
                backoffMs = MIN_BACKOFF_MS
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                // If we were just interrupted for shutdown, the inbound exception (often a
                // wrapped InterruptedException emerging from HttpClient.send / InputStream.read)
                // isn't an error — it's the orderly stop. Suppress the warning.
                if (!running.get() || Thread.currentThread().isInterrupted) return
                logger.warn("Realtime: connection error: ${e.message ?: e.javaClass.simpleName}")
            }
            if (!running.get()) return
            sleepInterruptibly(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun connectAndRead(token: RealtimeAuth.Token) {
        if (token.topics.isEmpty()) {
            logger.warn("Realtime: backend issued zero topics; skipping connection.")
            throw IllegalStateException("no topics")
        }
        val uri = buildSubscribeUri(token.hubUrl, token.topics)
        val request =
            HttpRequest.newBuilder()
                .GET()
                .uri(uri)
                .header("Authorization", "Bearer ${token.jwt}")
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", SoulHttp.userAgent())
                .build()

        currentHubUrl = token.hubUrl
        currentTopics = token.topics
        debugInfo("Realtime: connecting to $uri (${token.topics.size} topic(s))")
        val response: HttpResponse<java.io.InputStream> =
            try {
                SoulHttp.client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: Exception) {
                lastConnectError = "send: ${e.javaClass.simpleName}: ${e.message}"
                throw e
            }
        if (response.statusCode() !in 200..299) {
            lastConnectError = "HTTP ${response.statusCode()}"
            throw java.io.IOException("Mercure returned HTTP ${response.statusCode()}")
        }
        lastConnectError = null
        lastConnectedAt = System.currentTimeMillis()
        debugInfo("Realtime: connected.")
        BufferedReader(InputStreamReader(response.body(), StandardCharsets.UTF_8)).use { reader ->
            MercureSseReader.readLoop(reader) { event -> dispatch(event.data) }
        }
        lastDisconnectedAt = System.currentTimeMillis()
        debugInfo("Realtime: server closed connection.")
    }

    private fun buildSubscribeUri(
        hubUrl: String,
        topics: List<String>,
    ): URI {
        val qs =
            topics.joinToString("&") { topic ->
                "topic=" + URLEncoder.encode(topic, StandardCharsets.UTF_8)
            }
        val sep = if (hubUrl.contains('?')) '&' else '?'
        return URI.create("$hubUrl$sep$qs")
    }

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ───────────────────── dispatch ─────────────────────

    private fun dispatch(data: String) {
        val obj: JsonObject =
            try {
                JsonParser.parseString(data).asJsonObject
            } catch (e: Exception) {
                logger.warn("Realtime: dropped non-JSON payload (${e.javaClass.simpleName})")
                return
            }
        val type = obj.get("type")?.asString
        if (type.isNullOrBlank()) {
            logger.warn("Realtime: dropped payload with no type field: $obj")
            return
        }
        val payload =
            obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        eventsReceived++
        lastEventType = type
        lastEventAt = System.currentTimeMillis()
        debugInfo("Realtime: dispatched type='$type'")
        when (type) {
            "sync-invalidate" -> {
                val kind = payload.get("kind")?.asString ?: return
                Events.publish(SyncInvalidate(kind))
            }

            "notification" -> {
                val message = payload.get("message")?.asString ?: return
                val severity = payload.get("severity")?.asString ?: "info"
                Events.publish(BackendNotification(message, severity))
            }

            else -> debugInfo("Realtime: ignoring unknown type '$type'")
        }
    }

    /** Duration constant exposed for tests / debug; not used directly. */
    @Suppress("unused")
    private val connectTimeout: Duration = Duration.ofSeconds(15)
}
