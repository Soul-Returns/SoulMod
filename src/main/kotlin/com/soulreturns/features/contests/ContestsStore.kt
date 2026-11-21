package com.soulreturns.features.contests

import com.soulreturns.core.events.Events
import com.soulreturns.features.contests.model.ApiError
import com.soulreturns.features.contests.model.ContestEdit
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestStates
import com.soulreturns.features.contests.model.ContestSummary
import com.soulreturns.features.contests.model.ContestTypeDescriptor
import com.soulreturns.features.contests.model.ContestUpdate
import com.soulreturns.features.contests.model.ContestsBody
import com.soulreturns.features.contests.model.CreateContestRequest
import com.soulreturns.features.contests.model.FeedEntry
import com.soulreturns.features.contests.model.FeedKinds
import com.soulreturns.features.contests.model.FriendUpdate
import com.soulreturns.features.contests.model.FriendUpdateKinds
import com.soulreturns.features.contests.model.FriendsBody
import com.soulreturns.features.contests.model.InviteSpec
import com.soulreturns.features.contests.model.MemberRuns
import com.soulreturns.features.contests.model.MemberStatuses
import com.soulreturns.features.contests.model.PlayerRef
import com.soulreturns.features.contests.model.Roles
import com.soulreturns.features.contests.model.UpdateKinds
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft

/** A value loaded from the backend plus its load state. [error] is the contract's error code. */
data class Remote<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val error: ApiError? = null,
    /** Epoch ms of the last successful load; 0 = never. */
    val fetchedAt: Long = 0L,
) {
    /** A value is shown, but the latest refresh failed. */
    val stale: Boolean get() = value != null && error != null
}

/** The outcome of a store action, delivered on the client thread. */
data class ActionResult(
    val ok: Boolean,
    /** The contract's error code (`already_friends`, `contest_full`, …) or a synthetic one ([ContestsApi]). */
    val code: String? = null,
    /** A sentence the UI can show as is. */
    val message: String? = null,
    val status: Int = 0,
    /** `field` of `invalid_body`, the `uuid` of `not_friends`/`already_member`. */
    val field: String? = null,
    val uuid: String? = null,
    /** The contest the action created or changed. */
    val contestId: Long? = null,
    /** `sent` or `accepted` for a friend request. */
    val friendResult: String? = null,
) {
    companion object {
        val OK = ActionResult(ok = true)
        internal fun of(status: Int, error: ApiError?, contestId: Long? = null) = ActionResult(
            ok = false,
            code = error?.error,
            message = ContestErrors.describe(error),
            status = status,
            field = error?.field,
            uuid = error?.uuid,
            contestId = contestId,
        )
    }
}

/** Live = realtime stream up and REST answering; Reconnecting = stream down; Offline = REST failing. */
enum class ContestConnection { LIVE, RECONNECTING, OFFLINE }

/** Older feed pages loaded on demand, on top of the snapshot's newest 50. */
data class FeedState(val entries: List<FeedEntry>, val more: Boolean, val loading: Boolean)

/** History pages (`/contests/history`). [next] null with [fetchedAt] > 0 = no more pages. */
data class HistoryState(
    val contests: List<ContestSummary> = emptyList(),
    val next: Long? = null,
    val loading: Boolean = false,
    val error: ApiError? = null,
    val fetchedAt: Long = 0L,
) {
    val exhausted: Boolean get() = fetchedAt > 0 && next == null
}

/**
 * Client-side state of Friends & Contests. **Client thread only**: every read and every action call
 * happens there, results land there (network work runs on [SoulScope]). [version] grows with every
 * change, so a screen can cheaply notice one; [addListener] is the push variant.
 *
 * Refetch rules (Mercure has no replay): a screen calls [openContest] / [refreshFriends] /
 * [refreshContests] when it opens; the store itself refetches on a realtime revision gap, on unknown or
 * state-less update kinds, after the stream reconnects, and every [POLL_MS] while a held contest is
 * running and realtime is down.
 */
