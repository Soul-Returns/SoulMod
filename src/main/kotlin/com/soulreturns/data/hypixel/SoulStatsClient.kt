package com.soulreturns.data.hypixel

import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.MojangUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /player/{uuid}/soul-stats` — the read path for statistics **Soul itself** collected, as
 * opposed to anything Hypixel publishes (mineshaft visits and corpses first; other trackers later).
 *
 * ### The envelope is namespaced on purpose
 * Each producing feature owns one key under [SoulStatsEnvelope.namespaces], and the backend builds
 * the response by asking its registered providers. So a new tracker adds a namespace on both sides
 * without renegotiating this contract, and a client that has never heard of a namespace ignores it.
 * The response is *not* folded into `/skyblock/profiles`, which must stay a verbatim Hypixel proxy.
 *
 * ### Privacy is the point, not a formality
 * This publishes grind data the player never had to share. So: opt-in, default **off**
 * (`profileViewer.shareSoulStats`, pushed to the backend on the presence payload), the owner can
 * always see their own regardless of the flag, and there is no way to enumerate players.
 *
 * ### Every failure is silence
 * [soulStats] returns null for *all* of "not shared" (403), "unknown player" (404), rate limited
 * (429) and "backend unreachable". The consumer hides the feature — no error line, no empty state,
 * because a player looking at someone who did not opt in has not done anything wrong. [lastOutcome]
 * exists so that silence is still diagnosable from `/soul dev`.
 *
 * The first namespace is `mineshaft` (port doc 04); the map stays open, so a later tracker adds one
 * without either side renegotiating this contract.
 */
object SoulStatsClient {
    const val INTENT = "spv-soul-stats"

    const val CODE_NOT_CALLED = "not-called"

    /** A namespace came back. */
    const val CODE_OK = "ok"

    /**
     * The endpoint answered and the target is visible to us, but every provider had nothing to say
     * about them — normally a player who has never used a tracked feature.
     */
    const val CODE_NO_NAMESPACES = "no-namespaces"

    /** The target has not opted in (403), or a body said so. */
    const val CODE_NOT_SHARED = "not-shared"

    /** Unknown to the backend (404), rate limited, or unreachable. */
    const val CODE_UNAVAILABLE = "unavailable"

    /** We never asked — the uuid was not usable. */
    const val CODE_BAD_REQUEST = "bad-request"

    /** Why the last call returned what it did — for `/soul dev`, since failures are invisible. */
    @Volatile
    var lastOutcome: String = "not called yet"
        private set

    /**
     * [lastOutcome]'s reason as one of the `CODE_*` constants above, so a diagnostic can branch on *why* the
     * answer was empty instead of pattern-matching the human-readable text. A plain `String` rather
     * than an enum: a subject `when` over a Kotlin enum emits the `$WhenMappings` class that Fabric's
     * KnotClassLoader can fail to resolve (see CLAUDE.md).
     */
    @Volatile
    var lastOutcomeCode: String = CODE_NOT_CALLED
        private set

    /**
     * Fetch [uuidUndashed]'s Soul-collected stats, or null when there is nothing to show.
     *
     * @param ownProfile true when this is the local player looking at themselves. Only used to make
     *   [lastOutcome] readable — the *authorisation* decision is the backend's, always.
     */
    suspend fun soulStats(uuidUndashed: String, ownProfile: Boolean = false): SoulStatsEnvelope? {
        if (!MojangUuid.isUndashed(uuidUndashed)) {
            lastOutcome = "not attempted: '$uuidUndashed' is not a 32-hex uuid"
            lastOutcomeCode = CODE_BAD_REQUEST
            return null
        }

        val result = BackendClient.get(
            endpoint = "/player/$uuidUndashed/soul-stats",
            serializer = SoulStatsEnvelope.serializer(),
            intent = INTENT,
        )
        if (result !is BackendResult.Ok) {
            val failure = result as BackendResult.Failure
            lastOutcome = when (failure.status) {
                403 -> "hidden: that player has not opted in to sharing Soul stats"
                404 -> "hidden: the backend has no Soul stats for that player"
                429 -> "hidden: rate limited (${failure.retryAfter?.seconds ?: "?"}s)"
                else -> "hidden: ${failure.message}"
            }
            lastOutcomeCode = if (failure.status == 403) CODE_NOT_SHARED else CODE_UNAVAILABLE
            return null
        }

        val envelope = result.value
        if (!envelope.shared && !ownProfile) {
            // Belt and braces: the backend already enforces this, but a `shared: false` body that
            // still carried namespaces must not be rendered.
            lastOutcome = "hidden: shared=false"
            lastOutcomeCode = CODE_NOT_SHARED
            return null
        }
        // `shared` is reported even here: when the answer is "nothing to show", whether the target
        // opted in is the one thing that distinguishes "not shared" from "shared, but no feature has
        // registered a namespace yet" — and those want completely different follow-up.
        if (envelope.namespaces.isEmpty()) {
            lastOutcome = "no namespaces yet (schemaVersion=${envelope.schemaVersion}, " +
                "shared=${envelope.shared})" + if (result.fromCache) " (cached)" else ""
            lastOutcomeCode = CODE_NO_NAMESPACES
            return null
        }
        lastOutcome = "ok: ${envelope.namespaces.keys.joinToString()} (shared=${envelope.shared})" +
            if (result.fromCache) " (cached)" else ""
        lastOutcomeCode = CODE_OK
        return envelope
    }
}

/**
 * The `soul-stats` envelope.
 *
 * @property schemaVersion bumped only for a **breaking** change to the envelope itself. Namespaces
 *   are additive-only, so adding one never moves this.
 * @property shared the target's opt-in state. The owner sees their own with this false.
 * @property namespaces feature key → that feature's payload, left as a [JsonObject] so this client
 *   never needs updating when a namespace's contents grow. The feature that owns a namespace parses
 *   its own.
 */
@Serializable
data class SoulStatsEnvelope(
    val schemaVersion: Int = 1,
    val shared: Boolean = false,
    val namespaces: Map<String, JsonObject> = emptyMap(),
) {
    /** The raw payload for one namespace, or null if the backend did not send it. */
    fun namespace(key: String): JsonObject? = namespaces[key]
}
