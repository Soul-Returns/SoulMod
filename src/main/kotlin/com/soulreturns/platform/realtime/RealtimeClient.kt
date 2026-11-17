package com.soulreturns.platform.realtime

import com.soulreturns.api.HypixelNetwork
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendAuth
import com.soulreturns.platform.http.SoulHttp
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.SyncLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The mod's single connection to the backend's realtime channel.
 *
 * One long-lived Mercure SSE stream, subscribed to **every topic `GET /realtime/token` returns**,
 * feeding a type-keyed dispatch registry. Before this existed the mod opened one stream from inside
 * the dungeon secret sync, on one concrete topic, only during a run — so `sync-invalidate` and
 * `notification`, both of which the backend already publishes, were unreachable by construction.
 *
 * ### Thread model
 * One daemon thread (`soul-realtime`) owns the connection and blocks on the read. That is the one
 * deliberate exception to "background work belongs to [SoulScope]": a permanently-parked reader is
 * not a task. [SoulScope] is still used for the timed JWT re-mint.
 *
 * Handlers therefore run **on the realtime thread**. Anything touching the client (screens, HUD
 * state, chat) must hop across with `Minecraft.getInstance().execute { }`.
 *
 * ### Lifecycle
 * Connects once the player is on a `hypixel.net` server and a bearer is obtainable; disconnects on
 * server leave and on `CLIENT_STOPPING`. There is no point holding a stream open on the title
 * screen, and the gate makes the connection lifecycle observable. `dev.realtimeAlwaysOn` lifts the
 * Hypixel half of the gate for local backend work. The Hypixel Alpha network closes it regardless.
 *
 * ### Topics
 * The token's topics are subscribed to verbatim, never filtered or rewritten. [requestTopic] adds a
 * concrete topic beyond them (and reconnects to pick it up) for the case where the hub will not
 * accept a URI template as a *subscribe selector* — the template is definitely honoured in the
 * JWT's `mercure.subscribe` claim, which is a different question.
 *
 * ### Delivery is best-effort
 * Publishing is fire-and-forget on the backend side and the hub does not replay what was missed
 * during a reconnect gap. **No consumer may depend on an event arriving.** [Last-Event-ID] is sent
 * on reconnect so that a hub configured with a history transport would replay — harmless without one.
 */
object RealtimeClient {
    private val logger = SoulLogger("Soul/Realtime")
    private val json = Json { ignoreUnknownKeys = true }

    private const val MIN_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L

    /** Re-mint this long before the JWT's `exp` rather than waiting for the hub to drop us. */
    private val REMINT_MARGIN: Duration = Duration.ofHours(1)

    /** Fallback re-mint interval when the JWT carries no readable `exp` (backend TTL is 24 h). */
    private val REMINT_FALLBACK: Duration = Duration.ofHours(23)

    /** How often the gate is re-evaluated, in client ticks (20/s). */
    private const val RECONCILE_TICKS = 100

    // ------------------------------------------------------------------ dispatch registry

    /** One realtime message, already unwrapped from the SSE frame and the backend's envelope. */
    data class RealtimeEvent(
        val type: String,
        /** The topic it was published on, when the backend tells us; null otherwise. */
        val topic: String?,
        val data: JsonObject,
        /** The SSE `id:` field, if any — also what we replay from on reconnect. */
        val id: String?,
    )

    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(RealtimeEvent) -> Unit>>()

    /**
     * Route every event of [type] to [handler]. Types with no handler are dropped silently (at
     * debug) — the backend adds event types without a coordinated mod release and relies on that.
     *
     * [handler] runs on the realtime thread; keep it short and hop to the client thread for
     * anything that renders.
     */
    fun onRealtimeEvent(type: String, handler: (RealtimeEvent) -> Unit) {
        handlers.computeIfAbsent(type) { CopyOnWriteArrayList() }.add(handler)
    }

    // ------------------------------------------------------------------ state

    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var hubUrl: String? = null
    @Volatile private var currentTopics: List<String> = emptyList()
    @Volatile private var lastError: String? = null
    @Volatile private var connectedSince: Instant? = null
    @Volatile private var lastEventType: String? = null
    @Volatile private var lastEventAt: Instant? = null
    @Volatile private var lastEventId: String? = null
    @Volatile private var jwtExpiresAt: Instant? = null

    /** Set just before we close the stream ourselves, so the read failure is not reported as an error. */
    @Volatile private var deliberateClose = false

