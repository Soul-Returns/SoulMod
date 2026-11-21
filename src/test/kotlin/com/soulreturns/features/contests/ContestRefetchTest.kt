package com.soulreturns.features.contests

import com.soulreturns.features.contests.model.ApiError
import com.soulreturns.features.contests.model.ContestInfo
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestStates
import com.soulreturns.features.contests.model.ContestUpdate
import com.soulreturns.features.contests.model.ContestsBody
import com.soulreturns.features.contests.model.FeedEntry
import com.soulreturns.features.contests.model.FriendUpdate
import com.soulreturns.features.contests.model.FriendsBody
import com.soulreturns.features.contests.model.Goal
import com.soulreturns.features.contests.model.SnapshotMe
import com.soulreturns.features.contests.model.UpdateKinds
import kotlinx.coroutines.runBlocking
import net.minecraft.network.chat.Component
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A manual clock plus the timers [ContestsStore.schedule] arms on it. */
internal class FakeTime {
    private var clock = START
    private val timers = ArrayList<Pair<Long, () -> Unit>>()

    fun now(): Long = clock

    fun schedule(delayMs: Long, block: () -> Unit) {
        timers += (clock + delayMs) to block
    }

    fun reset() {
        clock = START
        timers.clear()
    }

    /** Moves time forward, running every timer that comes due (in order, including ones they arm). */
    fun advance(ms: Long) {
        val target = clock + ms
        while (true) {
            val next = timers.filter { it.first <= target }.minByOrNull { it.first } ?: break
            timers.remove(next)
            clock = maxOf(clock, next.first)
            next.second()
        }
        clock = target
    }

    companion object {
        const val START = 1_795_000_000_000L
    }
}

class ContestRefetchTest {
    private val time = FakeTime()
    private val queued = ArrayDeque<suspend () -> Unit>()

    private var serverRev = 54L
    private var snapshotCalls = 0
    private var listCalls = 0
    private var friendCalls = 0

    /** Scripted answers for the next snapshot fetches (consumed first); afterwards the server's current revision. */
    private val snapshotScript = ArrayDeque<ContestsApi.Reply<ContestSnapshot>>()
    private val friendScript = ArrayDeque<ContestsApi.Reply<FriendsBody>>()

    private fun snap(rev: Long, state: String = ContestStates.LOBBY) = ContestSnapshot(
        rev = rev,
        contest = ContestInfo(id = 7, type = "nucleus-profit", name = "Nucleus Night", state = state, goal = Goal(units = 50, label = "50 runs each")),
        me = SnapshotMe(role = "owner", status = "joined", plays = true, profileId = "99999999999999999999999999999999"),
        feed = listOf(FeedEntry(seq = 1, kind = "created")),
    )

    private fun rateLimited(retryAfterMs: Long?) = ContestsApi.Reply<ContestSnapshot>(null, 429, ApiError("rate_limited", "slow down"), retryAfterMs)

    private fun member(rev: Long) = ContestUpdate(contestId = 7, rev = rev, kind = UpdateKinds.MEMBER)

    /** Runs the queued network work (the fakes answer at once). */
    private fun pump() {
        while (queued.isNotEmpty()) runBlocking { queued.removeFirst()() }
    }

    private fun step(ms: Long) {
        time.advance(ms)
        pump()
    }

    @BeforeTest
    fun setUp() {
        ContestsStore.post = { it() }
        ContestsStore.launch = { queued += it }
        time.reset()
        ContestsStore.now = time::now
        ContestsStore.schedule = time::schedule
        ContestsStore.selfUuidProvider = { "0123456789abcdef0123456789abcdef" }
        ContestsStore.emit = { }
        ContestsChat.sink = { _: Component -> }
        ContestCaptures.targets = { emptyList() }
        ContestCaptures.saveTarget = { _, _ -> }
        ContestsStore.endShown = { true }
        ContestsStore.markEndShown = { }
        ContestsStore.fetchSnapshot = {
            snapshotCalls++
            snapshotScript.removeFirstOrNull() ?: ContestsApi.Reply(snap(serverRev), 200, null)
        }
        ContestsStore.fetchContests = {
            listCalls++
            ContestsApi.Reply(ContestsBody(), 200, null)
        }
        ContestsStore.fetchFriends = {
            friendCalls++
            friendScript.removeFirstOrNull() ?: ContestsApi.Reply(FriendsBody(), 200, null)
        }
        ContestsStore.reset()
        queued.clear(); snapshotScript.clear(); friendScript.clear()
        serverRev = 54L; snapshotCalls = 0; listCalls = 0; friendCalls = 0
        ContestsStore.seed(snap(54))
    }

    @AfterTest
    fun tearDown() {
        ContestsStore.reset()
    }

    @Test
    fun aBurstOfEightMemberUpdatesIsOneFetchOfEach() {
        for (rev in 55L..62L) {
            serverRev = rev
            ContestsStore.onContestUpdate(member(rev))
            step(100)
        }
        assertEquals(0, snapshotCalls, "nothing fetched while the burst is still going")
        step(ContestsStore.COALESCE_MS)
        assertEquals(1, snapshotCalls)
        assertEquals(1, listCalls)
        assertEquals(62L, ContestsStore.snapshot(7).value?.rev)
        step(60_000)
        assertEquals(1, snapshotCalls, "the fetched revision covers every event: no trailing fetch")
        assertEquals(1, listCalls)
    }

