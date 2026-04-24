package com.soulreturns.api

import net.minecraft.client.MinecraftClient
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

object BackendAuth {
    private data class TokenEntry(val token: String, val acquiredAt: Instant)

    private val current = AtomicReference<TokenEntry?>(null)
    @Volatile private var lastFailureAt: Instant = Instant.EPOCH
    private const val FAILURE_COOLDOWN_MS = 30_000L

    fun token(): String? = current.get()?.token

    fun clear() {
        current.set(null)
    }

    fun ensureAuthenticated(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            current.get()?.let { return it.token }
        }
        val now = Instant.now()
        if (!forceRefresh && now.toEpochMilli() - lastFailureAt.toEpochMilli() < FAILURE_COOLDOWN_MS) {
            return null
        }
        return try {
            val token = performHandshake()
            if (token != null) {
                current.set(TokenEntry(token, Instant.now()))
                SoulExecutor.log("Authenticated with backend.")
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
            SoulExecutor.warn("Backend /authenticate returned ${response.statusCode()}: ${response.body().take(200)}")
            null
        }
    }
}
