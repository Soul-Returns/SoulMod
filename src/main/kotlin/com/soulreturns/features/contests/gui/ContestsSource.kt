package com.soulreturns.features.contests.gui

import com.soulreturns.features.contests.ActionResult
import com.soulreturns.features.contests.CaptureStatus
import com.soulreturns.features.contests.ContestConnection
import com.soulreturns.features.contests.ContestsStore
import com.soulreturns.features.contests.HistoryState
import com.soulreturns.features.contests.QueuedRun
import com.soulreturns.features.contests.Remote
import com.soulreturns.features.contests.model.ContestEdit
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestTypeDescriptor
import com.soulreturns.features.contests.model.ContestsBody
import com.soulreturns.features.contests.model.CreateContestRequest
import com.soulreturns.features.contests.model.FeedEntry
import com.soulreturns.features.contests.model.FriendsBody
import com.soulreturns.features.contests.model.InviteSpec
import com.soulreturns.features.contests.model.MemberRuns

typealias Done = (ActionResult) -> Unit

/**
 * Everything the contests screens, HUD and reveal read and do, in one seam: [StoreContestsSource] is
 * the live [ContestsStore]; tests and `/soul dev contests fixture` pass a fixed one. The screens never
 * compute a score: totals, ranks and checks are the server's.
 */
interface ContestsSource {
    val selfUuid: String
    val selfName: String

    fun friends(): Remote<FriendsBody>
    fun contests(): Remote<ContestsBody>
    fun history(): HistoryState
    fun snapshot(id: Long): Remote<ContestSnapshot>
    fun feed(id: Long): List<FeedEntry>
    fun runs(id: Long, uuid: String): Remote<MemberRuns>
    fun types(): List<ContestTypeDescriptor>
    fun connection(): Connection
    fun queued(contestId: Long): List<QueuedRun>
    fun capture(contestId: Long): CaptureStatus?

    fun openContest(id: Long)
    fun refreshFriends()
    fun refreshContests()
    fun loadTypes()
    fun loadHistory(reset: Boolean)
    fun refreshContest(id: Long)
    fun loadRuns(id: Long, uuid: String)
    fun retryNow()

    /** The screen closed: stop holding the opened contest live. */
    fun closeContest()

    fun addFriend(name: String, done: Done)
    fun acceptFriend(uuid: String, done: Done)
    fun declineFriend(uuid: String, done: Done)
    fun cancelFriendRequest(uuid: String, done: Done)
    fun removeFriend(uuid: String, done: Done)
    fun blockPlayer(uuid: String, done: Done)
    fun unblockPlayer(uuid: String, done: Done)
    fun setShareArea(on: Boolean, done: Done)

    fun createContest(request: CreateContestRequest, done: Done)
    fun join(id: Long, asRole: String, done: Done)
    fun declineInvite(id: Long, done: Done)
    fun leave(id: Long, done: Done)
    fun setReady(id: Long, ready: Boolean, done: Done)
    fun invite(id: Long, invites: List<InviteSpec>, done: Done)
    fun setRole(id: Long, uuid: String, role: String, done: Done)
    fun approveJoin(id: Long, uuid: String, done: Done)
    fun rejectJoin(id: Long, uuid: String, done: Done)
    fun removeMember(id: Long, uuid: String, reason: String, done: Done)
    fun edit(id: Long, edit: ContestEdit, done: Done)
    fun openDraft(id: Long, done: Done)
    fun start(id: Long, done: Done)
    fun endEarly(id: Long, done: Done)
    fun cancel(id: Long, reason: String, done: Done)
    fun voidRun(id: Long, runId: String, reason: String, done: Done)
    fun restoreRun(id: Long, runId: String, done: Done)
}

/**
 * Connection to Soul's server as the screens show it: [LIVE], [RECONNECTING] (the stream dropped
 * [since]), [OFFLINE] (REST failed; data from [dataAt]) or [BACK] (reconnected at [since], everything
 * refetched; shown for [BACK_MS]).
 */
class Connection(val state: String, val since: Long? = null, val dataAt: Long? = null) {
    val stale: Boolean get() = state == RECONNECTING || state == OFFLINE

    companion object {
        const val LIVE = "live"
        const val RECONNECTING = "reconnecting"
        const val OFFLINE = "offline"
        const val BACK = "back"
        const val BACK_MS = 60_000L
    }
}

