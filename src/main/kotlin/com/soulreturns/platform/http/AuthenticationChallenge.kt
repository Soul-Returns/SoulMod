package com.soulreturns.platform.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
internal data class AuthenticationChallenge(
    val schemaVersion: Int,
    val challengeId: String,
    val serverId: String,
    val expiresAt: Long,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val challengePattern = Regex("[a-f0-9]{64}")
        private val serverPattern = Regex("[a-f0-9]{40}")

        fun decode(raw: String, now: Instant = Instant.now()): AuthenticationChallenge {
            val challenge = json.decodeFromString<AuthenticationChallenge>(raw)
            require(challenge.schemaVersion == 1 && challengePattern.matches(challenge.challengeId) &&
                serverPattern.matches(challenge.serverId) && Instant.ofEpochSecond(challenge.expiresAt).isAfter(now)) {
                "Invalid or expired backend authentication challenge"
            }
            return challenge
        }
    }
}
