package com.soulreturns.features.contests

import com.soulreturns.core.events.Event
import com.soulreturns.features.contests.model.CheckCounts
import com.soulreturns.features.contests.model.CheckStates
import com.soulreturns.features.contests.model.ContestInfo
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestStates
import com.soulreturns.features.contests.model.ContestUpdate
import com.soulreturns.features.contests.model.FeedEntry
import com.soulreturns.features.contests.model.FriendUpdate
import com.soulreturns.features.contests.model.Goal
import com.soulreturns.features.contests.model.PlayerRef
import com.soulreturns.features.contests.model.RankRow
import com.soulreturns.features.contests.model.RunCheck
import com.soulreturns.features.contests.model.RunCheckHint
import com.soulreturns.features.contests.model.SnapshotMe
import com.soulreturns.features.contests.model.StandingRow
import com.soulreturns.features.contests.model.UpdateKinds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.network.chat.Component
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ME = "0123456789abcdef0123456789abcdef"
private const val OWNER = "fedcba9876543210fedcba9876543210"

private fun row(uuid: String, name: String, rank: Int?, total: Long, units: Int = 10) = StandingRow(
    uuid = uuid, name = name, role = "participant", status = "joined", rank = rank, units = units, goal = 50, total = total,
    best = total / 10, checks = CheckCounts(),
)

private fun snapshot(rev: Long, state: String = ContestStates.RUNNING, standings: List<StandingRow>? = null) = ContestSnapshot(
    rev = rev,
    contest = ContestInfo(id = 7, type = "nucleus-profit", name = "Nucleus Night", state = state, goal = Goal(units = 50, label = "50 runs each")),
    me = SnapshotMe(role = "participant", status = "joined", plays = true, profileId = "99999999999999999999999999999999"),
    standings = standings ?: listOf(row(OWNER, "Example_Owner", 1, 2_000), row(ME, "Example_One", 2, 1_000)),
    feed = listOf(FeedEntry(seq = 10, kind = "start")),
)

private fun runUpdate(rev: Long, feed: List<FeedEntry> = emptyList()) = ContestUpdate(
    contestId = 7, rev = rev, kind = UpdateKinds.RUN, state = ContestStates.RUNNING,
    standing = row(ME, "Example_One", 1, 2_500),
    ranks = listOf(RankRow(ME, 1), RankRow(OWNER, 2)),
    feed = feed,
)

class ContestReducerTest {
    @Test
    fun runUpdateAppliesRowRanksAndFeed() {
        val out = ContestReducer.apply(snapshot(133), runUpdate(134, listOf(FeedEntry(seq = 11, kind = "run"))))
        val s = assertNotNull(out.snapshot)
        assertFalse(out.refetch)
        assertEquals(134L, s.rev)
        assertEquals(listOf(ME, OWNER), s.standings.map { it.uuid })
        assertEquals(2_500L, s.standings[0].total)
        assertEquals(listOf(11L, 10L), s.feed.map { it.seq })
    }

    @Test
    fun revisionGapRefetches() {
        val out = ContestReducer.apply(snapshot(133), runUpdate(136))
        assertNull(out.snapshot)
        assertTrue(out.refetch)
    }

    @Test
    fun staleOrDuplicateEventIsIgnored() {
        val out = ContestReducer.apply(snapshot(133), runUpdate(133))
        assertTrue(out.stale)
        assertFalse(out.refetch)
    }

    @Test
    fun stateLessKindsAndUnknownKindsRefetch() {
        for (kind in listOf(UpdateKinds.MEMBER, UpdateKinds.STATE, UpdateKinds.SETTINGS, "teleport")) {
            val out = ContestReducer.apply(snapshot(133), ContestUpdate(contestId = 7, rev = 134, kind = kind))
            assertTrue(out.refetch, kind)
        }
    }

