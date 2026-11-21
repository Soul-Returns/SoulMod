package com.soulreturns.features.contests.gui

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.features.contests.ActionResult
import com.soulreturns.features.contests.CaptureStatus
import com.soulreturns.features.contests.HistoryState
import com.soulreturns.features.contests.QueuedRun
import com.soulreturns.features.contests.Remote
import com.soulreturns.features.contests.hud.ContestEndReveal
import com.soulreturns.features.contests.hud.ContestHud
import com.soulreturns.features.contests.hud.ContestToasts
import com.soulreturns.features.contests.model.ApiError
import com.soulreturns.features.contests.model.CheckCounts
import com.soulreturns.features.contests.model.ContestEdit
import com.soulreturns.features.contests.model.ContestInfo
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestSummary
import com.soulreturns.features.contests.model.ContestTypeDescriptor
import com.soulreturns.features.contests.model.ContestsBody
import com.soulreturns.features.contests.model.CreateContestRequest
import com.soulreturns.features.contests.model.FeedEntry
import com.soulreturns.features.contests.model.Friend
import com.soulreturns.features.contests.model.FriendContest
import com.soulreturns.features.contests.model.FriendRequestRow
import com.soulreturns.features.contests.model.FriendsBody
import com.soulreturns.features.contests.model.FriendsMe
import com.soulreturns.features.contests.model.Goal
import com.soulreturns.features.contests.model.GoalKind
import com.soulreturns.features.contests.model.InviteSpec
import com.soulreturns.features.contests.model.Member
import com.soulreturns.features.contests.model.MemberRequest
import com.soulreturns.features.contests.model.MemberRuns
import com.soulreturns.features.contests.model.Metric
import com.soulreturns.features.contests.model.PlayerRef
import com.soulreturns.features.contests.model.Presence
import com.soulreturns.features.contests.model.Run
import com.soulreturns.features.contests.model.RunCheck
import com.soulreturns.features.contests.model.RunFields
import com.soulreturns.features.contests.model.RunItem
import com.soulreturns.features.contests.model.RunVoid
import com.soulreturns.features.contests.model.SnapshotMe
import com.soulreturns.features.contests.model.StandingRow
import com.soulreturns.features.contests.model.SummaryMe
import com.soulreturns.features.contests.model.TopRow
import com.soulreturns.features.contests.model.UnitNames
import com.soulreturns.features.contests.model.UploadItem
import com.soulreturns.platform.devaccess.DevAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

/**
 * Fixed contests data (the mock's made-up players) for `/soul dev contests fixture <name>`: every page,
 * role and connection state without a backend, for the test client's screenshots. Dev access only.
 */
object ContestsFixtures {
    private const val MIN = 60_000L
    private val t0 = System.currentTimeMillis()

    private fun uuid(n: Int) = "00000000000040008000%012x".format(n)

    private class P(val n: Int, val name: String, val presence: Presence) {
        val uuid = uuid(n)
        val ref get() = PlayerRef(uuid, name)
    }

    private val WREN = P(1, "Wren_Digs", Presence(true, true, area = "Crystal Hollows", subLocation = "Crystal Nucleus"))
    private val MIRA = P(2, "MossyMira", Presence(true, true, area = "Crystal Hollows", subLocation = "Jungle Temple"))
    private val TUFF = P(3, "Tuff_Lou", Presence(true, true, area = "Crystal Hollows", subLocation = "Mithril Deposits"))
    private val BEN = P(4, "Basalt_Ben", Presence(true, true, areaHidden = true))
    private val OTIS = P(5, "Gem_Otis", Presence(false, lastOnlineAt = t0 - 120 * MIN))
    private val IVY = P(6, "Ivy_Quartz", Presence(true, true, area = "Hub", subLocation = "Village"))
    private val PIP = P(7, "Pyro_Pip", Presence(false, lastOnlineAt = t0 - 3 * 1440 * MIN))
    private val FERN = P(8, "Dusty_Fern", Presence(true, true, area = "Dwarven Mines", subLocation = "Royal Mines"))
    private val LARS = P(9, "LapisLars", Presence(false, lastOnlineAt = t0 - 1440 * MIN))
    private val GUS = P(10, "Grief_Gus", Presence(false))

