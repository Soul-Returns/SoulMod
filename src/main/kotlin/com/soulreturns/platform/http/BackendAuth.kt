package com.soulreturns.platform.http

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import com.soulreturns.util.SoulLogger
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A fresh bearer was minted by a successful `/authenticate` handshake (not a cache hit). Published
 * on the thread that ran the handshake, usually a [com.soulreturns.platform.concurrent.SoulScope]
 * worker; handlers must not block it.
 */
data class BackendAuthenticated(val uuid: String?) : Event

/**
 * Bearer-token auth against the Soul backend, privacy-preserving via Mojang's session service:
 * generate a random serverId, call `sessionService.joinServer(uuid, accessToken, serverId)`, then
 * `GET /authenticate` with the username + serverId. The backend verifies via Mojang `hasJoined` —
 * our access token never leaves the client.
 *
 * Token is cached in memory and persisted to `config/soul/auth_token.txt` so a relaunch reuses it.
 */
object BackendAuth {
    private val logger = SoulLogger("Soul/BackendAuth")

    /**
     * A cached bearer. [uuid] is the profile it was minted for — null only for a cache file written
     * before this field existed.
     */
    private data class TokenEntry(val token: String, val expiresAt: Instant, val uuid: String?)

    private val current = AtomicReference<TokenEntry?>(null)
    private val flight = SingleFlight<TokenEntry>(::usableCached, ::handshakeBlocked)

    @Volatile private var lastFailureAt = Instant.EPOCH
    @Volatile private var lastRateLimitAt = Instant.EPOCH

    private const val FAILURE_COOLDOWN_MS = 30_000L
    private const val RATE_LIMIT_COOLDOWN_MS = 5 * 60_000L
    private const val TOKEN_TTL_MS = 23 * 60 * 60_000L // backend TTL is 24 h; refresh a bit early
    private const val LOGIN_DEFER_MAX_MS = 30_000L

    private const val TEST_BEARER_PROPERTY = "soul.testBearerToken"
    @Volatile private var testBearerAnnounced = false
    @Volatile private var testBearerRefusalLogged = false

    private val cacheFile: File
        get() = FabricLoader.getInstance().configDir.resolve("soul/auth_token.txt").toFile()

    /**
     * Outcome of the most recent [performHandshake] attempt, for the `/soul dev backend test`
     * diagnostic. [statusCode] is the `/authenticate` HTTP status, or null when the handshake
     * never got that far (no session, `joinServer` threw, …).
     */
    data class HandshakeInfo(
        val at: Instant,
        val statusCode: Int?,
        val durationMs: Long,
        val detail: String,
    )

    @Volatile
    var lastHandshake: HandshakeInfo? = null
        private set

    /** Expiry of the in-memory token, or null when there is none. */
    fun expiresAt(): Instant? = current.get()?.expiresAt

    /** Where the token is persisted — shown by the backend diagnostic. */
    fun cachePath(): String = try {
        cacheFile.absolutePath
    } catch (_: Exception) {
        "<unavailable>"
    }

    /** Remaining generic-failure backoff in ms (0 = not backing off). */
    fun failureCooldownRemainingMs(): Long =
        (FAILURE_COOLDOWN_MS - (System.currentTimeMillis() - lastFailureAt.toEpochMilli())).coerceAtLeast(0)

    /** Remaining 429 backoff in ms (0 = not rate limited). */
    fun rateLimitCooldownRemainingMs(): Long =
        (RATE_LIMIT_COOLDOWN_MS - (System.currentTimeMillis() - lastRateLimitAt.toEpochMilli())).coerceAtLeast(0)