    @Test
    fun checkReplacesTheRowButKeepsRanks() {
        val u = ContestUpdate(
            contestId = 7, rev = 134, kind = UpdateKinds.CHECK,
            standing = row(ME, "Example_One", 2, 1_000).copy(checks = CheckCounts(verified = 5)),
            ranks = listOf(RankRow(ME, 1)),
            run = RunCheckHint(ME, "r1", RunCheck(CheckStates.VERIFIED)),
        )
        val s = assertNotNull(ContestReducer.apply(snapshot(133), u).snapshot)
        assertEquals(5, s.standings.first { it.uuid == ME }.checks.verified)
        assertEquals(2, s.standings.first { it.uuid == ME }.rank)
    }

    @Test
    fun voidAppliesLikeARun() {
        val u = runUpdate(134).copy(kind = UpdateKinds.VOID)
        assertNotNull(ContestReducer.apply(snapshot(133), u).snapshot)
    }
}

class ContestStoreTest {
    private val signals = ArrayList<Event>()
    private val chat = ArrayList<String>()
    private val launched = ArrayList<String>()
    private val savedTargets = HashMap<Long, CaptureTarget?>()

    @BeforeTest
    fun setUp() {
        ContestsStore.post = { it() }
        ContestsStore.launch = { launched += "call" }
        ContestsStore.now = { 1_795_000_000_000L }
        ContestsStore.selfUuidProvider = { ME }
        ContestsStore.emit = { signals += it }
        ContestsChat.sink = { c: Component -> chat += c.string }
        ContestCaptures.targets = { savedTargets.values.filterNotNull() }
        ContestCaptures.saveTarget = { id, t -> savedTargets[id] = t }
        ContestsStore.reset()
        signals.clear(); chat.clear(); launched.clear()
    }

    @AfterTest
    fun tearDown() {
        ContestsStore.reset()
    }

    @Test
    fun liveRunEventUpdatesStandingsAndSignalsAPass() {
        ContestsStore.seed(snapshot(133))
        val passFeed = FeedEntry(
            seq = 11, kind = "pass", player = PlayerRef(OWNER, "Example_Owner"),
            data = buildJsonObject {
                put("passed", buildJsonObject { put("uuid", ME); put("name", "Example_One") })
                put("rank", 1)
            },
        )
        val u = runUpdate(134, listOf(passFeed)).copy(
            standing = row(OWNER, "Example_Owner", 1, 3_000),
            ranks = listOf(RankRow(OWNER, 1), RankRow(ME, 2)),
        )
        ContestsStore.onContestUpdate(u)
        assertEquals(134L, ContestsStore.snapshot(7).value?.rev)
        val pass = signals.filterIsInstance<ContestPassedSignal>().single()
        assertEquals("Example_Owner", pass.by.name)
        assertEquals(2, pass.myRank)
        assertEquals(2_000L, pass.behind)
        assertTrue(launched.isEmpty(), "no refetch for a contiguous run event")
    }

    @Test
    fun revisionGapRefetchesTheSnapshot() {
        ContestsStore.seed(snapshot(133))
        ContestsStore.onContestUpdate(runUpdate(140))
        assertTrue(ContestsStore.snapshot(7).loading)
        assertEquals(133L, ContestsStore.snapshot(7).value?.rev)
        assertEquals(1, launched.size)
    }

    @Test
    fun goalAndFlagSignals() {
        ContestsStore.seed(snapshot(133))
        val goal = FeedEntry(seq = 11, kind = "goal", player = PlayerRef(OWNER, "Example_Owner"), data = buildJsonObject { put("units", 50); put("total", 9_000) })
        val flag = FeedEntry(seq = 12, kind = "flag", player = PlayerRef(ME, "Example_One"), data = buildJsonObject { put("runId", "r9"); put("reason", "too_fast") })
        val unknown = FeedEntry(seq = 13, kind = "teleport", data = JsonObject(mapOf("x" to JsonPrimitive(1))))
        ContestsStore.onContestUpdate(runUpdate(134, listOf(goal, flag, unknown)))
        val fin = signals.filterIsInstance<ContestPlayerFinishedSignal>().single()
        assertEquals(9_000L, fin.total)
        assertFalse(fin.isMe)
        assertEquals("r9", signals.filterIsInstance<ContestRunFlaggedSignal>().single().runId)
    }