object ContestsStore {
    private val logger = SoulLogger("Soul/Contests")

    /** Lazy fallback while a contest runs and realtime is down. */
    const val POLL_MS = 60_000L

    // ------------------------------------------------------------------ seams for tests

    /** Runs [block] on the client thread. */
    internal var post: (() -> Unit) -> Unit = { block ->
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) block() else mc.execute(block)
    }

    /** Runs network work off the client thread. */
    internal var launch: (suspend () -> Unit) -> Unit = { block -> SoulScope.io.launch { block() } }

    internal var now: () -> Long = { System.currentTimeMillis() }

    internal var selfUuidProvider: () -> String? = {
        try {
            Minecraft.getInstance().user?.profileId?.let(MojangUuid::undashed)
        } catch (_: Throwable) {
            null
        }
    }

    /** Signals go to the bus; tests capture them. */
    internal var emit: (com.soulreturns.core.events.Event) -> Unit = { Events.publish(it) }

    /** Ends already announced on this computer (kept on disk, so a restart within the hour does not replay them). */
    internal var endShown: (Long) -> Boolean = { ContestOutbox.endShown(it) }
    internal var markEndShown: (Long) -> Unit = { ContestOutbox.markEndShown(it) }

    // ------------------------------------------------------------------ state (read on the client thread)

    var version: Long = 0L
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    var friends: Remote<FriendsBody> = Remote()
        private set
    var contests: Remote<ContestsBody> = Remote()
        private set
    var types: Remote<List<ContestTypeDescriptor>> = Remote()
        private set
    var history: HistoryState = HistoryState()
        private set

    /** The contest the screen shows, if any ([openContest] / [closeContest]). */
    var openId: Long? = null
        private set

    private val snapshots = HashMap<Long, Remote<ContestSnapshot>>()
    private val olderFeed = HashMap<Long, FeedState>()
    private val memberRuns = HashMap<String, Remote<MemberRuns>>()

    var connection: ContestConnection = ContestConnection.OFFLINE
        private set

    /** Epoch ms the stream went down (while [connection] is RECONNECTING/OFFLINE); null when live. */
    var reconnectingSince: Long? = null
        private set

    /** Epoch ms the stream came back after being down (for "Back online", the UI decides how long). */
    var reconnectedAt: Long? = null
        private set

    /** Epoch ms of the last successful REST read of contest data ("data as of"). */
    var dataAsOf: Long = 0L
        private set

    /** The last failed action or refresh, for a generic error line. */
    var lastError: ActionResult? = null
        private set

    fun snapshot(id: Long): Remote<ContestSnapshot> = snapshots[id] ?: Remote()

    /** The open contest's snapshot state, or null when no contest is open. */
    fun open(): Remote<ContestSnapshot>? = openId?.let(::snapshot)

    /** Snapshot feed plus older pages, newest first. */
    fun feed(id: Long): List<FeedEntry> {
        val snap = snapshots[id]?.value?.feed ?: emptyList()
        val older = olderFeed[id]?.entries ?: return snap
        return ContestReducer.mergeFeed(older, snap)
    }

    /** Whether older feed entries exist on the server. */
    fun feedMore(id: Long): Boolean = olderFeed[id]?.more ?: (snapshots[id]?.value?.feedMore ?: false)

    fun feedLoading(id: Long): Boolean = olderFeed[id]?.loading ?: false

    fun memberRuns(contestId: Long, uuid: String): Remote<MemberRuns> = memberRuns[runsKey(contestId, uuid)] ?: Remote()

    /** Your runs for [contestId] still on disk: not sent yet, or rejected by the server (with the reason). */
    fun queuedRuns(contestId: Long): List<QueuedRun> = ContestOutbox.entries(contestId)

    /** Whether Soul records runs for [contestId] right now, and if not, why. Null when you do not play in it. */
    fun capture(contestId: Long): CaptureStatus? = ContestCaptures.status(contestId)

    /** Signed-in player's uuid, undashed. */
    fun selfUuid(): String? = selfUuidProvider()

    /** Held snapshots of contests that are running and in which you play or watch (for the HUD). */
    fun runningSnapshots(): List<ContestSnapshot> =
        snapshots.values.mapNotNull { it.value }.filter { it.contest.state == ContestStates.RUNNING }

    /** All held snapshots (capture gating reads them). */
    fun heldSnapshots(): List<ContestSnapshot> = snapshots.values.mapNotNull { it.value }

    // ------------------------------------------------------------------ refreshes

    fun refreshFriends(onDone: (ActionResult) -> Unit = {}) {
        friends = friends.copy(loading = true)
        changed()
        launch {
            val reply = ContestsApi.friends()
            post {
                if (reply.value != null) {
                    friends = Remote(reply.value, fetchedAt = now())
                    ShareAreaSync.onFriendsLoaded(reply.value)
                    netOk()
                    changed()
                    onDone(ActionResult.OK)
                } else {
                    friends = friends.copy(loading = false, error = reply.error)
                    fail(reply.status, reply.error, null, onDone)
                }
            }
        }
    }

    fun refreshContests(onDone: (ActionResult) -> Unit = {}) {
        contests = contests.copy(loading = true)
        changed()
        launch {
            val reply = ContestsApi.contests()
            post {
                if (reply.value != null) {
                    onContestsLoaded(reply.value)
                    onDone(ActionResult.OK)
                } else {
                    contests = contests.copy(loading = false, error = reply.error)
                    fail(reply.status, reply.error, null, onDone)
                }
            }
        }
    }

    /** `GET /contests/types` (cached 1 h by the HTTP client). */
    fun loadTypes(onDone: (ActionResult) -> Unit = {}) {
        types = types.copy(loading = true)
        changed()
        launch {
            val reply = ContestsApi.types()
            post {
                if (reply.value != null) {
                    types = Remote(reply.value.types, fetchedAt = now())
                    changed()
                    onDone(ActionResult.OK)
                } else {
                    types = types.copy(loading = false, error = reply.error)
                    fail(reply.status, reply.error, null, onDone)
                }
            }
        }
    }

    /** Shows [id] on the screen and refetches it (call on every screen open). */
    fun openContest(id: Long, onDone: (ActionResult) -> Unit = {}) {
        openId = id
        refreshContest(id, onDone)
    }

    fun closeContest() {
        openId = null
        changed()
    }

    fun refreshContest(id: Long, onDone: (ActionResult) -> Unit = {}) {
        snapshots[id] = snapshot(id).copy(loading = true)
        changed()
        launch {
            val reply = ContestsApi.snapshot(id)
            post {
                if (reply.value != null) {
                    acceptSnapshot(reply.value, live = true)
                    onDone(ActionResult.OK)
                } else {
                    snapshots[id] = snapshot(id).copy(loading = false, error = reply.error)
                    fail(reply.status, reply.error, id, onDone)
                }
            }
        }
    }

    /** Next page of history (`reset` starts over). */
    fun loadHistoryPage(reset: Boolean = false, onDone: (ActionResult) -> Unit = {}) {
        if (history.loading) return
        if (!reset && history.exhausted) return onDone(ActionResult.OK)
        val before = if (reset) null else history.next
        history = (if (reset) HistoryState() else history).copy(loading = true)
        changed()
        launch {
            val reply = ContestsApi.history(before)
            post {
                if (reply.value != null) {
                    val seen = history.contests.map { it.id }.toHashSet()
                    history = HistoryState(
                        contests = history.contests + reply.value.contests.filter { seen.add(it.id) },
                        next = reply.value.next,
                        fetchedAt = now(),
                    )
                    netOk()
                    changed()
                    onDone(ActionResult.OK)
                } else {
                    history = history.copy(loading = false, error = reply.error)
                    fail(reply.status, reply.error, null, onDone)
                }
            }
        }
    }

    fun loadMemberRuns(contestId: Long, uuid: String, onDone: (ActionResult) -> Unit = {}) {
        val key = runsKey(contestId, uuid)
        memberRuns[key] = memberRuns(contestId, uuid).copy(loading = true)
        changed()
        launch {
            val reply = ContestsApi.memberRuns(contestId, uuid)
            post {
                if (reply.value != null) {
                    memberRuns[key] = Remote(reply.value, fetchedAt = now())
                    netOk()
                    changed()
                    onDone(ActionResult.OK)
                } else {
                    memberRuns[key] = memberRuns(contestId, uuid).copy(loading = false, error = reply.error)
                    fail(reply.status, reply.error, contestId, onDone)
                }
            }
        }
    }

    fun loadOlderFeed(contestId: Long, onDone: (ActionResult) -> Unit = {}) {
        val oldest = feed(contestId).minOfOrNull { it.seq } ?: return onDone(ActionResult.OK)
        if (!feedMore(contestId) || feedLoading(contestId)) return onDone(ActionResult.OK)
        olderFeed[contestId] = (olderFeed[contestId] ?: FeedState(emptyList(), true, false)).copy(loading = true)
        changed()
        launch {
            val reply = ContestsApi.feed(contestId, before = oldest)
            post {
                val held = olderFeed[contestId] ?: FeedState(emptyList(), true, false)
                if (reply.value != null) {
                    olderFeed[contestId] = FeedState(ContestReducer.mergeFeed(held.entries, reply.value.feed), reply.value.more, false)
                    changed()
                    onDone(ActionResult.OK)
                } else {
                    olderFeed[contestId] = held.copy(loading = false)
                    fail(reply.status, reply.error, contestId, onDone)
                }
            }
        }
    }

    /** "Try again": refetch what is shown and send queued runs. */
    fun retryNow() {
        refreshContests()
        openId?.let { refreshContest(it) }
        ContestOutbox.flush()
    }

    // ------------------------------------------------------------------ friend actions

    fun addFriend(name: String, onDone: (ActionResult) -> Unit = {}) =
        friendCall(onDone, { ContestsApi.requestFriend(name) })

    fun acceptFriend(uuid: String, onDone: (ActionResult) -> Unit = {}) = friendCall(onDone, { ContestsApi.friendAction(uuid, "accept") })
    fun declineFriend(uuid: String, onDone: (ActionResult) -> Unit = {}) = friendCall(onDone, { ContestsApi.friendAction(uuid, "decline") })
    fun cancelFriendRequest(uuid: String, onDone: (ActionResult) -> Unit = {}) = friendCall(onDone, { ContestsApi.friendAction(uuid, "cancel") })
    fun removeFriend(uuid: String, onDone: (ActionResult) -> Unit = {}) = friendCall(onDone, { ContestsApi.friendAction(uuid, "remove") })
    fun blockPlayer(uuid: String, onDone: (ActionResult) -> Unit = {}) = friendCall(onDone, { ContestsApi.friendAction(uuid, "block") })
    fun unblockPlayer(uuid: String, onDone: (ActionResult) -> Unit = {}) = friendCall(onDone, { ContestsApi.friendAction(uuid, "unblock") })

    /** "Friends can see where I am": stored in config and sent to the server. */
    fun setShareArea(share: Boolean, onDone: (ActionResult) -> Unit = {}) = ShareAreaSync.set(share, onDone)

    internal fun friendCall(onDone: (ActionResult) -> Unit, call: suspend () -> ContestsApi.Reply<FriendsBody>) {
        launch {
            val reply = call()
            post {
                if (reply.value != null) {
                    friends = Remote(reply.value, fetchedAt = now())
                    netOk()
                    changed()
                    onDone(ActionResult(ok = true, friendResult = reply.value.result))
                } else {
                    fail(reply.status, reply.error, null, onDone)
                }
            }
        }
    }

    // ------------------------------------------------------------------ contest actions

    /** `POST /contests`; [ActionResult.contestId] is the new contest. Fills `profileId` when you play and it is null. */
    fun createContest(request: CreateContestRequest, onDone: (ActionResult) -> Unit = {}) {
        val body = if (request.profileId == null) request.copy(profileId = ContestCaptures.currentProfileUndashed()) else request
        snapshotCall(null, onDone) { ContestsApi.create(body) }
    }

    /**
     * Join an invitation, or ask to join a running contest (the server makes it a request when late joining
     * needs approval). Playing sends the current SkyBlock profile; it fails with `invalid_profile` when the
     * profile is not known yet.
     */
    fun join(contestId: Long, asRole: String = Roles.PARTICIPANT, onDone: (ActionResult) -> Unit = {}) {
        val profile = if (asRole == Roles.SPECTATOR) null else ContestCaptures.currentProfileUndashed()
        if (asRole != Roles.SPECTATOR && profile == null) {
            onDone(ActionResult(false, "invalid_profile", ContestErrors.describe("invalid_profile"), contestId = contestId))
            return
        }
        snapshotCall(contestId, onDone) { ContestsApi.join(contestId, asRole, profile) }
    }

    /** A spectator (or a never-joined invitee) asks to play after the start. */
    fun requestToJoin(contestId: Long, onDone: (ActionResult) -> Unit = {}) = join(contestId, Roles.PARTICIPANT, onDone)

    fun declineInvite(contestId: Long, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.contestAction(contestId, "decline") }

    fun leave(contestId: Long, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.contestAction(contestId, "leave") }

    fun setReady(contestId: Long, ready: Boolean, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.ready(contestId, ready) }

    fun invite(contestId: Long, invites: List<InviteSpec>, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.invite(contestId, invites) }

    fun setRole(contestId: Long, uuid: String, role: String, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.setRole(contestId, uuid, role) }

    fun approveJoin(contestId: Long, uuid: String, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.memberDecision(contestId, uuid, "approve") }

    fun rejectJoin(contestId: Long, uuid: String, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.memberDecision(contestId, uuid, "reject") }

    /** Before the start: removed. Running: disqualified with [reason] (0..120 chars). */
    fun removeMember(contestId: Long, uuid: String, reason: String, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.removeMember(contestId, uuid, reason.take(120)) }

    fun disqualify(contestId: Long, uuid: String, reason: String, onDone: (ActionResult) -> Unit = {}) =
        removeMember(contestId, uuid, reason, onDone)

    fun edit(contestId: Long, edit: ContestEdit, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.edit(contestId, edit) }

    /** Draft -> lobby (sends the invites). */
    fun openDraft(contestId: Long, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.contestAction(contestId, "open") }

    fun start(contestId: Long, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.contestAction(contestId, "start") }

    fun endEarly(contestId: Long, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.contestAction(contestId, "end") }

    fun cancel(contestId: Long, reason: String, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.cancel(contestId, reason.take(120)) }

    /** [reason] 1..120 chars. */
    fun voidRun(contestId: Long, runId: String, reason: String, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.voidRun(contestId, runId, reason.take(120)) }

    fun restoreRun(contestId: Long, runId: String, onDone: (ActionResult) -> Unit = {}) =
        snapshotCall(contestId, onDone) { ContestsApi.restoreRun(contestId, runId) }

    private fun snapshotCall(contestId: Long?, onDone: (ActionResult) -> Unit, call: suspend () -> ContestsApi.Reply<ContestSnapshot>) {
        launch {
            val reply = call()
            post {
                val snap = reply.value
                if (snap != null) {
                    acceptSnapshot(snap, live = true)
                    // Open member run lists of this contest may be stale now (void/restore/dq).
                    memberRuns.keys.filter { it.startsWith("${snap.contest.id}/") }.forEach { key ->
                        loadMemberRuns(snap.contest.id, key.substringAfter('/'))
                    }
                    refreshContests()
                    onDone(ActionResult(ok = true, contestId = snap.contest.id))
                } else {
                    fail(reply.status, reply.error, contestId, onDone)
                }
            }
        }
    }

    // ------------------------------------------------------------------ inbound

    /** A fresh list: snapshots of lobby/running contests you are in are fetched for the HUD and capture. */
    internal fun onContestsLoaded(body: ContestsBody) {
        val first = contests.fetchedAt == 0L
        contests = Remote(body, fetchedAt = now())
        netOk()
        changed()
        for (summary in body.invitations) {
            if (announcedInvites.add(summary.id)) {
                emit(ContestInvitedSignal(summary))
                ContestsChat.invited(summary)
            }
        }
        for (summary in body.active) {
            val held = snapshots[summary.id]?.value
            if (held == null || held.rev < summary.rev) refreshContest(summary.id)
        }
        // Ended while away: finished under an hour ago, never seen running in this session or announced here before.
        if (first) {
            val cutoff = now() - MISSED_END_WINDOW_MS
            body.history.filter { it.state == ContestStates.FINISHED && (it.endedAt ?: 0) >= cutoff && !endShown(it.id) }
                .forEach { missedEnds += it.id; refreshContest(it.id) }
        }
    }

    private val announcedInvites = HashSet<Long>()
    private val missedEnds = HashSet<Long>()
    private const val MISSED_END_WINDOW_MS = 3_600_000L

    /**
     * Store [snap] and fire signals for what changed against the held one. [live] is false only for tests
     * that seed state.
     */
    internal fun acceptSnapshot(snap: ContestSnapshot, live: Boolean) {
        val id = snap.contest.id
        val old = snapshots[id]?.value
        if (old != null && old.rev > snap.rev) {
            // An older answer that overtook a newer one: keep the newer.
            snapshots[id] = snapshots[id]!!.copy(loading = false, error = null)
            changed()
            return
        }
        snapshots[id] = Remote(snap, fetchedAt = now())
        dataAsOf = now()
        netOk()
        if (live) signalTransitions(old, snap)
        ContestCaptures.onSnapshot(snap)
        changed()
    }

    private fun signalTransitions(old: ContestSnapshot?, snap: ContestSnapshot) {
        val state = snap.contest.state
        if (old == null) {
            if (snap.contest.id in missedEnds && state == ContestStates.FINISHED) {
                missedEnds -= snap.contest.id
                endedSeen += snap.contest.id
                markEndShown(snap.contest.id)
                emit(ContestEndedSignal(snap, missed = true, won = won(snap)))
                ContestsChat.ended(snap)
            }
            return
        }
        val was = old.contest.state
        if (was != ContestStates.RUNNING && state == ContestStates.RUNNING) {
            emit(ContestStartedSignal(snap))
            ContestsChat.started(snap)
        }
        if (!ContestStates.isOver(was) && ContestStates.isOver(state) && endedSeen.add(snap.contest.id)) {
            markEndShown(snap.contest.id)
            emit(ContestEndedSignal(snap, missed = false, won = won(snap)))
            ContestsChat.ended(snap)
        } else if (was == ContestStates.FINISHED && state == ContestStates.FINISHED && old.rev != snap.rev) {
            standingsUpdated(old, snap, null)
        }
    }

    private val endedSeen = HashSet<Long>()

    private fun won(snap: ContestSnapshot): Boolean {
        val me = selfUuid() ?: return false
        return snap.standings.firstOrNull { it.uuid == me }?.rank == 1
    }

    private fun standingsUpdated(old: ContestSnapshot, snap: ContestSnapshot, runner: PlayerRef?) {
        val me = selfUuid()
        val oldRank = old.standings.firstOrNull { it.uuid == me }?.rank
        val newRank = snap.standings.firstOrNull { it.uuid == me }?.rank
        val orderBefore = old.standings.map { it.uuid to it.rank }
        val orderAfter = snap.standings.map { it.uuid to it.rank }
        if (orderBefore == orderAfter && old.standings.map { it.total } == snap.standings.map { it.total }) return
        emit(ContestStandingsUpdatedSignal(snap.contest.id, snap.contest.name, oldRank, newRank, runner))
        ContestsChat.standingsUpdated(snap, oldRank, newRank, runner)
    }

    /** Realtime `contest-update` (already on the client thread). */
    internal fun onContestUpdate(u: ContestUpdate) {
        val held = snapshots[u.contestId]?.value
        if (u.kind == UpdateKinds.MEMBER || u.kind == UpdateKinds.STATE || held == null) refreshContests()
        if (held == null) {
            // Not held: only fetch what a screen, the HUD or capture needs.
            if (u.contestId == openId) refreshContest(u.contestId)
            return
        }
        val outcome = ContestReducer.apply(held, u)
        logger.debug(
            "contest-update c=${u.contestId} rev=${u.rev} held=${held.rev} kind=${u.kind} ranks=${u.ranks.joinToString { "${it.uuid.take(6)}:${it.rank}" }}" +
                " -> ${if (outcome.stale) "stale" else if (outcome.refetch) "refetch" else outcome.snapshot?.standings?.joinToString { "${it.name}:${it.rank}" }}",
        )
        if (outcome.stale) return
        val next = outcome.snapshot
        if (outcome.refetch || next == null) {
            refreshContest(u.contestId)
            return
        }
        snapshots[u.contestId] = Remote(next, fetchedAt = now())
        dataAsOf = now()
        signalFeed(held, next, u.feed)
        if (held.contest.state == ContestStates.FINISHED && u.kind == UpdateKinds.RUN) {
            standingsUpdated(held, next, u.feed.firstOrNull { it.kind == FeedKinds.RUN }?.player ?: u.standing?.let { PlayerRef(it.uuid, it.name) })
        }
        if (u.kind == UpdateKinds.CHECK || u.kind == UpdateKinds.VOID) {
            val who = u.run?.uuid ?: u.standing?.uuid
            if (who != null && memberRuns.containsKey(runsKey(u.contestId, who))) loadMemberRuns(u.contestId, who)
        }
        if (u.kind == UpdateKinds.CHECK) {
            val me = selfUuid()
            val hint = u.run
            if (hint != null && hint.uuid == me && hint.check.state == com.soulreturns.features.contests.model.CheckStates.FLAGGED) {
                emit(ContestRunFlaggedSignal(next.contest.id, next.contest.name, hint.runId, null, hint.check.reason))
            }
        }
        changed()
    }

    private fun signalFeed(old: ContestSnapshot, snap: ContestSnapshot, entries: List<FeedEntry>) {
        val me = selfUuid() ?: return
        val name = snap.contest.name
        for (e in entries.sortedBy { it.seq }) {
            when (e.kind) {
                FeedKinds.PASS -> {
                    val passed = e.ref("passed")
                    val by = e.player
                    if (passed?.uuid == me && by != null) {
                        val mine = snap.standings.firstOrNull { it.uuid == me }
                        val above = mine?.rank?.let { r -> snap.standings.filter { (it.rank ?: Int.MAX_VALUE) < r }.maxByOrNull { it.rank ?: 0 } }
                        emit(ContestPassedSignal(snap.contest.id, name, by, mine?.rank, above?.let { it.total - mine.total }))
                    }
                }
                FeedKinds.GOAL -> {
                    val p = e.player ?: continue
                    val goal = snap.contest.goal.units ?: 0
                    val going = snap.standings.count { it.status == MemberStatuses.JOINED && it.units < goal }
                    emit(ContestPlayerFinishedSignal(snap.contest.id, name, p, e.long("total") ?: 0L, going, p.uuid == me))
                }
                FeedKinds.FLAG -> if (e.player?.uuid == me) {
                    val runId = e.str("runId") ?: ""
                    emit(ContestRunFlaggedSignal(snap.contest.id, name, runId, null, e.str("reason")))
                }
                else -> Unit
            }
        }
        if (old.contest.state != snap.contest.state) signalTransitions(old, snap)
    }

    /**
     * The outbox delivered a run. The realtime `run` event carries the same change (with everyone's ranks),
     * so the held snapshot is only refetched when the stream is down; an open own run list is reloaded.
     */
    internal fun onRunUploaded(contestId: Long, response: com.soulreturns.features.contests.model.RunUploadResponse) {
        val me = selfUuid()
        if (connection != ContestConnection.LIVE && snapshots.containsKey(contestId)) refreshContest(contestId)
        if (me != null && memberRuns.containsKey(runsKey(contestId, me))) loadMemberRuns(contestId, me)
        changed()
    }

    /** Realtime `friend-update`. */
    internal fun onFriendUpdate(u: FriendUpdate) {
        if (u.kind == FriendUpdateKinds.REQUEST) {
            emit(FriendRequestSignal(PlayerRef(u.uuid, u.name)))
            ContestsChat.friendRequest(u)
        }
        if (u.kind == FriendUpdateKinds.ACCEPTED) ContestsChat.friendAccepted(u)
        refreshFriends()
    }

    // ------------------------------------------------------------------ connection

    /** Called by [ContestsSync] from its tick with the stream state. */
    internal fun setStream(connected: Boolean) {
        val before = connection
        connection = when {
            connected && restFailing -> ContestConnection.OFFLINE
            connected -> ContestConnection.LIVE
            restFailing -> ContestConnection.OFFLINE
            else -> ContestConnection.RECONNECTING
        }
        if (connected && before != ContestConnection.LIVE && reconnectingSince != null) reconnectedAt = now()
        if (connected) reconnectingSince = null else if (reconnectingSince == null) reconnectingSince = now()
        if (before != connection) changed()
    }

    private var restFailing = false

    private fun netOk() {
        if (restFailing) {
            restFailing = false
            setStream(connection != ContestConnection.RECONNECTING && reconnectingSince == null)
        }
    }

    private fun fail(status: Int, error: ApiError?, contestId: Long?, onDone: (ActionResult) -> Unit) {
        val result = ActionResult.of(status, error, contestId)
        lastError = result
        val code = error?.error
        if (code == ContestsApi.ERR_TRANSPORT && !restFailing) {
            restFailing = true
            connection = ContestConnection.OFFLINE
            if (reconnectingSince == null) reconnectingSince = now()
        }
        logger.debug("contests call failed: $status ${error?.error} ${error?.message}")
        changed()
        onDone(result)
    }

    // ------------------------------------------------------------------ helpers

    private fun runsKey(contestId: Long, uuid: String) = "$contestId/$uuid"

    internal fun changed() {
        version++
        for (l in listeners.toList()) {
            try {
                l()
            } catch (t: Throwable) {
                logger.warn("contests listener threw", t)
            }
        }
    }

    /** Drop everything (account switch, tests). */
    internal fun reset() {
        friends = Remote(); contests = Remote(); types = Remote(); history = HistoryState()
        openId = null; snapshots.clear(); olderFeed.clear(); memberRuns.clear()
        announcedInvites.clear(); missedEnds.clear(); endedSeen.clear()
        connection = ContestConnection.OFFLINE; reconnectingSince = null; reconnectedAt = null
        dataAsOf = 0L; lastError = null; restFailing = false
        changed()
    }

    /** Test seam: put a snapshot in without signals. */
    internal fun seed(snap: ContestSnapshot) {
        snapshots[snap.contest.id] = Remote(snap, fetchedAt = now())
    }
}
