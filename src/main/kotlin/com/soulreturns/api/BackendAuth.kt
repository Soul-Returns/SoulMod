package com.soulreturns.api

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

object BackendAuth {
    private data class TokenEntry(val token: String, val expiresAt: Instant)

    private val current = AtomicReference<TokenEntry?>(null)
    @Volatile private var lastFailureAt: Instant = Instant.EPOCH
    @Volatile private var lastRateLimitAt: Instant = Instant.EPOCH

    private const val FAILURE_COOLDOWN_MS    = 30_000L
    private const val RATE_LIMIT_COOLDOWN_MS = 5 * 60_000L  // 5 minutes
    private const val TOKEN_TTL_MS           = 23 * 60 * 60_000L  // 23 h (backend TTL is 24 h)

    private val cacheFile: File
        get() = FabricLoader.getInstance().configDir.resolve("soul/auth_token.txt").toFile()

    /** Load a persisted token from disk. Called once on mod init. */
    fun loadCached() {
        try {
            val f = cacheFile
            if (!f.exists()) return
            val lines = f.readLines()
            if (lines.size < 2) return
            val token = lines[0].trim()
            val expiresAt = Instant.ofEpochMilli(lines[1].trim().toLong())
            if (Instant.now().isBefore(expiresAt)) {
                current.set(TokenEntry(token, expiresAt))
                SoulExecutor.log("Loaded cached auth token (expires in ${(expiresAt.toEpochMilli() - System.currentTimeMillis()) / 60_000} min)")
            } else {
                f.delete()
                SoulExecutor.log("Cached auth token expired, will re-authenticate")
            }
        } catch (e: Exception) {
            SoulExecutor.warn("Failed to load cached auth token", e)
        }
    }

    fun token(): String? = current.get()?.token

    fun clear() {
        current.set(null)
        try { cacheFile.delete() } catch (_: Exception) {}
    }

    fun ensureAuthenticated(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            current.get()?.takeIf { Instant.now().isBefore(it.expiresAt) }?.let { return it.token }
        }
        val now = Instant.now()
        // 429 backoff is always respected — even forceRefresh won't blast a rate-limited endpoint.
        val rateLimitRemaining = RATE_LIMIT_COOLDOWN_MS - (now.toEpochMilli() - lastRateLimitAt.toEpochMilli())
        if (rateLimitRemaining > 0) {
            SoulExecutor.log("Auth skipped — rate limited, ${rateLimitRemaining / 1000}s remaining")
            return null
        }
        if (!forceRefresh && now.toEpochMilli() - lastFailureAt.toEpochMilli() < FAILURE_COOLDOWN_MS) {
            return null
        }
        return try {
            val token = performHandshake()
            if (token != null) {
                val expiresAt = Instant.now().plusMillis(TOKEN_TTL_MS)
                current.set(TokenEntry(token, expiresAt))
                persistToken(token, expiresAt)
                SoulExecutor.log("Authenticated with backend (token cached until ${expiresAt})")
                token
            } else {
                lastFailureAt = Instant.now()
                null
            }
        } catch (e: Exception) {
            SoulExecutor.warn("Backend auth threw", e)
            lastFailureAt = Instant.now()
            null
        }
    }

    private fun persistToken(token: String, expiresAt: Instant) {
        try {
            val f = cacheFile
            f.parentFile?.mkdirs()
            f.writeText("$token\n${expiresAt.toEpochMilli()}\n")
        } catch (e: Exception) {
            SoulExecutor.warn("Failed to persist auth token", e)
        }
    }

    private fun performHandshake(): String? {
        val mc = MinecraftClient.getInstance() ?: return null
        val session = mc.session ?: return null
        val username = session.username ?: return null
        val accessToken = session.accessToken ?: return null
        val profileUuid: UUID = session.uuidOrNull ?: return null
        val sessionService = mc.apiServices?.sessionService() ?: return null
        val serverId = UUID.randomUUID().toString().replace("-", "")

        try {
            sessionService.joinServer(profileUuid, accessToken, serverId)
        } catch (e: Exception) {
            SoulExecutor.warn("sessionService.joinServer failed", e)
            return null
        }

        val response = SoulHttp.get(
            "${SoulHttp.backendBaseUrl()}/authenticate",
            headers = mapOf(
                "x-minecraft-username" to username,
                "x-minecraft-server" to serverId,
            )
        )

        return if (response.statusCode() in 200..299 && response.body().isNotBlank()) {
            response.body().trim()
        } else {
            if (response.statusCode() == 429) {
                lastRateLimitAt = Instant.now()
                SoulExecutor.warn("Backend /authenticate returned 429 — backing off for ${RATE_LIMIT_COOLDOWN_MS / 1000}s")
            } else {
                SoulExecutor.warn("Backend /authenticate returned ${response.statusCode()}: ${response.body().take(200)}")
            }
            null
        }
    }
}
