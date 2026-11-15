package com.soulreturns.platform.realtime

import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import kotlinx.serialization.Serializable

/**
 * `GET /realtime/token` — the mod's subscriber credentials for the Mercure hub.
 *
 * @property jwt HS256 subscriber JWT minted by the backend with the hub's shared secret (the mod
 *   never mints anything itself), `exp` = now + 24 h.
 * @property hubUrl the publicly reachable hub URL. In a container-based dev setup this is the one
 *   thing that is easy to get wrong — an internal hostname here is unreachable from the client.
 * @property topics the topics this JWT authorises, to be subscribed to **verbatim**.
 *   `RealtimeTokenService::topicsFor()` on the backend is the single source of truth and this
 *   endpoint exists precisely so the mod never hardcodes a topic string. One of them
 *   (`dungeon/{serverId}`) is an RFC 6570 URI template.
 */
@Serializable
data class RealtimeToken(
    val jwt: String = "",
    val hubUrl: String = "",
    val topics: List<String> = emptyList(),
)

/**
 * Fetches a [RealtimeToken]. Blocking — called from the realtime daemon thread, never the client
 * thread. Everything else (bearer, 401-retry, rate-limit backoff, usage attribution) is
 * [BackendClient]'s job; this is just the typed endpoint.
 *
 * The response is declared uncacheable by the backend (`X-Backend-Expire-In: 0`) and we ask for a
 * bypass anyway: a reconnect must never reuse a JWT that is closer to expiry than it looks.
 */
internal object RealtimeAuth {
    const val INTENT = "realtime-subscribe"

    /** @return the token, or null with [lastError] set. */
    fun fetch(): RealtimeToken? {
        // Anything thrown here would kill the realtime thread outright — including the
        // CancellationException that surfaces when SoulScope is torn down mid-request at shutdown.
        val result = try {
            BackendClient.getBlocking(
                endpoint = "/realtime/token",
                serializer = RealtimeToken.serializer(),
                intent = INTENT,
                bypassCache = true,
            )
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
            return null
        }
        if (result is BackendResult.Ok) {
            val token = result.value
            if (token.jwt.isBlank() || token.hubUrl.isBlank()) {
                lastError = "malformed /realtime/token response (no jwt or hubUrl)"
                return null
            }
            lastError = null
            return token
        }
        lastError = (result as BackendResult.Failure).message
        return null
    }

    /** Why the most recent [fetch] failed, for the realtime status dump. */
    @Volatile
    var lastError: String? = null
        private set
}
