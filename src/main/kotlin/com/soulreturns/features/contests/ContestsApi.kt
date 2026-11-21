package com.soulreturns.features.contests

import com.soulreturns.features.contests.model.ApiError
import com.soulreturns.features.contests.model.ContestEdit
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestsBody
import com.soulreturns.features.contests.model.CreateContestRequest
import com.soulreturns.features.contests.model.FeedPage
import com.soulreturns.features.contests.model.FriendsBody
import com.soulreturns.features.contests.model.HistoryPage
import com.soulreturns.features.contests.model.InviteSpec
import com.soulreturns.features.contests.model.MemberRuns
import com.soulreturns.features.contests.model.RunUpload
import com.soulreturns.features.contests.model.RunUploadResponse
import com.soulreturns.features.contests.model.TypesBody
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The REST half of the contests/friends contract (`local://contests-contract.md`, v1), one function per
 * route. Every call goes through [BackendClient] (auth, 429 cooldowns, Alpha gate); GETs bypass the HTTP
 * cache (the backend sends `X-Backend-Expire-In: 0` anyway, except `/contests/types`).
 */
object ContestsApi {
    const val INTENT_FRIENDS = "friends"
    const val INTENT_CONTESTS = "contests"
    const val INTENT_RUNS = "contest-run"

    internal val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** A call's outcome: the decoded body, or the server's error code (or a synthetic one, see [ApiError]). */
    class Reply<T>(val value: T?, val status: Int, val error: ApiError?, val retryAfterMs: Long? = null) {
        val ok: Boolean get() = value != null
    }

    // ------------------------------------------------------------------ friends

    suspend fun friends(): Reply<FriendsBody> = get("/friends", FriendsBody.serializer(), INTENT_FRIENDS)

    suspend fun requestFriend(name: String): Reply<FriendsBody> =
        post("/friends/request", NameBody(name.trim()), NameBody.serializer(), FriendsBody.serializer(), INTENT_FRIENDS)

    /** `accept|decline|cancel|remove|block|unblock`. */
    suspend fun friendAction(uuid: String, action: String): Reply<FriendsBody> =
        post("/friends/${seg(uuid)}/$action", EmptyBody(), EmptyBody.serializer(), FriendsBody.serializer(), INTENT_FRIENDS)

    suspend fun setShareArea(share: Boolean): Reply<FriendsBody> =
        post("/friends/settings", ShareAreaBody(share), ShareAreaBody.serializer(), FriendsBody.serializer(), INTENT_FRIENDS)

    // ------------------------------------------------------------------ contests (reads)

    suspend fun types(): Reply<TypesBody> =
        get("/contests/types", TypesBody.serializer(), INTENT_CONTESTS, bypassCache = false)

    suspend fun contests(): Reply<ContestsBody> = get("/contests", ContestsBody.serializer(), INTENT_CONTESTS)

    suspend fun history(before: Long?, limit: Int = 20): Reply<HistoryPage> {
        val q = buildString {
            append("/contests/history?limit=").append(limit.coerceIn(1, 50))
            if (before != null) append("&before=").append(before)
        }
        return get(q, HistoryPage.serializer(), INTENT_CONTESTS)
    }