    @Test
    fun anEventDuringAFetchSchedulesExactlyOneMoreAndTheFinalStateMatches() {
        serverRev = 56
        ContestsStore.onContestUpdate(member(55))
        ContestsStore.onContestUpdate(member(56))
        time.advance(ContestsStore.COALESCE_MS)
        assertEquals(2, queued.size, "the first snapshot and list fetches are in flight")

        // More events while it is in flight; its answer (revision 58) is older than the newest event.
        for (rev in 57L..62L) ContestsStore.onContestUpdate(member(rev))
        time.advance(10_000)
        assertEquals(2, queued.size, "no second fetch while one is in flight")
        serverRev = 58
        pump()
        assertEquals(1, snapshotCalls)
        assertEquals(58L, ContestsStore.snapshot(7).value?.rev)

        serverRev = 62
        step(ContestsStore.COALESCE_MS)
        assertEquals(2, snapshotCalls)
        assertEquals(62L, ContestsStore.snapshot(7).value?.rev)
        step(60_000)
        assertEquals(2, snapshotCalls)
    }

    @Test
    fun aSteadyStreamIsBoundedAndNothingIsLost() {
        var rev = 54L
        repeat(100) { // 10 s of one event per 100 ms
            rev++
            serverRev = rev
            ContestsStore.onContestUpdate(member(rev))
            step(100)
        }
        assertTrue(snapshotCalls in 4..6, "expected about one fetch per 2 s, got $snapshotCalls")
        assertTrue(listCalls in 4..6, "expected about one list fetch per 2 s, got $listCalls")
        step(ContestsStore.COALESCE_MS)
        assertEquals(rev, ContestsStore.snapshot(7).value?.rev)
        val calls = snapshotCalls
        step(60_000)
        assertEquals(calls, snapshotCalls)
    }

    @Test
    fun aRateLimitIsFollowedByExactlyOneRetryAfterRetryAfter() {
        serverRev = 55
        snapshotScript += rateLimited(5_000)
        ContestsStore.onContestUpdate(member(55))
        step(ContestsStore.COALESCE_MS)
        assertEquals(1, snapshotCalls)
        assertEquals(54L, ContestsStore.snapshot(7).value?.rev)

        step(4_900)
        assertEquals(1, snapshotCalls, "still backing off")
        step(200)
        assertEquals(2, snapshotCalls)
        assertEquals(55L, ContestsStore.snapshot(7).value?.rev)
        step(60_000)
        assertEquals(2, snapshotCalls, "the retry succeeded: nothing else")
    }

    @Test
    fun aRateLimitWithoutRetryAfterUsesTheDefaultBackoff() {
        serverRev = 55
        snapshotScript += rateLimited(null)
        ContestsStore.onContestUpdate(member(55))
        step(ContestsStore.COALESCE_MS)
        step(14_000)
        assertEquals(1, snapshotCalls)
        step(1_500)
        assertEquals(2, snapshotCalls)
        assertEquals(55L, ContestsStore.snapshot(7).value?.rev)
    }

    @Test
    fun eventsDuringTheBackoffStillCostOneRetry() {
        serverRev = 55
        snapshotScript += rateLimited(5_000)
        ContestsStore.onContestUpdate(member(55))
        step(ContestsStore.COALESCE_MS)
        for (rev in 56L..60L) {
            serverRev = rev
            ContestsStore.onContestUpdate(member(rev))
            step(500)
        }
        assertEquals(1, snapshotCalls)
        step(5_000)
        assertEquals(2, snapshotCalls)
        assertEquals(60L, ContestsStore.snapshot(7).value?.rev)
    }

    @Test
    fun anActionsOwnSnapshotIsAppliedAtOnceAndCancelsThePendingRefetch() {
        for (rev in 55L..58L) ContestsStore.onContestUpdate(member(rev))
        ContestsStore.acceptSnapshot(snap(58), live = true)
        assertEquals(58L, ContestsStore.snapshot(7).value?.rev, "no waiting for the window")
        step(60_000)
        assertEquals(0, snapshotCalls)
    }

    @Test
    fun anActionsSnapshotOlderThanTheNewestEventStillGetsOneRefetch() {
        for (rev in 55L..58L) ContestsStore.onContestUpdate(member(rev))
        ContestsStore.acceptSnapshot(snap(56), live = true)
        assertEquals(56L, ContestsStore.snapshot(7).value?.rev)
        serverRev = 58
        step(ContestsStore.COALESCE_MS)
        assertEquals(1, snapshotCalls)
        assertEquals(58L, ContestsStore.snapshot(7).value?.rev)
    }

    @Test
    fun aScreenOpenFetchIsNotDelayed() {
        serverRev = 54
        ContestsStore.refreshContest(7)
        assertEquals(1, queued.size)
        pump()
        assertEquals(1, snapshotCalls)
    }

    @Test
    fun friendUpdateBurstIsOneFetch() {
        repeat(8) {
            ContestsStore.onFriendUpdate(FriendUpdate("updated", "11111111111111111111111111111111", "Example_Two"))
            step(100)
        }
        assertEquals(0, friendCalls)
        step(ContestsStore.COALESCE_MS)
        assertEquals(1, friendCalls)
        step(60_000)
        assertEquals(1, friendCalls)
    }

    @Test
    fun friendFetchRateLimitIsRetriedOnce() {
        friendScript += ContestsApi.Reply(null, 429, ApiError("rate_limited", "slow down"), 3_000L)
        ContestsStore.onFriendUpdate(FriendUpdate("updated", "11111111111111111111111111111111", "Example_Two"))
        step(ContestsStore.COALESCE_MS)
        assertEquals(1, friendCalls)
        step(3_100)
        assertEquals(2, friendCalls)
        assertTrue(ContestsStore.friends.value != null)
        step(60_000)
        assertEquals(2, friendCalls)
    }
}
