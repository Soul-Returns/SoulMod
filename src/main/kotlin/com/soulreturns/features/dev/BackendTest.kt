package com.soulreturns.features.dev

import com.soulreturns.Soul
import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.dungeons.sync.SoulBackendChannel
import com.soulreturns.platform.http.BackendAuth
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.SoulHttp
import com.soulreturns.platform.presence.PresenceService
import com.soulreturns.platform.realtime.MercureSseReader
import com.soulreturns.platform.realtime.RealtimeClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `/soul dev backend` (static dump) and `/soul dev backend test` (live end-to-end probe).
 *
 * The probe walks the exact path the dungeon secret sync uses, one step at a time, so a failure
 * points at the layer that broke instead of just "sync doesn't work":
 *
 *  1. **Reachability** — plain `GET /` (no auth): is the host up and are we talking to the URL we
 *     think we are?
 *  2. **Auth** — Mojang `joinServer` + `GET /authenticate` → bearer token (or the cached one).
 *  3. **Realtime token** — `GET /realtime/token` with that bearer → Mercure JWT + hub URL + topics.
 *     This is also what proves the bearer is actually *accepted*, not merely present.
 *  4. **Hub** — open the SSE stream against the hub on a throwaway topic.
 *  5. **Relay round-trip** — `POST /dungeon/sync` and wait for the message to come back down the
 *     stream, which exercises publish → Mercure → subscribe end to end.
 *
 * Steps 4–5 use a random `dungeon/soultest<hex>` instance id rather than the live one, so the probe
 * never broadcasts junk to teammates: nobody else is subscribed to that topic. The backend
 * authorises the whole `dungeon/{serverId}` template, so a made-up id needs no special support.
 *
 * Note that the throwaway topic is not invisible to the shared
 * [com.soulreturns.platform.realtime.RealtimeClient] connection: that one subscribes to the
 * `dungeon/{serverId}` template, which matches `dungeon/soultest<hex>` too, so the probe's own relay
 * echo also lands there and bumps its event counter. Harmless — the dungeon channel drops it (own
 * echo, and no run in progress) — but it is why running the probe makes the shared connection's
 * `events` climb.
 *
 * **Step 4 deliberately opens its own throwaway SSE stream** rather than reusing the shared
 * [com.soulreturns.platform.realtime.RealtimeClient] connection. Two reasons: the probe must work
 * when the shared connection is down (that is often exactly what is being diagnosed), and it must
 * not perturb a live connection the dungeon sync is riding on. The shared connection's own state is
 * reported separately, by [realtimeLines].
 *
 * Runs on its own daemon thread — every step is a blocking HTTP call and must not touch the client
 * thread. Output goes to [DevOutput], which is thread-safe and which the dev window re-reads every
 * frame — so a multi-second probe fills in step by step rather than arriving all at once.
 */
object BackendTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val running = AtomicBoolean(false)

    /** How long to wait for the relayed message to arrive back over SSE. */
    private const val ECHO_TIMEOUT_MS = 10_000L

    // ------------------------------------------------------------------ static dump

    /** `/soul dev backend` — everything known without touching the network. */
    fun info() {
        DevOutput.begin("/soul dev backend")
        DevOutput.line("§6Backend §8(static — run §f/soul dev backend test§8 for a live check)")
        for (line in environmentLines()) DevOutput.line(line)
        for (line in authStateLines()) DevOutput.line(line)
        for (line in httpClientLines()) DevOutput.line(line)
        for (line in realtimeLines()) DevOutput.line(line)
        for (line in presenceLines()) DevOutput.line(line)
        for (line in syncStateLines()) DevOutput.line(line)
    }

    /**
     * `/soul dev realtime` — just the shared realtime connection, plus a nudge to re-evaluate its
     * gate so "why is it not connected" answers itself instead of waiting up to 5 s for the tick.
     */
    fun realtime() {
        DevOutput.begin("/soul dev realtime")
        try {
            RealtimeClient.reconcile()
        } catch (t: Throwable) {
            DevOutput.line("§creconcile threw: §f${t.javaClass.simpleName}: ${t.message}")
        }
        DevOutput.line("§6Realtime")
        for (line in realtimeLines()) DevOutput.line(line)
        DevOutput.line("§8  send one with §f./bin/console§8 or §fPOST /admin/players/<uuid>/notify§8 on the backend")
    }

    /**
     * `/soul dev presence` — the presence block, plus the exact JSON body a transition push would
     * send right now. The payload is printed because the contract requires all six keys on every
     * request and a serializer misconfiguration would drop them silently (see
     * [PresenceService.previewPayload]).
     */
    fun presence() {
        DevOutput.begin("/soul dev presence")
        DevOutput.line("§6Presence")
        for (line in presenceLines()) DevOutput.line(line)
        DevOutput.line("§8  next transition push would send:")
        for (line in PresenceService.previewPayload().lines()) DevOutput.line("§8    §7$line")
    }

    // ------------------------------------------------------------------ live probe

    /**
     * `/soul dev backend test [fresh]` — runs the five steps above. [fresh] drops the cached bearer
     * first, so the Mojang handshake and `/authenticate` are exercised for real instead of skipped.
     */
    fun test(fresh: Boolean) {
        DevOutput.begin("/soul dev backend test" + if (fresh) " fresh" else "")
        if (!running.compareAndSet(false, true)) {
            DevOutput.line("§eBackend test already running.")
            return
        }
        Thread({
            try {
                execute(fresh)
            } catch (t: Throwable) {
                DevOutput.line("§cBackend test crashed: §f${t.javaClass.simpleName}: ${t.message}")
            } finally {
                running.set(false)
            }
        }, "soul-backend-test").apply { isDaemon = true }.start()
    }

    private fun execute(fresh: Boolean) {
        val startedAt = System.nanoTime()
        DevOutput.line("§6Backend test §8— §7${if (fresh) "forced re-auth" else "cached auth allowed"}")
        for (line in environmentLines()) DevOutput.line(line)

        var failed = 0
        val base = SoulHttp.backendBaseUrl()

        // 1 — reachability -----------------------------------------------------------------
        val reachable = step(1, "Reachability §8GET §7$base/") {
            val t0 = System.nanoTime()
            val response = SoulHttp.get("$base/")
            val server = response.headers().firstValue("server").orElse(null)
            Result(
                ok = response.statusCode() < 500,
                detail = "HTTP ${response.statusCode()} in ${ms(t0)}" + (server?.let { " §8(server: $it)" } ?: ""),
                hint = if (response.statusCode() >= 500) "the host answered but is erroring — check the backend's own logs" else null,
            )
        }
        if (!reachable) {
            failed++
            DevOutput.line("§8  ↳ §7nothing else can work until the host answers; the remaining steps still run.")
        }

        // 2 — auth -------------------------------------------------------------------------
        if (fresh) BackendAuth.clear()
        var bearer: String? = null
        val authed = step(2, "Auth §8Mojang joinServer + GET §7/authenticate") {
            val hadCached = !fresh && BackendAuth.token() != null
            val t0 = System.nanoTime()
            bearer = BackendAuth.ensureAuthenticated(forceRefresh = fresh)
            val handshake = BackendAuth.lastHandshake
            when {
                bearer != null && hadCached ->
                    Result(true, "reused cached token §8(no request made — add §7fresh§8 to force a handshake)")
                bearer != null ->
                    Result(true, "authenticated in ${ms(t0)} §8(HTTP ${handshake?.statusCode ?: "?"})")
                handshake != null ->
                    Result(
                        false,
                        "failed§8: ${handshake.statusCode?.let { "HTTP $it — " } ?: ""}${handshake.detail}",
                        hint = authHint(handshake),
                    )
                else ->
                    Result(false, "failed §8(no handshake attempted — see cooldowns below)", hint = cooldownHint())
            }
        }
        if (!authed) failed++
        for (line in authStateLines()) DevOutput.line(line)

        // 3 — realtime token, then 4 + 5 — hub + relay round-trip --------------------------
        val bearerToken = bearer
        if (bearerToken == null) {
            DevOutput.line("§7[3/5] Realtime §8— §eskipped §8(no bearer token)")
            DevOutput.line("§7[4/5] Hub §8— §eskipped §8(no bearer token)")
            DevOutput.line("§7[5/5] Relay §8— §eskipped §8(no bearer token)")
            failed += 3
        } else {
            val realtime = stepRealtime(base, bearerToken)
            if (realtime == null) {
                DevOutput.line("§7[4/5] Hub §8— §eskipped §8(no realtime token)")
                DevOutput.line("§7[5/5] Relay §8— §eskipped §8(no realtime token)")
                failed += 3
            } else {
                failed += roundTrip(base, bearerToken, realtime.jwt, realtime.hubUrl)
            }
        }

        // summary --------------------------------------------------------------------------
        val total = String.format(Locale.ROOT, "%.2fs", (System.nanoTime() - startedAt) / 1_000_000_000.0)
        if (failed == 0) {
            DevOutput.line("§aBackend test passed §8— all steps ok in $total")
        } else {
            DevOutput.line("§cBackend test failed §8— §f$failed§8 step(s) failed in $total")
        }
        for (line in realtimeLines()) DevOutput.line(line)
        for (line in presenceLines()) DevOutput.line(line)
        for (line in syncStateLines()) DevOutput.line(line)
    }

    private class Realtime(val jwt: String, val hubUrl: String, val topics: List<String>)

    /** Step 3. Returns null (having reported the failure) when no usable Mercure token came back. */
    private fun stepRealtime(base: String, bearer: String): Realtime? {
        var parsed: Realtime? = null
        val ok = step(3, "Realtime §8GET §7/realtime/token") {
            val t0 = System.nanoTime()
            val response = SoulHttp.get(
                "$base/realtime/token",
                headers = mapOf("Authorization" to bearer, "X-Intent" to "backend-test"),
            )
            if (response.statusCode() !in 200..299) {
                return@step Result(
                    false,
                    "HTTP ${response.statusCode()} in ${ms(t0)} §8— ${response.body().take(120)}",
                    hint = if (response.statusCode() == 401) {
                        "the bearer was rejected — re-run with §f/soul dev backend test fresh"
                    } else {
                        null
                    },
                )
            }
            val obj = json.parseToJsonElement(response.body()).jsonObject
            val jwt = obj["jwt"]?.jsonPrimitive?.content
            val hubUrl = obj["hubUrl"]?.jsonPrimitive?.content
            val topics = obj["topics"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
            if (jwt.isNullOrBlank() || hubUrl.isNullOrBlank()) {
                Result(false, "HTTP 200 but malformed body §8— ${response.body().take(120)}")
            } else {
                parsed = Realtime(jwt, hubUrl, topics)
                Result(true, "HTTP ${response.statusCode()} in ${ms(t0)}")
            }
        }
        val realtime = parsed
        if (!ok || realtime == null) return null
        DevOutput.line("§8  ↳ §7hub: §f${realtime.hubUrl}")
        DevOutput.line("§8  ↳ §7jwt: §f${mask(realtime.jwt)} §8· topics (${realtime.topics.size}): §f${realtime.topics.joinToString()}")
        if (realtime.topics.none { it == "dungeon/{serverId}" }) {
            DevOutput.line("§8  ↳ §eno §fdungeon/{serverId}§e template in the topic list — the relay step will not receive.")
        }
        return realtime
    }

    /** Steps 4 and 5. Returns the number of failed steps (0–2). */
    private fun roundTrip(base: String, bearer: String, jwt: String, hubUrl: String): Int {
        val instance = "soultest" + UUID.randomUUID().toString().replace("-", "").take(8)
        val topic = "dungeon/$instance"
        val nonce = UUID.randomUUID().toString()

        val separator = if (hubUrl.contains('?')) '&' else '?'
        val uri = try {
            URI.create("$hubUrl$separator" + "topic=" + URLEncoder.encode(topic, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            DevOutput.line("§7[4/5] Hub §8— §cfailed§8: unusable hub URL '§f$hubUrl§8' (${e.message})")
            DevOutput.line("§7[5/5] Relay §8— §eskipped")
            return 2
        }

        var response: HttpResponse<InputStream>? = null
        var reader: Thread? = null
        try {
            val hubOk = step(4, "Hub §8SSE §7$topic") {
                val t0 = System.nanoTime()
                val request = HttpRequest.newBuilder()
                    .GET()
                    .uri(uri)
                    // The live channel deliberately sets no timeout (the stream is long-lived); here
                    // the stream lives only as long as the probe, so bound it — an unresponsive hub
                    // must not wedge the test thread forever.
                    .timeout(Duration.ofMillis(ECHO_TIMEOUT_MS * 2))
                    .header("Authorization", "Bearer $jwt")
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", SoulHttp.userAgent())
                    .build()
                response = SoulHttp.client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val code = response!!.statusCode()
                Result(
                    ok = code in 200..299,
                    detail = if (code in 200..299) "connected in ${ms(t0)}" else "HTTP $code in ${ms(t0)}",
                    hint = when {
                        code == 401 -> "the hub rejected the subscriber JWT — hub secret mismatch between backend and Mercure"
                        code in 200..299 -> null
                        else -> "reached the hub but it refused the subscription"
                    },
                )
            }
            if (!hubOk) {
                DevOutput.line(
                    "§8  ↳ §7hub URL from the backend: §f$hubUrl§8 — if that is a container-internal name, " +
                        "this client cannot reach it (the backend's public Mercure URL must be host-reachable).",
                )
                DevOutput.line("§7[5/5] Relay §8— §eskipped §8(no SSE stream)")
                return 2
            }

            // Read the stream on its own thread; the round-trip step waits on this future.
            val stream = response!!.body()
            val echo = CompletableFuture<String>()
            reader = Thread({
                try {
                    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { r ->
                        MercureSseReader.readLoop(r) { event ->
                            val payload = runCatching {
                                json.parseToJsonElement(event.data).jsonObject["data"]?.jsonObject
                            }.getOrNull()
                            if (payload?.get("nonce")?.jsonPrimitive?.content == nonce) {
                                echo.complete(payload.toString())
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Stream closed underneath us (timeout path) — the future decides the outcome.
                } finally {
                    echo.complete("")
                }
            }, "soul-backend-test-sse").apply { isDaemon = true }
            reader.start()

            val relayOk = step(5, "Relay §8POST §7/dungeon/sync§8 → SSE echo") {
                val t0 = System.nanoTime()
                val body = buildJsonObject {
                    put("serverId", instance)
                    put(
                        "message",
                        buildJsonObject {
                            put("type", "soul_backend_test")
                            put("nonce", nonce)
                        },
                    )
                }.toString()
                val post = SoulHttp.post(
                    "$base/dungeon/sync",
                    body,
                    headers = mapOf(
                        "Authorization" to bearer,
                        "Content-Type" to "application/json",
                        "X-Intent" to "backend-test",
                    ),
                )
                if (post.statusCode() !in 200..299) {
                    return@step Result(
                        false,
                        "POST rejected: HTTP ${post.statusCode()} §8— ${post.body().take(120)}",
                    )
                }
                val postMs = ms(t0)
                val received = try {
                    echo.get(ECHO_TIMEOUT_MS, TimeUnit.MILLISECONDS).takeIf { it.isNotEmpty() }
                } catch (_: TimeoutException) {
                    null
                }
                if (received == null) {
                    Result(
                        false,
                        "POST ${post.statusCode()} in $postMs, but no echo within ${ECHO_TIMEOUT_MS / 1000}s",
                        hint = "the relay accepted the message but it never came back — check the backend's Mercure " +
                            "publisher (hub URL / JWT secret used for publishing)",
                    )
                } else {
                    val stamped = runCatching {
                        json.parseToJsonElement(received).jsonObject["uuid"]?.jsonPrimitive?.content
                    }.getOrNull()
                    val self = Minecraft.getInstance().user.profileId.toString().replace("-", "")
                    // The backend stamps the sender uuid from the bearer token, undashed.
                    val match = stamped != null && stamped.equals(self, ignoreCase = true)
                    Result(
                        true,
                        "POST ${post.statusCode()} in $postMs, echo in ${ms(t0)} §8" +
                            "(sender uuid stamped: §f${stamped ?: "none"}§8, ${if (match) "matches us" else "NOT ours"})",
                    )
                }
            }
            return if (relayOk) 0 else 1
        } catch (e: Exception) {
            DevOutput.line("§cRound-trip aborted§8: ${e.javaClass.simpleName}: ${e.message}")
            return 2
        } finally {
            runCatching { response?.body()?.close() }
            reader?.interrupt()
        }
    }

    // ------------------------------------------------------------------ report sections

    private fun environmentLines(): List<String> {
        val lines = mutableListOf<String>()
        val mc = Minecraft.getInstance()
        val mcVersion = try {
            SharedConstants.getCurrentVersion().name()
        } catch (_: Throwable) {
            "unknown"
        }
        lines += "§7  client: §fSoul ${Soul.version}§8 · MC $mcVersion · UA §f${SoulHttp.userAgent()}"

        val user = mc.user
        lines += "§7  session: §f${user.name}§8 · uuid §f${user.profileId}"
        // The tell for "DevAuth never ran" is the UUID version, not xuid: an offline account's id is
        // derived from the name (UUID.nameUUIDFromBytes → v3), a real Mojang profile id is v4.
        // DevAuth Neo authenticates for real but leaves xuid/clientId empty, so those are reported
        // as plain facts — they are NOT evidence of an offline account on their own.
        val offline = user.profileId.version() == 3
        lines += "§7  account: §f${if (offline) "§coffline (name-derived uuid)" else "online (v4 uuid)"}§8 · " +
            "xuid ${if (user.xuid.orElse(null).isNullOrBlank()) "absent" else "present"}§8 · " +
            "clientId ${if (user.clientId.orElse(null).isNullOrBlank()) "absent" else "present"}" +
            "§8 (DevAuth leaves both empty — normal)"
        if (offline && !BackendAuth.testBearerActive()) {
            lines += "§8  ↳ §ethis is the offline dev account; §f/authenticate§e will fail. Run §f./gradlew " +
                "runClient§e from a terminal once and complete the DevAuth login."
        }
        lines += "§7  server: §f${mc.currentServer?.ip ?: if (mc.isLocalServer) "singleplayer" else "not connected"}"

        val base = SoulHttp.backendBaseUrl()
        val property = System.getProperty("soul.backendUrl")?.takeIf { it.isNotBlank() }
        val env = System.getenv("SOUL_BACKEND_URL")?.takeIf { it.isNotBlank() }
        val configured = try {
            cfg.dev.backendUrl.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        val source = when {
            SoulHttp.overrideEnvWithConfig && configured != null -> "config field (Apply & reconnect override)"
            property != null -> "-Dsoul.backendUrl"
            env != null -> "SOUL_BACKEND_URL"
            configured != null -> "config field dev.backendUrl"
            else -> "built-in default"
        }
        lines += "§7  url: §f$base §8(from $source)"
        lines += "§8    -Dsoul.backendUrl=§7${property ?: "unset"}§8 · SOUL_BACKEND_URL=§7${env ?: "unset"}"
        lines += "§8    dev.backendUrl=§7${configured ?: "blank"}§8 · overrideEnvWithConfig=§7${SoulHttp.overrideEnvWithConfig}" +
            "§8 · default=§7${SoulHttp.BACKEND_URL}"
        return lines
    }

    private fun authStateLines(): List<String> {
        val lines = mutableListOf<String>()
        val token = BackendAuth.token()
        val expiry = BackendAuth.expiresAt()
        lines += "§7  auth: §f${if (token != null) "token cached ${mask(token)}" else "no token"}" +
            (expiry?.let { "§8 · expires in ${until(it)}" } ?: "")
        BackendAuth.testBearerStatus()?.let { lines += "§e    test bearer: $it" }
        BackendAuth.lastHandshake?.let {
            lines += "§8    last handshake: ${it.statusCode?.let { c -> "HTTP $c" } ?: "no request"} · " +
                "${it.detail} · ${it.durationMs}ms · ${ago(it.at)} ago"
        }
        val failure = BackendAuth.failureCooldownRemainingMs()
        val rateLimit = BackendAuth.rateLimitCooldownRemainingMs()
        if (failure > 0 || rateLimit > 0) {
            lines += "§8    cooldowns: §efailure ${failure / 1000}s · rate-limit ${rateLimit / 1000}s"
        }
        lines += "§8    token cache: §7${BackendAuth.cachePath()}"
        return lines
    }

    /** The shared [RealtimeClient] connection — the one stream every realtime consumer rides on. */
    private fun realtimeLines(): List<String> {
        val status = RealtimeClient.status()
        val lines = mutableListOf<String>()
        lines += "§7  realtime: ${if (status.connected) "§aconnected" else if (status.running) "§econnecting" else "§7idle"}" +
            "§8 · gate ${status.gate}" +
            (status.connectedSince?.let { " · up ${ago(it)}" } ?: "")
        lines += "§8    hub: §7${status.hubUrl ?: "none"}"
        lines += "§8    topics (${status.topics.size}): §7${status.topics.joinToString().ifEmpty { "none" }}"
        lines += "§8    events: §7${status.eventsReceived}" +
            (status.lastEventType?.let { " · last §f$it" } ?: "") +
            (status.lastEventAt?.let { " ${ago(it)} ago" } ?: "") +
            (status.lastEventId?.let { " §8· id §7${it.takeLast(12)}" } ?: "")
        lines += "§8    handlers: §7${status.handledTypes.joinToString().ifEmpty { "none" }}"
        topicMismatch(status.topics)?.let { lines += it }
        status.jwtExpiresAt?.let { lines += "§8    jwt expires in §7${until(it)}" }
        status.lastError?.let { lines += "§8    last error: §e$it" }
        return lines
    }

    /**
     * A topic mismatch is the one realtime failure that reports **nothing** on either side: the hub
     * drops a publish to a topic the subscriber JWT does not cover, and an unauthorized subscribe
     * still returns 200 and simply stays quiet (verified against Mercure 0.24.2). So "no events ever
     * arrive" looks identical to "nothing was ever sent". This checks the one mismatch we can catch
     * locally — the per-player topic not matching this session's uuid — so that case at least says so.
     */
    private fun topicMismatch(topics: List<String>): String? {
        if (topics.isEmpty()) return null
        val self = try {
            Minecraft.getInstance().user.profileId ?: return null
        } catch (_: Throwable) {
            return null
        }
        val userTopic = topics.firstOrNull { it.startsWith("user/") }
            ?: return "§8    ↳ §eno §fuser/<uuid>§e topic — backend notifications cannot reach this client"
        val id = userTopic.removePrefix("user/")
        val dashed = self.toString()
        if (id.equals(dashed, ignoreCase = true) || id.equals(dashed.replace("-", ""), ignoreCase = true)) return null
        return "§8    ↳ §esubscribed to §f$userTopic§e but this session is §f$dashed§e — a notification " +
            "published to us would be dropped silently by the hub, with no error on either side"
    }

    /** [BackendClient] cache + retry state — "is this endpoint cached, cooling down, or broken". */
    private fun httpClientLines(): List<String> {
        val status = BackendClient.status()
        val lines = mutableListOf<String>()
        lines += "§7  http: §fcache ${status.cacheEntries} entr(ies)§8 · hits ${status.cacheHits}" +
            " · misses ${status.cacheMisses} · requests ${status.requests}" +
            " · failures ${status.failures}" + (if (status.inFlight > 0) " · in flight ${status.inFlight}" else "")
        for ((endpoint, until) in status.cooldowns) {
            lines += "§8    cooling down: §e$endpoint§8 for ${until(until)}"
        }
        for ((endpoint, message) in status.lastErrors) {
            lines += "§8    last error on §7$endpoint§8: §e$message"
        }
        return lines
    }

    private fun presenceLines(): List<String> {
        val status = PresenceService.status()
        val lines = mutableListOf<String>()
        lines += "§7  presence: §f${if (status.enabled) "§aenabled" else "§7disabled"}§8 · pushes ${status.pushes}" +
            " · failures ${status.failures}"
        val payload = status.lastPayload
        if (payload == null) {
            lines += "§8    last push: §7none yet"
        } else {
            lines += "§8    last push: §7${payload.event}" +
                (status.lastAttemptAt?.let { " ${ago(it)} ago" } ?: "") +
                "§8 → §7${status.lastResult ?: "in flight"}"
            lines += "§8    payload: §7${payload.describe()}"
        }
        lines += "§8    next heartbeat: §7" + (
            status.nextHeartbeatAt?.let { if (it.isAfter(Instant.now())) "in ${until(it)}" else "due" }
                ?: "not scheduled §8(off, or not on a server)"
            )
        return lines
    }

    private fun syncStateLines(): List<String> {
        val status = SoulBackendChannel.status()
        val lines = mutableListOf<String>()
        lines += "§7  location: §fserver ${LocationApi.serverId ?: "none"}§8 · type ${LocationApi.serverType ?: "none"}" +
            " · mode ${LocationApi.mode ?: "none"} · inDungeon ${LocationApi.inDungeon}" +
            " · inRun ${DungeonRunState.inRun}"
        lines += "§7  sync channel: §fenabled ${status.enabled}§8 · instance ${status.serverId ?: "none"}" +
            " · shared SSE ${if (status.realtimeConnected) "§aconnected§8" else "disconnected"}"
        lines += "§8    events: in ${status.eventsReceived}" +
            (status.lastInboundAt?.let { " (last ${ago(it)} ago)" } ?: "") +
            " · out ${status.eventsPublished}" +
            (status.lastPublishAt?.let { " (last ${ago(it)} ago)" } ?: "")
        status.lastError?.let { lines += "§8    last error: §e$it" }
        return lines
    }

    // ------------------------------------------------------------------ plumbing

    private class Result(val ok: Boolean, val detail: String, val hint: String? = null)

    /** Runs one numbered step, printing its outcome (and any hint) and swallowing throwables. */
    private fun step(index: Int, title: String, body: () -> Result): Boolean {
        val result = try {
            body()
        } catch (t: Throwable) {
            Result(false, "${t.javaClass.simpleName}: ${t.message ?: "no message"}", hint = throwableHint(t))
        }
        DevOutput.line("§7[$index/5] $title §8— ${if (result.ok) "§a" else "§c"}${result.detail}")
        result.hint?.let { DevOutput.line("§8  ↳ §e$it") }
        return result.ok
    }

    private fun throwableHint(t: Throwable): String? {
        val message = (t.message ?: "").lowercase()
        return when {
            t is java.net.ConnectException || "connection refused" in message ->
                "nothing is listening there — is the backend running, and is the port right?"
            t is java.net.http.HttpConnectTimeoutException -> "connect timed out — wrong host, or blocked by a firewall"
            t is java.net.UnknownHostException -> "host does not resolve"
            t is javax.net.ssl.SSLException -> "TLS failed — using https:// against a plain-http dev backend?"
            else -> null
        }
    }

    private fun authHint(handshake: BackendAuth.HandshakeInfo): String? = when {
        handshake.statusCode == 401 || handshake.statusCode == 403 ->
            "the backend's Mojang hasJoined check did not confirm us — check the account line above, and " +
                "that the backend reaches sessionserver.mojang.com"
        handshake.statusCode == 429 -> "rate limited by the backend — wait for the cooldown below"
        handshake.statusCode == null && "joinServer" in handshake.detail ->
            "Mojang's session service rejected the client session (offline / expired DevAuth token)"
        handshake.statusCode == 404 -> "no /authenticate on this host — is the URL pointing at the right service?"
        else -> null
    }

    private fun cooldownHint(): String? {
        val rateLimit = BackendAuth.rateLimitCooldownRemainingMs()
        val failure = BackendAuth.failureCooldownRemainingMs()
        return when {
            rateLimit > 0 -> "backing off from a 429 for another ${rateLimit / 1000}s"
            failure > 0 -> "backing off from a recent failure for another ${failure / 1000}s — 'fresh' bypasses this"
            else -> null
        }
    }

    private fun ms(startNanos: Long): String =
        "${(System.nanoTime() - startNanos) / 1_000_000}ms"

    private fun mask(secret: String?): String {
        if (secret.isNullOrBlank()) return "none"
        val head = secret.take(8)
        return "$head…§8(${secret.length} chars)"
    }

    private fun ago(instant: Instant): String = duration(System.currentTimeMillis() - instant.toEpochMilli())

    private fun until(instant: Instant): String = duration(instant.toEpochMilli() - System.currentTimeMillis())

    private fun duration(millis: Long): String {
        if (millis <= 0) return "0s"
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}
