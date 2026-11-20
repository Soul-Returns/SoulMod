package com.soulreturns.data.hypixel

import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.MojangUuid
import kotlinx.serialization.json.JsonObject

/**
 * `GET /skyblock/profiles?uuid=<32-hex-undashed>` — the backend's pure proxy onto Hypixel's
 * `/v2/skyblock/profiles`.
 *
 * Everything goes through [BackendClient], which is what makes this endpoint safe to call from a
 * command: the backend limits it to **30 requests per minute per token** and answers with
 * `X-Backend-Expire-In: 60000`, so the shared response cache and single-flight absorb a user opening
 * `/sp` on every member of a dungeon roster. This is the one endpoint in the mod where that client
 * cache genuinely earns its keep.
 *
 * **Why via the backend at all:** Hypixel's endpoint needs an `API-Key` header. The backend holds one
 * key for everyone, so no user ever needs their own.
 */
object HypixelProfileClient {
    /** The Profile Viewer's lookups — user-initiated, bursty. */
    const val INTENT_LOOKUP = "spv-lookup"

    /** The self-load path — one per profile switch. Separated so the admin dashboard can tell them apart. */
    const val INTENT_SELF = "self-profile-load"

    /**
     * Synthetic status for "HTTP 200, but Hypixel's own `success` field said no".
     *
     * The backend passes Hypixel's body through verbatim, so the mod is the first place that can
     * notice. It is not an HTTP failure and it is not a malformed body, hence a status of its own —
     * outside the range of both real HTTP codes and [BackendResult.Failure]'s own constants.
     */
    const val HYPIXEL_REFUSED = -20

    /**
     * Fetch [uuidUndashed]'s profiles.
     *
     * @param uuidUndashed 32-hex, no dashes — validated here rather than trusted, because a dashed
     *   uuid reaches the backend as a 400 and a *lower-cased-elsewhere* one silently splits the
     *   cache key. Use [MojangUuid.undashed].
     * @param intent [INTENT_LOOKUP] or [INTENT_SELF].
     * @param bypassCache force a network round trip; only the dev diagnostics should need it.
     */
    suspend fun profiles(
        uuidUndashed: String,
        intent: String,
        bypassCache: Boolean = false,
    ): BackendResult<SkyblockProfiles> {
        if (!MojangUuid.isUndashed(uuidUndashed)) {
            return BackendResult.Failure(
                BackendResult.Failure.MALFORMED,
                "uuid must be 32-char hex without dashes, got '$uuidUndashed'",
            )
        }

        val result = BackendClient.get(
            endpoint = "/skyblock/profiles?uuid=$uuidUndashed",
            serializer = JsonObject.serializer(),
            intent = intent,
            bypassCache = bypassCache,
        )
        // `if (result is …)` — never `when (result)` over a sealed type (the $WhenMappings trap).
        if (result !is BackendResult.Ok) return result as BackendResult.Failure

        val parsed = SkyblockProfiles.from(result.value)
        if (!parsed.success) {
            return BackendResult.Failure(
                HYPIXEL_REFUSED,
                parsed.cause ?: "Hypixel refused the request",
            )
        }
        return BackendResult.Ok(parsed, result.fromCache, result.expiresAt)
    }
}
