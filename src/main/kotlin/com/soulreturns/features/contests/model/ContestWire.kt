package com.soulreturns.features.contests.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Wire types of the contests/friends contract (v1). Every enum travels as a String: an unknown value
// from a newer backend must never fail a decode (see the `Known` helpers below). Defaults exist so an
// absent key decodes; every field the contract documents as nullable is nullable.

/** `{"uuid":"<32hex>","name":"<username>"}`. */
@Serializable
data class PlayerRef(val uuid: String = "", val name: String = "")

/** Goal as the server describes it. [units] is null for goal kinds without units. */
@Serializable
data class Goal(val kind: String = GoalKinds.UNITS, val units: Int? = null, val label: String = "")

@Serializable
data class Presence(
    val online: Boolean = false,
    val inGame: Boolean = false,
    val lastOnlineAt: Long? = null,
    val areaHidden: Boolean = false,
    val area: String? = null,
    val subLocation: String? = null,
)

@Serializable
data class CheckCounts(val verified: Int = 0, val pending: Int = 0, val flagged: Int = 0)

@Serializable
data class StandingRow(
    val uuid: String = "",
    val name: String = "",
    val role: String = "",
    val status: String = "",
    val rank: Int? = null,
    val shared: Boolean = false,
    val rankBefore: Int? = null,
    val units: Int = 0,
    val goal: Int = 0,
    val total: Long = 0,
    val best: Long = 0,
    val avg: Long? = null,
    val lastRunAt: Long? = null,
    val lastValue: Long? = null,
    val unpriced: Int = 0,
    val voided: Int = 0,
    val extra: Int = 0,
    val checks: CheckCounts = CheckCounts(),
    val medianDurationMs: Long? = null,
    val untimed: Int = 0,
    val goalReachedAt: Long? = null,
    val dqReason: String? = null,
)

/** An open late-join request: `{"role":"participant","at":<ms>}`. */
@Serializable
data class MemberRequest(val role: String = "", val at: Long? = null)

@Serializable
data class Member(
    val uuid: String = "",
    val name: String = "",
    val role: String = "",
    val status: String = "",
    val ready: Boolean = false,
    val invitedBy: PlayerRef? = null,
    val invitedAt: Long? = null,
    val joinedAt: Long? = null,
    val profileLocked: Boolean = false,
    val request: MemberRequest? = null,
    val dqReason: String? = null,
    val presence: Presence = Presence(),
)

@Serializable
data class RunItem(
    val name: String = "",
    val id: String? = null,
    val count: Long = 0,
    val unitPrice: Long? = null,
    val value: Long = 0,
)

@Serializable
data class RunCheck(val state: String = CheckStates.PENDING, val reason: String? = null, val at: Long? = null)

@Serializable
data class RunVoid(val by: PlayerRef? = null, val reason: String? = null, val at: Long? = null)

@Serializable
data class Run(
    val runId: String = "",
    val seq: Int? = null,
    val n: Int? = null,
    val counted: Boolean = false,
    val finishedAt: Long = 0,
    val startedAt: Long? = null,
    val receivedAt: Long? = null,
    val durationMs: Long? = null,
    val partial: Boolean = false,
    val late: Boolean = false,
    val value: Long = 0,
    val items: List<RunItem> = emptyList(),
    val unpriced: Int = 0,
    val powder: Map<String, Long> = emptyMap(),
    val check: RunCheck = RunCheck(),
    val voided: RunVoid? = null,
)

/**
 * One feed line. [data] stays raw because its shape depends on [kind] (contract table 3); read it with
 * [str] / [long] / [int] / [ref]. Unknown kinds are to be ignored by the UI.
 */