    val TYPE = ContestTypeDescriptor(
        id = "nucleus-profit", title = "Nucleus profit", description = "Most loot value from Crystal Nucleus runs.", area = "Crystal Hollows",
        metric = Metric("Loot value"), unit = UnitNames("run", "runs"),
        goalKinds = listOf(GoalKind("units", "Same number of runs each", 1, 200, 50)),
        runFields = RunFields(duration = true, items = true, powder = true, partial = true), notableUnitValue = 100_000,
        rules = listOf(
            "A run is one Crystal Nucleus loot bundle.",
            "It counts the loot bundle's value only; nothing is subtracted.",
            "Prices are frozen when the contest starts.",
            "Powder has no value.",
            "Items without a price count 0 coins and are listed as items without a price.",
        ),
        verification = "The server checks the number of runs against Hypixel's placed-crystal counters. It can confirm that runs happened, never what dropped.",
    )
    private val LATER = ContestTypeDescriptor(id = "corpse-count", title = "Mineshaft corpses", description = "Most corpses looted in Glacite Mineshafts.", area = "Glacite Mineshafts", enabled = false, metric = Metric("Corpses", "count"), unit = UnitNames("corpse", "corpses"))

    // ------------------------------------------------------------------ the running contest

    private class Line(val p: P, val role: String, val units: Int, val total: Long, val best: Long, val flagged: Int = 0, val unpriced: Int = 0, val status: String = ContestView.JOINED)

    private val NIGHT_LINES = listOf(
        Line(MIRA, ContestView.MANAGER, 34, 21_400_000, 1_900_000, unpriced = 2),
        Line(WREN, ContestView.OWNER, 31, 18_900_000, 2_400_000),
        Line(TUFF, ContestView.PARTICIPANT, 33, 17_200_000, 1_400_000, flagged = 1),
        Line(BEN, ContestView.PARTICIPANT, 27, 15_100_000, 1_300_000),
        Line(IVY, ContestView.PARTICIPANT, 12, 6_300_000, 900_000),
    )

    private fun standings(lines: List<Line>, goal: Int, finished: Boolean, tie: String? = null): List<StandingRow> {
        var rank = 0
        return lines.mapIndexed { i, l ->
            if (l.status == ContestView.DISQUALIFIED) return@mapIndexed row(l, null, goal, finished)
            rank++
            val shared = tie == "shared" && (i == 1 || i == 2)
            row(l, if (shared && i == 2) rank - 1 else rank, goal, finished, shared)
        }
    }

    private fun row(l: Line, rank: Int?, goal: Int, finished: Boolean, shared: Boolean = false) = StandingRow(
        uuid = l.p.uuid, name = l.p.name, role = l.role, status = l.status, rank = rank, shared = shared, units = l.units, goal = goal,
        total = l.total, best = l.best, avg = if (l.units > 0) l.total / l.units else null, lastRunAt = t0 - (3 + l.p.n) * MIN, lastValue = l.best / 3,
        unpriced = l.unpriced, voided = if (l.flagged > 0) 1 else 0,
        checks = CheckCounts(verified = l.units - 3 - l.flagged, pending = 3, flagged = l.flagged),
        medianDurationMs = (4 * 60 + 30 + l.p.n * 7) * 1000L, untimed = if (l.p.n == 4) 1 else 0, goalReachedAt = if (l.units >= goal) t0 - 30 * MIN else null,
        dqReason = if (l.status == ContestView.DISQUALIFIED) "Ran the contest on an alt profile." else null,
    )

    private fun member(l: Line, status: String = l.status, ready: Boolean = true) = Member(
        uuid = l.p.uuid, name = l.p.name, role = l.role, status = status, ready = ready, invitedBy = WREN.ref, invitedAt = t0 - 90 * MIN,
        joinedAt = t0 - 80 * MIN, profileLocked = true, presence = l.p.presence,
    )

