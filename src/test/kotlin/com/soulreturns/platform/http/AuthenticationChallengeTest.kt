package com.soulreturns.platform.http

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AuthenticationChallengeTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private val valid = AuthenticationChallenge(1, "a".repeat(64), "b".repeat(40), now.epochSecond + 120)

    @Test
    fun acceptsFreshBackendChallengeWithAdditionalFields() {
        val raw = Json.encodeToString(valid).dropLast(1) + ",\"additional\":true}"
        assertEquals(valid, AuthenticationChallenge.decode(raw, now))
    }

    @Test
    fun rejectsExpiredOrMalformedProofBeforeJoiningMojang() {
        for (challenge in listOf(
            valid.copy(expiresAt = now.epochSecond),
            valid.copy(expiresAt = now.epochSecond - 1),
            valid.copy(schemaVersion = 2),
            valid.copy(challengeId = "a".repeat(63)),
            valid.copy(challengeId = "g".repeat(64)),
            valid.copy(serverId = "b".repeat(39)),
            valid.copy(serverId = "g".repeat(40)),
        )) {
            assertFails { AuthenticationChallenge.decode(Json.encodeToString(challenge), now) }
        }
        assertFails { AuthenticationChallenge.decode("{}", now) }
        assertFails { AuthenticationChallenge.decode("not-json", now) }
    }
}