@Serializable
data class FeedEntry(
    val seq: Long = 0,
    val at: Long = 0,
    val kind: String = "",
    val player: PlayerRef? = null,
    val actor: PlayerRef? = null,
    val data: JsonObject = JsonObject(emptyMap()),
) {
    fun str(key: String): String? = (data[key] as? JsonPrimitive)?.contentOrNull
    fun long(key: String): Long? = (data[key] as? JsonPrimitive)?.longOrNull
    fun int(key: String): Int? = long(key)?.toInt()
    fun bool(key: String): Boolean? = (data[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    fun ref(key: String): PlayerRef? {
        val o = data[key] as? JsonObject ?: return null
        val uuid = (o["uuid"] as? JsonPrimitive)?.contentOrNull ?: return null
        return PlayerRef(uuid, (o["name"] as? JsonPrimitive)?.contentOrNull ?: "")
    }
}

// ------------------------------------------------------------------ contest-type descriptor

@Serializable
data class Metric(val label: String = "", val format: String = MetricFormats.COINS, val higherIsBetter: Boolean = true)

@Serializable
data class UnitNames(val singular: String = "run", val plural: String = "runs")

@Serializable
data class GoalKind(
    val kind: String = "",
    val label: String = "",
    val min: Int? = null,
    val max: Int? = null,
    @SerialName("default") val defaultUnits: Int? = null,
)

@Serializable
data class RunFields(
    val duration: Boolean = false,
    val items: Boolean = false,
    val powder: Boolean = false,
    val partial: Boolean = false,
)

@Serializable
data class ContestTypeDescriptor(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val area: String? = null,
    val enabled: Boolean = true,
    val metric: Metric = Metric(),
    val unit: UnitNames = UnitNames(),
    val goalKinds: List<GoalKind> = emptyList(),
    val runFields: RunFields = RunFields(),
    val notableUnitValue: Long? = null,
    val rules: List<String> = emptyList(),
    val verification: String? = null,
)

@Serializable
data class TypesBody(val types: List<ContestTypeDescriptor> = emptyList())

// ------------------------------------------------------------------ friends

@Serializable
data class FriendsMe(val uuid: String = "", val name: String = "", val shareArea: Boolean = true)

/** A friend's lobby/running contest that you are also a member of. */
@Serializable
data class FriendContest(val id: Long = 0, val name: String = "", val state: String = "", val role: String = "")

@Serializable
data class Friend(
    val uuid: String = "",
    val name: String = "",
    val since: Long? = null,
    val presence: Presence = Presence(),
    val contests: List<FriendContest> = emptyList(),
)

/** A row of `incoming`, `outgoing` or `blocked`. */
@Serializable
data class FriendRequestRow(val uuid: String = "", val name: String = "", val at: Long? = null)

/** `GET /friends` and every friend mutation's answer; [result] only on `/friends/request`. */
@Serializable
data class FriendsBody(
    val me: FriendsMe = FriendsMe(),
    val friends: List<Friend> = emptyList(),
    val incoming: List<FriendRequestRow> = emptyList(),
    val outgoing: List<FriendRequestRow> = emptyList(),
    val blocked: List<FriendRequestRow> = emptyList(),
    val result: String? = null,
)

// ------------------------------------------------------------------ contests list / history

@Serializable
data class SummaryMe(
    val role: String = "",
    val status: String = "",
    val rank: Int? = null,
    val shared: Boolean = false,
    val units: Int = 0,
    val total: Long = 0,
)

@Serializable
data class TopRow(
    val uuid: String = "",
    val name: String = "",
    val rank: Int? = null,
    val shared: Boolean = false,
    val units: Int = 0,
    val total: Long = 0,
)

@Serializable
data class ContestSummary(
    val id: Long = 0,
    val type: String = "",
    val typeTitle: String = "",
    val name: String = "",
    val state: String = "",
    val rev: Long = 0,
    val owner: PlayerRef = PlayerRef(),
    val goal: Goal = Goal(),
    val startsAt: Long? = null,
    val startedAt: Long? = null,
    val endsAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val players: Int = 0,
    val spectators: Int = 0,
    val me: SummaryMe = SummaryMe(),
    val top: List<TopRow> = emptyList(),
    val invitedBy: PlayerRef? = null,
    val invitedAt: Long? = null,
)

@Serializable
data class ContestsBody(
    val schemaVersion: Int = 1,
    val serverTime: Long = 0,
    val active: List<ContestSummary> = emptyList(),
    val invitations: List<ContestSummary> = emptyList(),
    val history: List<ContestSummary> = emptyList(),
)

@Serializable
data class HistoryPage(val contests: List<ContestSummary> = emptyList(), val next: Long? = null)

// ------------------------------------------------------------------ snapshot

@Serializable
data class ContestInfo(
    val id: Long = 0,
    val type: String = "",
    val name: String = "",
    val state: String = "",
    val owner: PlayerRef = PlayerRef(),
    val goal: Goal = Goal(),
    val allowLateJoin: Boolean = false,
    val maxPlayers: Int = 10,
    val createdAt: Long? = null,
    val startsAt: Long? = null,
    val startedAt: Long? = null,
    val endsAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val canceledAt: Long? = null,
    val canceledReason: String? = null,
    val canceledBy: PlayerRef? = null,
    val pricesFrozenAt: Long? = null,
    val lateUploadsUntil: Long? = null,
)

/** `me` of a snapshot. Render buttons from [can] (contract 5: `upload, ready, leave, join, …`). */
@Serializable
data class SnapshotMe(
    val role: String = "",
    val status: String = "",
    val ready: Boolean = false,
    val profileId: String? = null,
    val plays: Boolean = false,
    val can: List<String> = emptyList(),
) {
    fun can(action: String): Boolean = action in can
}

@Serializable
data class ContestSnapshot(
    val schemaVersion: Int = 1,
    val serverTime: Long = 0,
    val rev: Long = 0,
    val contest: ContestInfo = ContestInfo(),
    val type: ContestTypeDescriptor = ContestTypeDescriptor(),
    val me: SnapshotMe = SnapshotMe(),
    val members: List<Member> = emptyList(),
    val standings: List<StandingRow> = emptyList(),
    val feed: List<FeedEntry> = emptyList(),
    val feedMore: Boolean = false,
)

@Serializable
data class FeedPage(val feed: List<FeedEntry> = emptyList(), val more: Boolean = false)

@Serializable
data class MemberRuns(val rev: Long = 0, val standing: StandingRow? = null, val runs: List<Run> = emptyList())

// ------------------------------------------------------------------ requests

@Serializable
data class GoalInput(val kind: String, val units: Int)

@Serializable
data class InviteSpec(val uuid: String, val role: String)

/** `POST /contests`. No defaults: every key is sent, nulls included (contract 5). */
@Serializable
data class CreateContestRequest(
    val type: String,
    val name: String,
    val goal: GoalInput,
    val startsAt: Long?,
    val endsAt: Long?,
    val allowLateJoin: Boolean,
    val draft: Boolean,
    val profileId: String?,
    val invites: List<InviteSpec>,
)

/**
 * `POST /contests/{id}/edit` fields; null = not sent (unchanged). [clearStartsAt] / [clearEndsAt] send an
 * explicit null ("start by hand" / "no deadline").
 */
data class ContestEdit(
    val name: String? = null,
    val goal: GoalInput? = null,
    val startsAt: Long? = null,
    val clearStartsAt: Boolean = false,
    val endsAt: Long? = null,
    val clearEndsAt: Boolean = false,
    val allowLateJoin: Boolean? = null,
)

// ------------------------------------------------------------------ run upload (nucleus-profit)

@Serializable
data class UploadItem(val name: String, val id: String?, val count: Long)

/**
 * The body of `POST /contests/{id}/runs` and, byte for byte, what the outbox stores. No defaults except
 * the optional fields, so every required key is always encoded.
 */
@Serializable
data class RunUpload(
    val schemaVersion: Int,
    val runId: String,
    val seq: Int?,
    val profileId: String,
    val finishedAt: Long,
    val startedAt: Long?,
    val durationMs: Long?,
    val partial: Boolean,
    val items: List<UploadItem>,
    val powder: Map<String, Long>,
)

@Serializable
data class RunUploadResponse(val run: Run = Run(), val rev: Long = 0, val standing: StandingRow? = null)

// ------------------------------------------------------------------ realtime

@Serializable
data class RankRow(val uuid: String = "", val rank: Int? = null, val shared: Boolean = false)

@Serializable
data class RunCheckHint(val uuid: String = "", val runId: String = "", val check: RunCheck = RunCheck())

/** Data of a `contest-update` realtime event. */
@Serializable
data class ContestUpdate(
    val contestId: Long = 0,
    val rev: Long = 0,
    val kind: String = "",
    val state: String? = null,
    val standing: StandingRow? = null,
    val ranks: List<RankRow> = emptyList(),
    val feed: List<FeedEntry> = emptyList(),
    val run: RunCheckHint? = null,
)

/** Data of a `friend-update` realtime event. */
@Serializable
data class FriendUpdate(val kind: String = "", val uuid: String = "", val name: String = "")

/** `{"error":"<code>","message":"…"}` plus the extra keys some codes carry. */
@Serializable
data class ApiError(
    val error: String = "",
    val message: String? = null,
    val field: String? = null,
    val uuid: String? = null,
    val lockedProfileId: String? = null,
)