    private fun feed(): List<FeedEntry> {
        fun e(min: Long, kind: String, p: P, vararg kv: Pair<String, Any>) = FeedEntry(
            seq = 1000 - min, at = t0 - min * MIN, kind = kind, player = p.ref,
            data = JsonObject(kv.associate { (k, v) -> k to if (v is P) JsonObject(mapOf("uuid" to JsonPrimitive(v.uuid), "name" to JsonPrimitive(v.name))) else if (v is Number) JsonPrimitive(v) else if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
        )
        return listOf(
            e(1, "run", MIRA, "n" to 34, "value" to 640_000, "durationMs" to 271_000),
            e(2, "pass", MIRA, "passed" to WREN, "rank" to 1),
            e(3, "drop", TUFF, "name" to "Divan's Alloy", "count" to 1, "value" to 1_400_000, "n" to 33),
            e(4, "run", WREN, "n" to 31, "value" to 520_000, "durationMs" to 288_000),
            e(6, "flag", TUFF, "n" to 30, "reason" to "not_in_api"),
            e(9, "run", BEN, "n" to 27, "value" to 410_000, "durationMs" to 305_000),
            e(14, "goal", BEN, "units" to 50, "total" to 24_100_000),
        )
    }

    private fun night(state: String, role: String, lines: List<Line> = NIGHT_LINES, tie: String? = null, extra: List<Member> = emptyList()): ContestSnapshot {
        val goal = 50
        val finished = state == ContestView.FINISHED
        val ls = if (role == ContestView.OWNER) lines else lines.map { if (it.p === WREN) Line(WREN, role, it.units, it.total, it.best) else if (it.role == ContestView.OWNER) Line(it.p, ContestView.OWNER, it.units, it.total, it.best, it.flagged, it.unpriced, it.status) else it }
        val owner = ls.firstOrNull { it.role == ContestView.OWNER }?.p ?: MIRA
        val members = ls.map { member(it, ready = it.p !== BEN) } + extra +
            (if (role == ContestView.SPECTATOR) emptyList() else listOf(Member(FERN.uuid, FERN.name, ContestView.SPECTATOR, ContestView.JOINED, true, presence = FERN.presence))) +
            listOf(Member(OTIS.uuid, OTIS.name, ContestView.PARTICIPANT, ContestView.INVITED, invitedAt = t0 - 100 * MIN, presence = OTIS.presence))
        val can = when (role) {
            ContestView.OWNER -> listOf("upload", "invite", "appoint", "edit", "start", "approve", "remove", "disqualify", "void", "end", "cancel")
            ContestView.MANAGER -> listOf("upload", "leave", "invite", "approve", "remove", "disqualify", "void", "end")
            ContestView.SPECTATOR -> listOf("leave", "join")
            else -> listOf("upload", "ready", "leave")
        }
        return ContestSnapshot(
            serverTime = t0, rev = 42,
            contest = ContestInfo(
                id = 2611, type = TYPE.id, name = "Nucleus Night", state = state, owner = owner.ref, goal = Goal("units", goal, "50 runs each"),
                allowLateJoin = true, maxPlayers = 10, createdAt = t0 - 120 * MIN, startedAt = if (state == ContestView.LOBBY) null else t0 - 218 * MIN,
                endsAt = t0 + 3 * 60 * MIN, endedAt = if (ContestView.FINISHED == state || ContestView.CANCELED == state) t0 - 2 * MIN else null,
                endReason = if (finished) "all_finished" else null, pricesFrozenAt = if (state == ContestView.LOBBY) null else t0 - 218 * MIN,
                canceledAt = if (state == ContestView.CANCELED) t0 - 2 * MIN else null, canceledBy = if (state == ContestView.CANCELED) owner.ref else null,
                canceledReason = if (state == ContestView.CANCELED) "Hypixel went down for maintenance." else null,
            ),
            type = TYPE,
            me = SnapshotMe(role = role, status = ContestView.JOINED, ready = true, plays = role != ContestView.SPECTATOR, can = can),
            members = members,
            standings = standings(ls, goal, finished, tie),
            feed = feed() + if (finished && tie == "updated") listOf(FeedEntry(2000, t0 - 1 * MIN, "run", WREN.ref, data = JsonObject(mapOf("n" to JsonPrimitive(50), "late" to JsonPrimitive(true), "value" to JsonPrimitive(700_000))))) else emptyList(),
        )
    }

    private fun finishedLines(tie: String?): List<Line> {
        val base = listOf(
            Line(MIRA, ContestView.MANAGER, 50, 31_200_000, 2_100_000, unpriced = 2),
            Line(WREN, ContestView.OWNER, 50, 29_800_000, 2_400_000),
            Line(TUFF, ContestView.PARTICIPANT, 50, if (tie != null) 29_800_000 else 27_100_000, if (tie == "shared") 2_400_000 else 1_400_000),
            Line(BEN, ContestView.PARTICIPANT, 46, 22_000_000, 1_300_000),
            Line(IVY, ContestView.PARTICIPANT, 31, 14_900_000, 900_000),
            Line(GUS, ContestView.PARTICIPANT, 8, 3_100_000, 800_000, status = ContestView.DISQUALIFIED),
        )
        return base
    }

    private fun runs(p: P, units: Int): List<Run> = (units downTo maxOf(1, units - 11)).map { n ->
        val value = 300_000L + (n * 37_123L % 900_000L)
        Run(
            runId = "r-${p.n}-$n", n = n, counted = n != units - 4, finishedAt = t0 - (units - n + 1) * 5 * MIN, durationMs = if (n == units - 2) null else (250 + n % 60) * 1000L,
            partial = n == units - 7, value = value,
            items = listOf(RunItem("Divan's Alloy", "DIVAN_ALLOY", if (n % 9 == 0) 1 else 0, 1_400_000, if (n % 9 == 0) 1_400_000 else 0), RunItem("Jaderald", "JADERALD", 1, 150_000, 150_000), RunItem("Prehistoric Egg", "PREHISTORIC_EGG", 1, null, 0), RunItem("Flawless Ruby Gemstone", "FLAWLESS_RUBY_GEM", 2, 90_000, 180_000)).filter { it.count > 0 },
            unpriced = 1, powder = mapOf("gemstone" to 1200L),
            check = when (n) {
                units -> RunCheck("pending", "waiting")
                units - 1 -> RunCheck("pending", "waiting")
                units - 3 -> RunCheck("flagged", "not_in_api")
                else -> RunCheck("verified")
            },
            voided = if (n == units - 4) RunVoid(WREN.ref, "Duplicate loot bundle.", t0 - 30 * MIN) else null,
        )
    }

    // ------------------------------------------------------------------ friends, summaries

    private fun friend(p: P, contest: FriendContest? = null) = Friend(p.uuid, p.name, t0 - 9 * 1440 * MIN, p.presence, listOfNotNull(contest))

    private fun friendsBody(empty: Boolean): FriendsBody = if (empty) FriendsBody(FriendsMe(WREN.uuid, WREN.name)) else FriendsBody(
        me = FriendsMe(WREN.uuid, WREN.name, true),
        friends = listOf(
            friend(MIRA, FriendContest(2611, "Nucleus Night", ContestView.RUNNING, ContestView.MANAGER)),
            friend(TUFF, FriendContest(2611, "Nucleus Night", ContestView.RUNNING, ContestView.PARTICIPANT)),
            friend(BEN), friend(IVY), friend(FERN, FriendContest(2611, "Nucleus Night", ContestView.RUNNING, ContestView.SPECTATOR)), friend(OTIS), friend(PIP), friend(LARS),
        ),
        incoming = listOf(FriendRequestRow(uuid(11), "Shale_Sam", t0 - 25 * MIN)),
        outgoing = listOf(FriendRequestRow(uuid(12), "Opal_Ola", t0 - 2 * 1440 * MIN)),
        blocked = listOf(FriendRequestRow(GUS.uuid, GUS.name, t0 - 6 * 1440 * MIN)),
    )

    private fun summary(s: ContestSnapshot, self: String): ContestSummary {
        val me = s.standings.firstOrNull { it.uuid == self }
        return ContestSummary(
            id = s.contest.id, type = s.type.id, typeTitle = s.type.title, name = s.contest.name, state = s.contest.state, rev = s.rev,
            owner = s.contest.owner, goal = s.contest.goal, startsAt = s.contest.startsAt, startedAt = s.contest.startedAt, endsAt = s.contest.endsAt,
            endedAt = s.contest.endedAt, endReason = s.contest.endReason, players = s.standings.size, spectators = 1,
            me = SummaryMe(s.me.role, s.me.status, me?.rank, me?.shared ?: false, me?.units ?: 0, me?.total ?: 0),
            top = s.standings.filter { it.rank != null }.take(4).map { TopRow(it.uuid, it.name, it.rank, it.shared, it.units, it.total) },
        )
    }

    private fun past(id: Long, name: String, goal: Int, days: Int, state: String, myRank: Int?, winner: P, total: Long) = ContestSummary(
        id = id, type = TYPE.id, typeTitle = TYPE.title, name = name, state = state, owner = MIRA.ref, goal = Goal("units", goal, "$goal runs each"),
        startedAt = t0 - days * 1440 * MIN, endedAt = t0 - days * 1440 * MIN + 72 * MIN, endReason = "all_finished", players = 4,
        me = SummaryMe(ContestView.PARTICIPANT, ContestView.JOINED, myRank, false, goal, total / 2),
        top = listOf(TopRow(winner.uuid, winner.name, 1, false, goal, total)),
    )

    private val HISTORY = listOf(
        past(2607, "Warm-up Ten", 10, 3, ContestView.FINISHED, 1, WREN, 6_100_000),
        past(2605, "Saturday Nucleus", 30, 2, ContestView.CANCELED, null, MIRA, 0),
        past(2603, "Friday Fast Five", 5, 10, ContestView.FINISHED, 3, TUFF, 2_900_000),
    )

    private val INVITE = ContestSummary(
        id = 2614, type = TYPE.id, typeTitle = TYPE.title, name = "Sunday Sprint", state = ContestView.LOBBY, owner = OTIS.ref,
        goal = Goal("units", 25, "25 runs each"), startsAt = t0 + 26 * 60 * MIN, players = 4, invitedBy = OTIS.ref, invitedAt = t0 - 40 * MIN,
        me = SummaryMe(ContestView.PARTICIPANT, ContestView.INVITED),
    )
    private val UPCOMING = ContestSummary(
        id = 2615, type = TYPE.id, typeTitle = TYPE.title, name = "Weekday Nucleus", state = ContestView.LOBBY, owner = MIRA.ref,
        goal = Goal("units", 20, "20 runs each"), startsAt = t0 + 3 * 1440 * MIN, players = 3, me = SummaryMe(ContestView.PARTICIPANT, ContestView.JOINED),
    )

    // ------------------------------------------------------------------ the source

    class FixtureSource(
        val snap: ContestSnapshot?,
        val friendsBody: FriendsBody?,
        val friendsDown: Boolean = false,
        val conn: Connection = Connection(Connection.LIVE),
        val queuedRuns: List<QueuedRun> = emptyList(),
        val extraActive: List<ContestSummary> = emptyList(),
    ) : ContestsSource {
        override val selfUuid: String = WREN.uuid
        override val selfName: String = WREN.name
        private val ok: Done.() -> Unit = { this(ActionResult(true)) }

        override fun friends() = Remote(friendsBody, error = if (friendsDown) ApiError("network", "Soul's server can't be reached.") else null, fetchedAt = t0 - 12 * MIN)
        override fun contests(): Remote<ContestsBody> = Remote(ContestsBody(
            serverTime = t0,
            active = listOfNotNull(snap?.takeIf { !ContestView(it, selfUuid).over }?.let { summary(it, selfUuid) }) + extraActive,
            invitations = listOf(INVITE),
            history = listOfNotNull(snap?.takeIf { ContestView(it, selfUuid).over }?.let { summary(it, selfUuid) }) + HISTORY,
        ), fetchedAt = t0)
        override fun history() = HistoryState(contests().value!!.history, null, false, null, t0)
        override fun snapshot(id: Long): Remote<ContestSnapshot> = if (snap != null && id == snap.contest.id) Remote(snap, fetchedAt = t0) else Remote(loading = true)
        override fun feed(id: Long) = snapshot(id).value?.feed.orEmpty()
        override fun runs(id: Long, uuid: String): Remote<MemberRuns> {
            val row = snap?.standings?.firstOrNull { it.uuid == uuid } ?: return Remote(loading = true)
            val p = listOf(WREN, MIRA, TUFF, BEN, OTIS, IVY, PIP, FERN, LARS, GUS).first { it.uuid == uuid }
            return Remote(MemberRuns(42, row, runs(p, row.units)), fetchedAt = t0)
        }
        override fun types() = listOf(TYPE, LATER)
        override fun connection() = conn
        override fun queued(contestId: Long) = queuedRuns
        override fun capture(contestId: Long): CaptureStatus? = null
        override fun openContest(id: Long) {}
        override fun refreshFriends() {}
        override fun refreshContests() {}
        override fun loadTypes() {}
        override fun loadHistory(reset: Boolean) {}
        override fun refreshContest(id: Long) {}
        override fun loadRuns(id: Long, uuid: String) {}
        override fun retryNow() {}
        override fun closeContest() {}
        override fun addFriend(name: String, done: Done) = done(if (name.equals("Steve_Nobody", true)) ActionResult(false, "not_a_user", "They don't use Soul.") else ActionResult(true, friendResult = "sent"))
        override fun acceptFriend(uuid: String, done: Done) = done.ok()
        override fun declineFriend(uuid: String, done: Done) = done.ok()
        override fun cancelFriendRequest(uuid: String, done: Done) = done.ok()
        override fun removeFriend(uuid: String, done: Done) = done.ok()
        override fun blockPlayer(uuid: String, done: Done) = done.ok()
        override fun unblockPlayer(uuid: String, done: Done) = done.ok()
        override fun setShareArea(on: Boolean, done: Done) = done.ok()
        override fun createContest(request: CreateContestRequest, done: Done) = done(ActionResult(true, contestId = 2611))
        override fun join(id: Long, asRole: String, done: Done) = done.ok()
        override fun declineInvite(id: Long, done: Done) = done.ok()
        override fun leave(id: Long, done: Done) = done.ok()
        override fun setReady(id: Long, ready: Boolean, done: Done) = done.ok()
        override fun invite(id: Long, invites: List<InviteSpec>, done: Done) = done.ok()
        override fun setRole(id: Long, uuid: String, role: String, done: Done) = done.ok()
        override fun approveJoin(id: Long, uuid: String, done: Done) = done.ok()
        override fun rejectJoin(id: Long, uuid: String, done: Done) = done.ok()
        override fun removeMember(id: Long, uuid: String, reason: String, done: Done) = done.ok()
        override fun edit(id: Long, edit: ContestEdit, done: Done) = done.ok()
        override fun openDraft(id: Long, done: Done) = done.ok()
        override fun start(id: Long, done: Done) = done.ok()
        override fun endEarly(id: Long, done: Done) = done.ok()
        override fun cancel(id: Long, reason: String, done: Done) = done(ActionResult(false, "forbidden", "Fixture: nothing is canceled."))
        override fun voidRun(id: Long, runId: String, reason: String, done: Done) = done.ok()
        override fun restoreRun(id: Long, runId: String, done: Done) = done.ok()
    }

    private fun queued() = listOf(QueuedRun(2611, "q-1", 32, t0 - 3 * MIN, t0 - 8 * MIN, 287_000, false, listOf(UploadItem("Jaderald", "JADERALD", 1)), emptyMap(), t0 - 3 * MIN, 2, false, null, null))

    /** Fixture name to (source, page, contest, player). */
    fun build(name: String): Pair<ContestsSource, ContestsNavState>? {
        val nav = ContestsNavState()
        fun go(page: String, id: Long? = null, player: String? = null) = nav.apply { this.page = page; contestId = id; this.player = player }
        val friends = friendsBody(false)
        val lobbyExtra = listOf(Member(uuid(11), "Shale_Sam", ContestView.PARTICIPANT, ContestView.DECLINED, presence = Presence(false)))
        return when (name) {
            "home" -> FixtureSource(night(ContestView.RUNNING, ContestView.OWNER), friends, extraActive = listOf(UPCOMING)) to go(ContestsNavState.HOME)
            "home-empty" -> FixtureSource(null, friends) to go(ContestsNavState.HOME)
            "friends" -> FixtureSource(null, friends) to go(ContestsNavState.FRIENDS)
            "friends-empty" -> FixtureSource(null, friendsBody(true)) to go(ContestsNavState.FRIENDS)
            "friends-down" -> FixtureSource(null, friends, friendsDown = true, conn = Connection(Connection.OFFLINE, dataAt = t0 - 12 * MIN)) to go(ContestsNavState.FRIENDS)
            "friends-added" -> FixtureSource(null, friends) to go(ContestsNavState.FRIENDS).also { it.addResult = "sent"; it.addName = "Shale_Sam" }
            "create" -> FixtureSource(null, friends) to go(ContestsNavState.CREATE).also { it.createInvites[MIRA.uuid] = ContestView.MANAGER; it.createInvites[TUFF.uuid] = ContestView.PARTICIPANT; it.createInvites[FERN.uuid] = ContestView.SPECTATOR }
            "lobby-owner" -> FixtureSource(night(ContestView.LOBBY, ContestView.OWNER, extra = lobbyExtra), friends) to go(ContestsNavState.CONTEST, 2611)
            "lobby-manager" -> FixtureSource(night(ContestView.LOBBY, ContestView.MANAGER), friends) to go(ContestsNavState.CONTEST, 2611)
            "lobby-participant" -> FixtureSource(night(ContestView.LOBBY, ContestView.PARTICIPANT), friends) to go(ContestsNavState.CONTEST, 2611)
            "lobby-spectator" -> FixtureSource(night(ContestView.LOBBY, ContestView.SPECTATOR), friends) to go(ContestsNavState.CONTEST, 2611)
            "running-owner" -> FixtureSource(night(ContestView.RUNNING, ContestView.OWNER, extra = listOf(Member(LARS.uuid, LARS.name, ContestView.PARTICIPANT, ContestView.REQUESTED, request = MemberRequest(ContestView.PARTICIPANT, t0 - 4 * MIN), presence = LARS.presence))), friends) to go(ContestsNavState.CONTEST, 2611)
            "running-manager" -> FixtureSource(night(ContestView.RUNNING, ContestView.MANAGER), friends) to go(ContestsNavState.CONTEST, 2611)
            "running-participant" -> FixtureSource(night(ContestView.RUNNING, ContestView.PARTICIPANT), friends) to go(ContestsNavState.CONTEST, 2611)
            "running-spectator" -> FixtureSource(night(ContestView.RUNNING, ContestView.SPECTATOR), friends) to go(ContestsNavState.CONTEST, 2611)
            "running-reconnecting" -> FixtureSource(night(ContestView.RUNNING, ContestView.PARTICIPANT), friends, conn = Connection(Connection.RECONNECTING, since = t0 - 40_000, dataAt = t0 - 40_000)) to go(ContestsNavState.CONTEST, 2611)
            "running-offline" -> FixtureSource(night(ContestView.RUNNING, ContestView.PARTICIPANT), friends, conn = Connection(Connection.OFFLINE, dataAt = t0 - 6 * MIN), queuedRuns = queued()) to go(ContestsNavState.CONTEST, 2611)
            "running-back" -> FixtureSource(night(ContestView.RUNNING, ContestView.PARTICIPANT), friends, conn = Connection(Connection.BACK, since = t0 - 10_000)) to go(ContestsNavState.CONTEST, 2611)
            "player" -> FixtureSource(night(ContestView.RUNNING, ContestView.OWNER), friends) to go(ContestsNavState.PLAYER, 2611, TUFF.uuid)
            "player-me-offline" -> FixtureSource(night(ContestView.RUNNING, ContestView.PARTICIPANT), friends, conn = Connection(Connection.OFFLINE, dataAt = t0 - 6 * MIN), queuedRuns = queued()) to go(ContestsNavState.PLAYER, 2611, WREN.uuid)
            "finished" -> FixtureSource(night(ContestView.FINISHED, ContestView.OWNER, finishedLines(null)), friends) to go(ContestsNavState.CONTEST, 2611)
            "finished-tie" -> FixtureSource(night(ContestView.FINISHED, ContestView.PARTICIPANT, finishedLines("tie")), friends) to go(ContestsNavState.CONTEST, 2611)
            "finished-shared" -> FixtureSource(night(ContestView.FINISHED, ContestView.PARTICIPANT, finishedLines("shared"), tie = "shared"), friends) to go(ContestsNavState.CONTEST, 2611)
            "finished-updated" -> FixtureSource(night(ContestView.FINISHED, ContestView.PARTICIPANT, finishedLines(null), tie = "updated"), friends) to go(ContestsNavState.CONTEST, 2611)
            "canceled" -> FixtureSource(night(ContestView.CANCELED, ContestView.PARTICIPANT), friends) to go(ContestsNavState.CONTEST, 2611)
            "past" -> FixtureSource(night(ContestView.FINISHED, ContestView.PARTICIPANT, finishedLines(null)), friends) to go(ContestsNavState.PAST, 2611).also { it.fromHistory = true }
            "history" -> FixtureSource(night(ContestView.FINISHED, ContestView.PARTICIPANT, finishedLines(null)), friends) to go(ContestsNavState.HISTORY)
            "checks" -> FixtureSource(null, friends) to go(ContestsNavState.CHECKS)
            else -> null
        }
    }

    val NAMES = listOf(
        "home", "home-empty", "friends", "friends-empty", "friends-down", "friends-added", "create",
        "lobby-owner", "lobby-manager", "lobby-participant", "lobby-spectator",
        "running-owner", "running-manager", "running-participant", "running-spectator", "running-reconnecting", "running-offline", "running-back",
        "player", "player-me-offline", "finished", "finished-tie", "finished-shared", "finished-updated", "canceled", "past", "history", "checks",
    )

    /** `/soul dev contests fixture <name>`, `hud <name> [expanded]`, `hud off`, `toast <kind>`, `reveal <podium|rollup|banner>`. */
    fun devNode(): LiteralArgumentBuilder<FabricClientCommandSource> = ClientCommands.literal("contests")
        .requires { DevAccess.unlocked }
        .then(ClientCommands.literal("fixture").then(ClientCommands.argument("name", StringArgumentType.word())
            .suggests { _, b -> NAMES.forEach(b::suggest); b.buildFuture() }
            .executes { ctx ->
                val (src, nav) = build(StringArgumentType.getString(ctx, "name")) ?: return@executes 0
                val mc = net.minecraft.client.Minecraft.getInstance()
                mc.execute { mc.setScreenAndShow(ContestsScreen(src, nav)) }
                1
            }))
        .then(ClientCommands.literal("hud").then(ClientCommands.argument("name", StringArgumentType.greedyString())
            .executes { ctx ->
                val arg = StringArgumentType.getString(ctx, "name").split(' ')
                if (arg[0] == "off") ContestHud.fixture = false
                else build(arg[0])?.let { ContestHud.source = it.first; ContestHud.fixture = true }
                if (!ContestHud.fixture) ContestHud.source = StoreContestsSource
                ContestHud.expandedOverride = if (arg.getOrNull(1) == "expanded") true else if (arg.getOrNull(1) == "compact") false else null
                1
            }))
        .then(ClientCommands.literal("toast").then(ClientCommands.argument("kind", StringArgumentType.word())
            .suggests { _, b -> listOf("passed", "finished", "flagged", "invite", "updated", "won", "over").forEach(b::suggest); b.buildFuture() }
            .executes { ctx ->
                val k = StringArgumentType.getString(ctx, "kind")
                when (k) {
                    "passed" -> ContestToasts.show(ContestToasts.WARN, "ct_up", "MossyMira passed you", "You're 2nd now · 2.5M behind 1st")
                    "finished" -> ContestToasts.show(ContestToasts.OK, "ct_trophy", "Basalt_Ben finished", "Final total 22M. 2 players are still going.")
                    "flagged" -> ContestToasts.show(ContestToasts.BAD, "ct_flag", "Your run 30 was flagged", "Hypixel's counters don't show this run. It still counts. /soul contests")
                    "invite" -> ContestToasts.show(ContestToasts.INFO, "ct_bell", "Gem_Otis invited you", "Sunday Sprint · 25 runs each. Join in chat or /soul contests")
                    "updated" -> ContestToasts.show(ContestToasts.WARN, "ct_refresh", "Nucleus Night standings updated", "A late run by Wren_Digs counted. You're 2nd now.")
                    "won" -> ContestToasts.show(ContestToasts.OK, "ct_crown", "You won Nucleus Night!", "31.2M from 50 runs · 1.4M ahead of 2nd")
                    else -> ContestToasts.show(ContestToasts.OK, "ct_trophy", "Nucleus Night is over", "MossyMira wins with 31.2M. You placed 2nd. /soul contests")
                }
                1
            }))
        .then(ClientCommands.literal("reveal").then(ClientCommands.argument("variant", StringArgumentType.word())
            .suggests { _, b -> listOf("podium", "rollup", "banner").forEach(b::suggest); b.buildFuture() }
            .executes { ctx ->
                val v = StringArgumentType.getString(ctx, "variant")
                ContestEndReveal.self = { WREN.uuid }
                ContestEndReveal.start(night(ContestView.FINISHED, ContestView.PARTICIPANT, finishedLines(null)), v)
                1
            }))
}