/** The live source: [ContestsStore]. */
object StoreContestsSource : ContestsSource {
    private val S = ContestsStore

    override val selfUuid: String get() = S.selfUuid() ?: ""
    override val selfName: String get() = try {
        net.minecraft.client.Minecraft.getInstance().user.name
    } catch (_: Throwable) {
        ""
    }

    override fun friends() = S.friends
    override fun contests() = S.contests
    override fun history() = S.history
    override fun snapshot(id: Long) = S.snapshot(id)
    override fun feed(id: Long) = S.feed(id)
    override fun runs(id: Long, uuid: String) = S.memberRuns(id, uuid)
    override fun types() = S.types.value ?: emptyList()
    override fun queued(contestId: Long) = S.queuedRuns(contestId)
    override fun capture(contestId: Long) = S.capture(contestId)

    override fun connection(): Connection {
        val c = S.connection
        val now = S.now()
        if (c == ContestConnection.OFFLINE) return Connection(Connection.OFFLINE, dataAt = S.dataAsOf.takeIf { it > 0 })
        if (c == ContestConnection.RECONNECTING) return Connection(Connection.RECONNECTING, since = S.reconnectingSince, dataAt = S.dataAsOf.takeIf { it > 0 })
        val back = S.reconnectedAt
        return if (back != null && now - back < Connection.BACK_MS) Connection(Connection.BACK, since = back) else Connection(Connection.LIVE)
    }

    override fun openContest(id: Long) = S.openContest(id)
    override fun refreshFriends() = S.refreshFriends()
    override fun refreshContests() = S.refreshContests()
    override fun loadTypes() = S.loadTypes()
    override fun loadHistory(reset: Boolean) = S.loadHistoryPage(reset)
    override fun refreshContest(id: Long) = S.refreshContest(id)
    override fun loadRuns(id: Long, uuid: String) = S.loadMemberRuns(id, uuid)
    override fun retryNow() = S.retryNow()
    override fun closeContest() = S.closeContest()

    override fun addFriend(name: String, done: Done) = S.addFriend(name, done)
    override fun acceptFriend(uuid: String, done: Done) = S.acceptFriend(uuid, done)
    override fun declineFriend(uuid: String, done: Done) = S.declineFriend(uuid, done)
    override fun cancelFriendRequest(uuid: String, done: Done) = S.cancelFriendRequest(uuid, done)
    override fun removeFriend(uuid: String, done: Done) = S.removeFriend(uuid, done)
    override fun blockPlayer(uuid: String, done: Done) = S.blockPlayer(uuid, done)
    override fun unblockPlayer(uuid: String, done: Done) = S.unblockPlayer(uuid, done)
    override fun setShareArea(on: Boolean, done: Done) = S.setShareArea(on, done)

    override fun createContest(request: CreateContestRequest, done: Done) = S.createContest(request, done)
    override fun join(id: Long, asRole: String, done: Done) = S.join(id, asRole, done)
    override fun declineInvite(id: Long, done: Done) = S.declineInvite(id, done)
    override fun leave(id: Long, done: Done) = S.leave(id, done)
    override fun setReady(id: Long, ready: Boolean, done: Done) = S.setReady(id, ready, done)
    override fun invite(id: Long, invites: List<InviteSpec>, done: Done) = S.invite(id, invites, done)
    override fun setRole(id: Long, uuid: String, role: String, done: Done) = S.setRole(id, uuid, role, done)
    override fun approveJoin(id: Long, uuid: String, done: Done) = S.approveJoin(id, uuid, done)
    override fun rejectJoin(id: Long, uuid: String, done: Done) = S.rejectJoin(id, uuid, done)
    override fun removeMember(id: Long, uuid: String, reason: String, done: Done) = S.removeMember(id, uuid, reason, done)
    override fun edit(id: Long, edit: ContestEdit, done: Done) = S.edit(id, edit, done)
    override fun openDraft(id: Long, done: Done) = S.openDraft(id, done)
    override fun start(id: Long, done: Done) = S.start(id, done)
    override fun endEarly(id: Long, done: Done) = S.endEarly(id, done)
    override fun cancel(id: Long, reason: String, done: Done) = S.cancel(id, reason, done)
    override fun voidRun(id: Long, runId: String, reason: String, done: Done) = S.voidRun(id, runId, reason, done)
    override fun restoreRun(id: Long, runId: String, done: Done) = S.restoreRun(id, runId, done)
}
