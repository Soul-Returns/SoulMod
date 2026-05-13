package com.soulreturns.platform.realtime

import com.soulreturns.platform.http.BackendClient
import com.soulreturns.util.SoulLogger

/**
 * Bridges the existing bearer-token auth into Mercure's JWT-based subscriber auth.
 *
 * One backend call: `GET /realtime/token` returning the Mercure JWT, the hub URL, and the
 * list of topics this player is currently allowed to subscribe to. The mod doesn't introspect
 * the JWT or mint anything itself — that's all backend-side, signed with the Mercure hub's
 * shared secret.
 *
 * JWTs are short-lived; on disconnect we re-call [fetch] before reconnecting.
 */
internal object RealtimeAuth {
    private val logger = SoulLogger("Soul/Realtime")

    data class Token(
        val jwt: String,
        val hubUrl: String,
        val topics: List<String>,
    )

    fun fetch(): Token? {
        val result = BackendClient.get("/realtime/token", intent = "realtime-token").join()
        when (result) {
            is BackendClient.Result.Ok -> {
                val obj = result.json.asJsonObject
                val jwt = obj.get("jwt")?.asString
                val hubUrl = obj.get("hubUrl")?.asString
                val topicsArr = obj.getAsJsonArray("topics")
                if (jwt.isNullOrBlank() || hubUrl.isNullOrBlank() || topicsArr == null) {
                    logger.warn("Malformed /realtime/token response: $obj")
                    return null
                }
                val topics = topicsArr.mapNotNull { it.asString }
                return Token(jwt, hubUrl, topics)
            }

            is BackendClient.Result.Error -> {
                logger.warn("/realtime/token failed: HTTP ${result.statusCode} ${result.message}")
                return null
            }
        }
    }
}