    @Test
    fun stateTransitionsSignalStartAndEndOnce() {
        ContestsStore.seed(snapshot(100, ContestStates.LOBBY))
        ContestsStore.acceptSnapshot(snapshot(101, ContestStates.RUNNING), live = true)
        assertEquals(1, signals.filterIsInstance<ContestStartedSignal>().size)
        assertTrue(chat.any { "started" in it })

        val final = snapshot(102, ContestStates.FINISHED, listOf(row(ME, "Example_One", 1, 5_000), row(OWNER, "Example_Owner", 2, 4_000)))
        ContestsStore.acceptSnapshot(final, live = true)
        ContestsStore.acceptSnapshot(final.copy(rev = 102), live = true)
        val ended = signals.filterIsInstance<ContestEndedSignal>().single()
        assertTrue(ended.won)
        assertFalse(ended.missed)
        assertTrue(chat.any { "is over" in it && "You placed §e1st" in it })
    }

    @Test
    fun lateRunAfterTheEndSignalsStandingsUpdated() {
        val final = snapshot(102, ContestStates.FINISHED, listOf(row(OWNER, "Example_Owner", 1, 5_000), row(ME, "Example_One", 2, 4_000)))
        ContestsStore.seed(final)
        val late = ContestUpdate(
            contestId = 7, rev = 103, kind = UpdateKinds.RUN, state = ContestStates.FINISHED,
            standing = row(ME, "Example_One", 1, 6_000), ranks = listOf(RankRow(ME, 1), RankRow(OWNER, 2)),
            feed = listOf(FeedEntry(seq = 50, kind = "run", player = PlayerRef(ME, "Example_One"), data = buildJsonObject { put("late", true) })),
        )
        ContestsStore.onContestUpdate(late)
        val s = signals.filterIsInstance<ContestStandingsUpdatedSignal>().single()
        assertEquals(2, s.oldRank)
        assertEquals(1, s.newRank)
        assertTrue(chat.any { "Standings updated" in it && "your late run" in it })
    }

    @Test
    fun olderSnapshotNeverOverwritesANewerOne() {
        ContestsStore.seed(snapshot(140))
        ContestsStore.acceptSnapshot(snapshot(139), live = true)
        assertEquals(140L, ContestsStore.snapshot(7).value?.rev)
    }

    @Test
    fun memberAndStateKindsAlsoRefreshTheList() {
        ContestsStore.seed(snapshot(133))
        ContestsStore.onContestUpdate(ContestUpdate(contestId = 7, rev = 134, kind = UpdateKinds.MEMBER))
        assertTrue(ContestsStore.contests.loading)
        assertTrue(ContestsStore.snapshot(7).loading)
    }

    @Test
    fun friendRequestEventSignalsAndRefetches() {
        ContestsStore.onFriendUpdate(FriendUpdate("request", "11111111111111111111111111111111", "Example_Two"))
        assertEquals("Example_Two", signals.filterIsInstance<FriendRequestSignal>().single().from.name)
        assertTrue(ContestsStore.friends.loading)
        assertTrue(chat.any { "friend request" in it })
    }

    @Test
    fun realtimeAcceptsOnlyTheOwnTopic() {
        assertTrue(ContestsSync.ownTopic("user/$ME"))
        assertFalse(ContestsSync.ownTopic("user/$OWNER"))
        assertFalse(ContestsSync.ownTopic(null))
    }

    @Test
    fun snapshotsKeepTheCaptureTargetInStep() {
        ContestsStore.acceptSnapshot(snapshot(1), live = false)
        assertEquals("99999999999999999999999999999999", savedTargets[7L]?.profileId)
        ContestsStore.acceptSnapshot(snapshot(2, ContestStates.FINISHED), live = false)
        assertNull(savedTargets[7L])
    }
}