    fun loadCached() {
        try {
            val f = cacheFile
            if (!f.exists()) return
            val lines = f.readLines()
            if (lines.size < 2) return
            val expiresAt = Instant.ofEpochMilli(lines[1].trim().toLong())
            if (Instant.now().isBefore(expiresAt)) {
                current.set(TokenEntry(lines[0].trim(), expiresAt, lines.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }))
            } else {
                f.delete()
            }
        } catch (e: Exception) {
            logger.warn("Failed to load cached auth token", e)
        }
    }

    fun token(): String? = current.get()?.token

    /** The profile id of the running session, or null before one exists. */
    private fun sessionUuid(): String? = try {
        Minecraft.getInstance().user?.profileId?.toString()
    } catch (_: Throwable) {
        null
    }

    /**
     * Whether [entry] was minted for the account currently signed in. A cache file written before
     * the uuid was recorded has none, and is trusted (there is nothing better to do with it).
     */
    private fun belongsToSession(entry: TokenEntry): Boolean {
        val cached = entry.uuid ?: return true
        val session = sessionUuid() ?: return true
        return cached.equals(session, ignoreCase = true)
    }

    fun clear() {
        current.set(null)
        try {
            cacheFile.delete()
        } catch (_: Exception) {
        }
    }

    /**
     * Test-only override (`tools/testclient c up --backend local`): `-Dsoul.testBearerToken=<token>`
     * replaces the Mojang handshake with a bearer the dev stack issued up front, because the
     * harness's offline account cannot `joinServer`. Refused against any soulreturns.com backend so
     * it can never be used against production.
     */
    private fun requestedTestBearer(): String? =
        System.getProperty(TEST_BEARER_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }

    private fun testBearerAllowed(): Boolean {
        val host = try {
            URI(SoulHttp.backendBaseUrl()).host?.lowercase()
        } catch (_: Exception) {
            null
        }
        return host != null && host != "soulreturns.com" && !host.endsWith(".soulreturns.com")
    }

    /** True while the test bearer replaces the handshake; the realtime gate opens for it as well. */
    fun testBearerActive(): Boolean = requestedTestBearer() != null && testBearerAllowed()

    /** The test bearer's state for diagnostics, or null when the property is not set. */
    fun testBearerStatus(): String? = when {
        requestedTestBearer() == null -> null
        testBearerAllowed() -> "active (-D$TEST_BEARER_PROPERTY, Mojang handshake skipped)"
        else -> "refused (-D$TEST_BEARER_PROPERTY is never used against soulreturns.com)"
    }

    /**
     * Not persisted, and [BackendAuthenticated] fires only on the first adoption: re-adopting after
     * a 401 must not re-trigger listeners whose requests would 401 again, with no cooldown between.
     */
    private fun adoptTestBearer(token: String): String {
        if (current.get()?.token == token) return token
        val uuid = sessionUuid()
        current.set(TokenEntry(token, Instant.now().plusMillis(TOKEN_TTL_MS), uuid))
        lastHandshake = HandshakeInfo(Instant.now(), null, 0, "skipped: test bearer from -D$TEST_BEARER_PROPERTY")
        if (!testBearerAnnounced) {
            testBearerAnnounced = true
            logger.info("Using the test bearer from -D$TEST_BEARER_PROPERTY (Mojang handshake skipped)")
            Events.publish(BackendAuthenticated(uuid))
        }
        return token
    }

    fun ensureAuthenticated(forceRefresh: Boolean = false): String? {
        requestedTestBearer()?.let { token ->
            if (testBearerAllowed()) return adoptTestBearer(token)
            if (!testBearerRefusalLogged) {
                testBearerRefusalLogged = true
                logger.error("-D$TEST_BEARER_PROPERTY refused: ${SoulHttp.backendBaseUrl()} is not a test backend; not authenticating")
            }
            return null
        }
        if (!forceRefresh) {
            val cached = current.get()
            if (cached != null && Instant.now().isBefore(cached.expiresAt) && !belongsToSession(cached)) {
                // A bearer minted for a different account. This is not hypothetical: two clients
                // sharing one game directory (two Prism accounts, or two worktrees on the dev run/
                // dir) share this cache, and using the other account's token would have the backend
                // stamp THEIR uuid on our messages — which looks like a sync bug, not an auth bug.
                logger.info("Cached auth token belongs to another account — re-authenticating")
                clear()
            }
        }
        // Callers run on SoulScope workers or their own threads, never the client thread: losers of
        // the flight park here until the winner's handshake finishes.
        return flight.run(
            skipCache = forceRefresh,
            bypassCooldown = forceRefresh,
            onMinted = { Events.publish(BackendAuthenticated(it.uuid)) },
            handshake = ::mint,
        )?.token
    }

    /**
     * The backend answered 401 to [rejected]. Drop it (only if it is still the current bearer: a
     * concurrent request may already have replaced it, and wiping that fresh one would cost a second
     * join) and return a bearer from the single flight, bypassing the generic failure cooldown but
     * never the 429 one.
     */
    fun reauthenticate(rejected: String): String? {
        requestedTestBearer()?.let { return ensureAuthenticated() }
        if (current.get()?.token == rejected) clear()
        return flight.run(
            skipCache = false,
            bypassCooldown = true,
            onMinted = { Events.publish(BackendAuthenticated(it.uuid)) },
            handshake = ::mint,
        )?.token
    }

    /** The cached entry if it is unexpired and was minted for the signed-in account. */
    private fun usableCached(): TokenEntry? =
        current.get()?.takeIf { Instant.now().isBefore(it.expiresAt) && belongsToSession(it) }

    /** Whether a cooldown forbids a handshake right now. [bypassFailure] never lifts the 429 one. */
    private fun handshakeBlocked(bypassFailure: Boolean): Boolean {
        val nowMs = System.currentTimeMillis()
        val rateLimitRemaining = RATE_LIMIT_COOLDOWN_MS - (nowMs - lastRateLimitAt.toEpochMilli())
        if (rateLimitRemaining > 0) {
            logger.debug("Auth skipped — rate limited, ${rateLimitRemaining / 1000}s remaining")
            return true
        }
        return !bypassFailure && nowMs - lastFailureAt.toEpochMilli() < FAILURE_COOLDOWN_MS
    }

    /** The one handshake, run under [flight]'s lock. */
    private fun mint(): TokenEntry? {
        awaitServerLogin()
        return try {
            val token = performHandshake()
            if (token != null) {
                val expiresAt = Instant.now().plusMillis(TOKEN_TTL_MS)
                val entry = TokenEntry(token, expiresAt, sessionUuid())
                current.set(entry)
                persistToken(token, expiresAt, entry.uuid)
                logger.info("Authenticated with backend (token cached ~23h)")
                entry
            } else {
                lastFailureAt = Instant.now()
                null
            }
        } catch (e: Exception) {
            logger.warn("Backend auth threw", e)
            lastFailureAt = Instant.now()
            null
        }
    }

    /**
     * Hypixel's own login must win Mojang's per-account join budget: while the client sits on the
     * connect screen, hold the handshake back (bounded, so a stuck screen cannot starve auth).
     */
    private fun awaitServerLogin() {
        val deadline = System.nanoTime() + LOGIN_DEFER_MAX_MS * 1_000_000L
        try {
            while (connectingToServer() && System.nanoTime() < deadline) Thread.sleep(250)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun connectingToServer(): Boolean = try {
        Minecraft.getInstance()?.gui?.screen() is ConnectScreen
    } catch (_: Throwable) {
        false
    }

    private fun persistToken(token: String, expiresAt: Instant, uuid: String?) {
        try {
            val f = cacheFile
            f.parentFile?.mkdirs()
            f.writeText("$token\n${expiresAt.toEpochMilli()}\n${uuid ?: ""}\n")
        } catch (e: Exception) {
            logger.warn("Failed to persist auth token", e)
        }
    }

    private fun performHandshake(): String? {
        val startedAt = System.currentTimeMillis()
        fun record(statusCode: Int?, detail: String) {
            lastHandshake = HandshakeInfo(
                at = Instant.now(),
                statusCode = statusCode,
                durationMs = System.currentTimeMillis() - startedAt,
                detail = detail,
            )
        }

        val mc = Minecraft.getInstance() ?: return null.also { record(null, "no Minecraft instance") }
        val session = mc.user ?: return null.also { record(null, "no session (mc.user)") }
        val username = session.name ?: return null.also { record(null, "session has no username") }
        val accessToken = session.accessToken ?: return null.also { record(null, "session has no access token") }
        val profileUuid: UUID = session.profileId ?: return null.also { record(null, "session has no profile uuid") }
        val sessionService = mc.services()?.sessionService()
            ?: return null.also { record(null, "no Mojang session service") }
        val serverId = UUID.randomUUID().toString().replace("-", "")

        try {
            sessionService.joinServer(profileUuid, accessToken, serverId)
        } catch (e: Exception) {
            logger.warn("sessionService.joinServer failed", e)
            record(null, "Mojang joinServer failed: ${e.message ?: e.javaClass.simpleName}")
            if (isMojangRateLimit(e)) {
                lastRateLimitAt = Instant.now()
                logger.warn("Mojang joinServer rate limited — backing off ${RATE_LIMIT_COOLDOWN_MS / 1000}s")
            }
            return null
        }

        val response = try {
            SoulHttp.get(
                "${SoulHttp.backendBaseUrl()}/authenticate",
                headers = mapOf(
                    "x-minecraft-username" to username,
                    "x-minecraft-server" to serverId,
                ),
            )
        } catch (e: Exception) {
            record(null, "GET /authenticate threw: ${e.message ?: e.javaClass.simpleName}")
            throw e
        }

        return if (response.statusCode() in 200..299 && response.body().isNotBlank()) {
            record(response.statusCode(), "ok")
            response.body().trim()
        } else {
            record(
                response.statusCode(),
                if (response.body().isBlank()) "empty body" else response.body().take(120).replace('\n', ' '),
            )
            if (response.statusCode() == 429) {
                lastRateLimitAt = Instant.now()
                logger.warn("Backend /authenticate 429 — backing off ${RATE_LIMIT_COOLDOWN_MS / 1000}s")
            } else {
                logger.warn("Backend /authenticate returned ${response.statusCode()}: ${response.body().take(200)}")
            }
            null
        }
    }
}