    private val eventsReceived = AtomicLong()
    private val thread = AtomicReference<Thread?>(null)

    /**
     * Bumped by every [start]/[stop]. The read loop carries the generation it was started with and
     * exits the moment it no longer matches, so a stop-then-start that outruns the old thread's own
     * `running` check can never leave two readers on the wire.
     */
    private val generation = AtomicLong()
    private val stream = AtomicReference<InputStream?>(null)
    private val remintJob = AtomicReference<Job?>(null)

    /** Concrete topics requested by features on top of the token's own list. */
    private val extraTopics = CopyOnWriteArrayList<String>()

    private var registered = false
    private var tickCounter = 0

    data class Status(
        val running: Boolean,
        val connected: Boolean,
        val gate: String,
        val hubUrl: String?,
        val topics: List<String>,
        val connectedSince: Instant?,
        val eventsReceived: Long,
        val lastEventType: String?,
        val lastEventAt: Instant?,
        val lastEventId: String?,
        val jwtExpiresAt: Instant?,
        val lastError: String?,
        val handledTypes: List<String>,
    )

    fun status(): Status = Status(
        running = running,
        connected = connected,
        gate = gateReason(),
        hubUrl = hubUrl,
        topics = currentTopics,
        connectedSince = connectedSince,
        eventsReceived = eventsReceived.get(),
        lastEventType = lastEventType,
        lastEventAt = lastEventAt,
        lastEventId = lastEventId,
        jwtExpiresAt = jwtExpiresAt,
        lastError = lastError ?: RealtimeAuth.lastError,
        handledTypes = handlers.keys.toList().sorted(),
    )

    // ------------------------------------------------------------------ lifecycle