    suspend fun snapshot(id: Long): Reply<ContestSnapshot> = get("/contests/$id", ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun feed(id: Long, before: Long, limit: Int = 50): Reply<FeedPage> =
        get("/contests/$id/feed?before=$before&limit=${limit.coerceIn(1, 100)}", FeedPage.serializer(), INTENT_CONTESTS)

    suspend fun memberRuns(id: Long, uuid: String): Reply<MemberRuns> =
        get("/contests/$id/members/${seg(uuid)}/runs", MemberRuns.serializer(), INTENT_CONTESTS)

    // ------------------------------------------------------------------ contests (writes)

    suspend fun create(request: CreateContestRequest): Reply<ContestSnapshot> =
        post("/contests", request, CreateContestRequest.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun join(id: Long, asRole: String, profileId: String?): Reply<ContestSnapshot> =
        post("/contests/$id/join", JoinBody(asRole, profileId), JoinBody.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun ready(id: Long, ready: Boolean): Reply<ContestSnapshot> =
        post("/contests/$id/ready", ReadyBody(ready), ReadyBody.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun invite(id: Long, invites: List<InviteSpec>): Reply<ContestSnapshot> =
        post("/contests/$id/invite", InvitesBody(invites), InvitesBody.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun setRole(id: Long, uuid: String, role: String): Reply<ContestSnapshot> =
        post("/contests/$id/members/${seg(uuid)}/role", RoleBody(role), RoleBody.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    /** `approve|reject`. */
    suspend fun memberDecision(id: Long, uuid: String, action: String): Reply<ContestSnapshot> =
        post("/contests/$id/members/${seg(uuid)}/$action", EmptyBody(), EmptyBody.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun removeMember(id: Long, uuid: String, reason: String): Reply<ContestSnapshot> =
        post("/contests/$id/members/${seg(uuid)}/remove", ReasonBody(reason), ReasonBody.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun edit(id: Long, edit: ContestEdit): Reply<ContestSnapshot> =
        post("/contests/$id/edit", editBody(edit), JsonObject.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    /** Body-less actions: `decline|leave|open|start|end`. */
    suspend fun contestAction(id: Long, action: String): Reply<ContestSnapshot> =
        post("/contests/$id/$action", EmptyBody(), EmptyBody.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun cancel(id: Long, reason: String): Reply<ContestSnapshot> =
        post("/contests/$id/cancel", ReasonBody(reason), ReasonBody.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun voidRun(id: Long, runId: String, reason: String): Reply<ContestSnapshot> =
        post("/contests/$id/runs/${seg(runId)}/void", ReasonBody(reason), ReasonBody.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun restoreRun(id: Long, runId: String): Reply<ContestSnapshot> =
        post("/contests/$id/runs/${seg(runId)}/restore", EmptyBody(), EmptyBody.serializer(), ContestSnapshot.serializer(), INTENT_CONTESTS)

    suspend fun uploadRun(id: Long, body: RunUpload): Reply<RunUploadResponse> =
        post("/contests/$id/runs", body, RunUpload.serializer(), RunUploadResponse.serializer(), INTENT_RUNS)

    internal fun editBody(edit: ContestEdit): JsonObject = buildJsonObject {
        edit.name?.let { put("name", it.trim()) }
        edit.goal?.let { put("goal", json.encodeToJsonElement(com.soulreturns.features.contests.model.GoalInput.serializer(), it)) }
        if (edit.clearStartsAt) put("startsAt", JsonNull) else edit.startsAt?.let { put("startsAt", it) }
        if (edit.clearEndsAt) put("endsAt", JsonNull) else edit.endsAt?.let { put("endsAt", it) }
        edit.allowLateJoin?.let { put("allowLateJoin", it) }
    }

    // ------------------------------------------------------------------ plumbing

    @Serializable private class EmptyBody
    @Serializable private data class NameBody(val name: String)
    @Serializable private data class ShareAreaBody(val shareArea: Boolean)
    @Serializable private data class ReadyBody(val ready: Boolean)
    @Serializable private data class RoleBody(val role: String)
    @Serializable private data class ReasonBody(val reason: String)
    @Serializable private data class InvitesBody(val invites: List<InviteSpec>)

    /** `profileId` is omitted for spectators (`{"as":"spectator"}`). */
    @Serializable
    private data class JoinBody(@SerialName("as") val asRole: String, val profileId: String? = null)

    private fun seg(raw: String): String = URLEncoder.encode(raw, StandardCharsets.UTF_8)

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, intent: String, bypassCache: Boolean = true): Reply<T> {
        val result = BackendClient.get(path, serializer, intent, bypassCache = bypassCache)
        if (result is BackendResult.Ok) return Reply(result.value, 200, null)
        val failure = result as BackendResult.Failure
        return Reply(null, failure.status, parseError(failure), failure.retryAfter?.toMillis())
    }

    private suspend fun <B, T> post(
        path: String,
        body: B,
        bodySerializer: KSerializer<B>,
        serializer: KSerializer<T>,
        intent: String,
    ): Reply<T> {
        val result = BackendClient.post(path, body, bodySerializer, intent)
        if (result is BackendResult.Ok) {
            return try {
                Reply(json.decodeFromJsonElement(serializer, result.value), 200, null)
            } catch (t: Throwable) {
                Reply(null, BackendResult.Failure.MALFORMED, ApiError(ERR_MALFORMED, "response did not decode: ${t.message}"))
            }
        }
        val failure = result as BackendResult.Failure
        return Reply(null, failure.status, parseError(failure), failure.retryAfter?.toMillis())
    }

    /** Synthetic codes for failures that never carried a server error body. */
    const val ERR_TRANSPORT = "transport"
    const val ERR_MALFORMED = "malformed"
    const val ERR_ALPHA = "alpha"
    const val ERR_NO_AUTH = "no_auth"
    const val ERR_UNKNOWN = "unknown"

    private val ERROR_CODE = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"")
    private val ERROR_MESSAGE = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val ERROR_FIELD = Regex("\"field\"\\s*:\\s*\"([^\"]+)\"")
    private val ERROR_UUID = Regex("\"uuid\"\\s*:\\s*\"([^\"]+)\"")
    private val ERROR_LOCKED = Regex("\"lockedProfileId\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * [BackendClient] keeps only `HTTP <status> — <first 160 chars of the body>` of a failure, so the
     * error object is read from that snippet (whole JSON when it fits, else by key).
     */
    internal fun parseError(failure: BackendResult.Failure): ApiError {
        val status = failure.status
        if (status == BackendResult.Failure.ALPHA_BLOCKED) return ApiError(ERR_ALPHA, failure.message)
        if (status == BackendResult.Failure.NO_AUTH) return ApiError(ERR_NO_AUTH, failure.message)
        if (status == BackendResult.Failure.MALFORMED) return ApiError(ERR_MALFORMED, failure.message)
        if (status < 0) return ApiError(ERR_TRANSPORT, failure.message)
        val text = failure.message
        val start = text.indexOf('{')
        if (start >= 0) {
            val snippet = text.substring(start)
            try {
                val parsed = json.decodeFromString(ApiError.serializer(), snippet)
                if (parsed.error.isNotEmpty()) return parsed
            } catch (_: Throwable) {
                // truncated body: fall through to the key scan
            }
            val code = ERROR_CODE.find(snippet)?.groupValues?.get(1)
            if (code != null) {
                return ApiError(
                    error = code,
                    message = ERROR_MESSAGE.find(snippet)?.groupValues?.get(1),
                    field = ERROR_FIELD.find(snippet)?.groupValues?.get(1),
                    uuid = ERROR_UUID.find(snippet)?.groupValues?.get(1),
                    lockedProfileId = ERROR_LOCKED.find(snippet)?.groupValues?.get(1),
                )
            }
        }
        if (status == 429) return ApiError("rate_limited", text)
        if (status >= 500) return ApiError(ERR_TRANSPORT, text)
        return ApiError(ERR_UNKNOWN, text)
    }
}
