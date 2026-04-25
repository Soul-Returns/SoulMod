package com.soulreturns.api

import net.minecraft.client.MinecraftClient
import com.soulreturns.util.SoulLogger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sends a lightweight authenticated GET /ping to the backend every 20 seconds
 * so the server knows which players are currently online.
 *
 * The `server` query parameter is included when the player is connected to a
 * server, and omitted when on the main menu.
 *
 * Authentication uses [BackendAuth]; a 401 triggers a single re-auth attempt.
 * All other errors are swallowed — a missed ping is harmless.
 */
object PresenceService {

    private val logger = SoulLogger("Soul/Presence")
    private const val INTERVAL_SEC = 20L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "soul-presence").also { it.isDaemon = true }
    }

    fun start() {
        scheduler.scheduleAtFixedRate(::sendPing, 0, INTERVAL_SEC, TimeUnit.SECONDS)
        logger.debug("PresenceService started")
    }

    private fun sendPing() {
        try {
            val token = BackendAuth.ensureAuthenticated() ?: return

            val serverAddress = MinecraftClient.getInstance()?.currentServerEntry?.address
            val url = buildUrl(serverAddress)

            var response = SoulHttp.get(url, mapOf("Authorization" to token))

            if (response.statusCode() == 401) {
                BackendAuth.clear()
                val refreshed = BackendAuth.ensureAuthenticated(forceRefresh = true) ?: return
                response = SoulHttp.get(url, mapOf("Authorization" to refreshed))
            }

            if (response.statusCode() !in 200..299) {
                logger.debug("Ping returned ${response.statusCode()}")
            }
        } catch (_: Throwable) {
            // Network errors are expected occasionally — don't log noise
        }
    }

    private fun buildUrl(server: String?): String {
        val base = "${SoulHttp.backendBaseUrl()}/ping"
        if (server.isNullOrBlank()) return base
        val encoded = URLEncoder.encode(server, StandardCharsets.UTF_8)
        return "$base?server=$encoded"
    }
}