    /**
     * Wire the lifecycle and register the built-in event bridges. Does not connect — [reconcile]
     * does that once the gate opens.
     */
    fun register() {
        if (registered) return
        registered = true

        onRealtimeEvent("sync-invalidate") { event ->
            val kind = event.data["kind"]?.jsonPrimitive?.content
            if (kind.isNullOrBlank()) {
                logger.warn("sync-invalidate with no kind: ${event.data}")
            } else {
                Events.publish(SyncInvalidate(event.topic, kind))
            }
        }
        onRealtimeEvent("notification") { event ->
            val message = event.data["message"]?.jsonPrimitive?.content
            if (message.isNullOrBlank()) {
                logger.warn("notification with no message: ${event.data}")
            } else {
                val severity = event.data["severity"]?.jsonPrimitive?.content ?: "info"
                Events.publish(BackendNotification(message, severity))
            }
        }
        // Entering Alpha closes the gate now rather than at the next slow-tick reconcile.
        Events.subscribe<HypixelNetwork.AlphaDetected> { reconcile() }

        ClientPlayConnectionEvents.JOIN.register(
            ClientPlayConnectionEvents.Join { _, _, _ -> reconcile() },
        )
        ClientPlayConnectionEvents.DISCONNECT.register(
            ClientPlayConnectionEvents.Disconnect { _, _ -> stop("left the server") },
        )
        ClientLifecycleEvents.CLIENT_STOPPING.register(
            ClientLifecycleEvents.ClientStopping { stop("client stopping") },
        )
        // The join event alone would miss the mod being enabled mid-session, the config URL being
        // changed, or auth only becoming possible later. Re-check on a slow tick as well.
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (++tickCounter % RECONCILE_TICKS == 0) reconcile()
        }
    }

    /** Open or close the connection to match the gate. Idempotent. */
    fun reconcile() {
        val want = shouldConnect()
        if (want && !running) {
            start()
        } else if (!want && running) {
            stop(gateReason())
        }
    }

    /**
     * Close and re-open the stream, e.g. because the topic set changed or the JWT is near expiry.
     * Cheap: the read loop treats it as a clean close and reconnects without backoff.
     */
    fun reconnect(reason: String) {
        if (!running) return
        SyncLog.ws("realtime", "reconnect — $reason")
        deliberateClose = true
        closeStream()
    }

    /**
     * Subscribe to [topic] in addition to the token's own topics. Triggers a reconnect the first
     * time a topic is added. Idempotent; pair with [releaseTopic].
     */
    fun requestTopic(topic: String) {
        if (topic.isBlank() || extraTopics.contains(topic)) return
        extraTopics.add(topic)
        reconnect("subscribe $topic")
    }

    /** Drop a topic added by [requestTopic]. */
    fun releaseTopic(topic: String) {
        if (!extraTopics.remove(topic)) return
        reconnect("unsubscribe $topic")
    }

    private fun start() {
        if (running) return
        val gen = generation.incrementAndGet()
        running = true
        val t = Thread({ runLoop(gen) }, "soul-realtime").apply { isDaemon = true }
        thread.set(t)
        t.start()
        SyncLog.ws("realtime", "starting — ${gateReason()}")
    }

    private fun stop(reason: String) {
        if (!running && thread.get() == null) return
        SyncLog.ws("realtime", "stopping — $reason")
        generation.incrementAndGet()
        running = false
        connected = false
        connectedSince = null
        remintJob.getAndSet(null)?.cancel()
        closeStream()
        thread.getAndSet(null)?.interrupt()
    }

    private fun closeStream() {
        val s = stream.getAndSet(null) ?: return
        try {
            s.close()
        } catch (_: Throwable) {
            // Closing to break the read is the whole point; whatever it throws is noise.
        }
    }

    // ------------------------------------------------------------------ gate

    /**
     * Connect only while the player is on Hypixel. `dev.realtimeAlwaysOn` lifts that for local
     * backend work (singleplayer, a test server), as does the test harness's test bearer — the
     * bearer is still required either way.
     */
    private fun shouldConnect(): Boolean {
        // The stream is inbound, but its events trigger requests (island-chest refetches): Alpha is off Hypixel.
        if (HypixelNetwork.isAlpha) return false
        val alwaysOn = try {
            cfg.dev.realtimeAlwaysOn
        } catch (_: Throwable) {
            false
        }
        if (alwaysOn || BackendAuth.testBearerActive()) return Minecraft.getInstance().user != null
        return onHypixel()
    }

    private fun onHypixel(): Boolean {
        val ip = Minecraft.getInstance().currentServer?.ip ?: return false
        return ip.contains("hypixel.net", ignoreCase = true)
    }

    private fun gateReason(): String {
        val alwaysOn = try {
            cfg.dev.realtimeAlwaysOn
        } catch (_: Throwable) {
            false
        }
        return when {
            HypixelNetwork.isAlpha -> "on the Hypixel Alpha network (no backend traffic from there)"
            (alwaysOn || BackendAuth.testBearerActive()) && Minecraft.getInstance().user == null -> "no session"
            alwaysOn -> "dev.realtimeAlwaysOn"
            BackendAuth.testBearerActive() -> "test bearer (-Dsoul.testBearerToken)"
            onHypixel() -> "on hypixel.net"
            else -> "not on hypixel.net (enable dev.realtimeAlwaysOn to override)"
        }
    }

    // ------------------------------------------------------------------ the loop

    private fun runLoop(gen: Long) {
        fun alive(): Boolean = running && generation.get() == gen

        var backoff = MIN_BACKOFF_MS
        while (alive()) {
            val token = RealtimeAuth.fetch()
            if (token == null) {
                if (!alive()) return
                lastError = RealtimeAuth.lastError ?: "no realtime token"
                if (!sleep(backoff)) return
                backoff = grow(backoff)
                continue
            }
            try {
                connectAndRead(token)
                backoff = MIN_BACKOFF_MS // clean close → reconnect promptly
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                // An interrupt during shutdown surfaces here as a wrapped IOException from
                // HttpClient/InputStream. That is the orderly stop, not a fault — say nothing.
                if (!alive() || Thread.currentThread().isInterrupted) return
                if (deliberateClose) {
                    deliberateClose = false
                    continue // our own reconnect: no error, no backoff
                }
                lastError = "${e.javaClass.simpleName}: ${describe(e)}"
                logger.warn("Realtime connection error: $lastError")
                SyncLog.ws("realtime", "connection error: $lastError")
            }
            if (deliberateClose) {
                deliberateClose = false
                continue
            }
            if (!alive() || !sleep(backoff)) return
            backoff = grow(backoff)
        }
    }

    private fun connectAndRead(token: RealtimeToken) {
        val topics = (token.topics + extraTopics).distinct()
        if (topics.isEmpty()) throw IllegalStateException("backend issued no topics")

        val uri = subscribeUri(token.hubUrl, topics)
        val builder = HttpRequest.newBuilder()
            .GET()
            .uri(uri)
            // No request timeout on purpose: this response is a stream that must live for hours.
            .header("Authorization", "Bearer ${token.jwt}")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", SoulHttp.userAgent())
        lastEventId?.let { builder.header("Last-Event-ID", it) }

        hubUrl = token.hubUrl
        currentTopics = topics
        jwtExpiresAt = jwtExpiry(token.jwt)
        SyncLog.ws("realtime", "connecting: $uri")

        val response: HttpResponse<InputStream> =
            SoulHttp.client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw IOException("hub returned HTTP ${response.statusCode()}")
        }

        val body = response.body()
        stream.set(body)
        connected = true
        connectedSince = Instant.now()
        lastError = null
        scheduleRemint()
        logger.info("Realtime connected — ${topics.size} topic(s) on ${token.hubUrl}")
        SyncLog.ws("realtime", "connected (${topics.joinToString()})")
        try {
            BufferedReader(InputStreamReader(body, StandardCharsets.UTF_8)).use { reader ->
                MercureSseReader.readLoop(reader) { dispatch(it) }
            }
        } finally {
            connected = false
            connectedSince = null
            remintJob.getAndSet(null)?.cancel()
            stream.set(null)
        }
        SyncLog.ws("realtime", "stream closed by hub")
    }

    private fun subscribeUri(hub: String, topics: List<String>): URI {
        val query = topics.joinToString("&") { "topic=" + URLEncoder.encode(it, StandardCharsets.UTF_8) }
        val separator = if (hub.contains('?')) '&' else '?'
        return URI.create("$hub$separator$query")
    }

    /**
     * Reconnect shortly before the subscriber JWT expires. Without this the stream simply stops
     * carrying events at the 24 h mark and the mod looks like it "stopped working overnight".
     */
    private fun scheduleRemint() {
        remintJob.getAndSet(null)?.cancel()
        val expiry = jwtExpiresAt
        val at = if (expiry != null) expiry.minus(REMINT_MARGIN) else Instant.now().plus(REMINT_FALLBACK)
        val delayMs = (at.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(60_000L)
        remintJob.set(
            SoulScope.io.launch {
                delay(delayMs)
                reconnect("JWT re-mint (expires ${expiry ?: "unknown"})")
            },
        )
    }

    /** `exp` out of the JWT payload, so the re-mint is scheduled against the real expiry. */
    private fun jwtExpiry(jwt: String): Instant? = try {
        val payload = jwt.split('.').getOrNull(1)
        if (payload == null) {
            null
        } else {
            val decoded = String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
            json.parseToJsonElement(decoded).jsonObject["exp"]?.jsonPrimitive?.longOrNull
                ?.let { Instant.ofEpochSecond(it) }
        }
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ dispatch

    private fun dispatch(sse: MercureSseReader.SseEvent) {
        sse.id?.takeIf { it.isNotBlank() }?.let { lastEventId = it }
        eventsReceived.incrementAndGet()
        lastEventAt = Instant.now()

        val envelope = try {
            json.parseToJsonElement(sse.data).jsonObject
        } catch (t: Throwable) {
            logger.warn("Dropped non-JSON realtime payload: ${sse.data.take(160)}")
            return
        }
        val type = envelope["type"]?.jsonPrimitive?.content
        if (type.isNullOrBlank()) {
            logger.warn("Dropped realtime payload with no type: ${sse.data.take(160)}")
            return
        }
        // `topic` is additive on the backend's side; absent on an older backend.
        val topic = envelope["topic"]?.jsonPrimitive?.content
        val data = (envelope["data"] as? JsonObject) ?: JsonObject(emptyMap())
        lastEventType = type
        SyncLog.ws("realtime", "← type=$type topic=${topic ?: "?"} $data")

        val list = handlers[type]
        if (list.isNullOrEmpty()) {
            logger.debug("Ignoring realtime type '$type' — no handler")
            return
        }
        val event = RealtimeEvent(type, topic, data, sse.id)
        for (handler in list) {
            try {
                handler(event)
            } catch (t: Throwable) {
                logger.warn("Realtime handler for '$type' threw", t)
            }
        }
    }

    // ------------------------------------------------------------------ small helpers

    /**
     * A readable reason for [t]. The JDK's `ConnectException` from `HttpClient` usually has a null
     * message and puts the useful part ("Connection refused") on its cause, which rendered as a bare
     * "ConnectException: no message" in the dev dump.
     */
    private fun describe(t: Throwable): String {
        var cause: Throwable? = t
        while (cause != null) {
            cause.message?.takeIf { it.isNotBlank() }?.let { return it }
            cause = cause.cause
        }
        return "no message"
    }

    private fun grow(backoff: Long): Long = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)

    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
