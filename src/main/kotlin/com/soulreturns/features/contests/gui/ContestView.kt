package com.soulreturns.features.contests.gui

import com.soulreturns.features.contests.QueuedRun
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestTypeDescriptor
import com.soulreturns.features.contests.model.FeedEntry
import com.soulreturns.features.contests.model.Member
import com.soulreturns.features.contests.model.StandingRow

/**
 * One contest snapshot read the way the screens need it: your role and permissions, ranked rows,
 * out rows, never-joined invitees, spectators, open join requests. Pure: everything is the server's
 * numbers, only grouped.
 */
class ContestView(val snap: ContestSnapshot, val selfUuid: String, val queued: List<QueuedRun> = emptyList()) {
    val c = snap.contest
    val type: ContestTypeDescriptor = snap.type
    val state: String = c.state
    val running get() = state == RUNNING
    val lobby get() = state == LOBBY || state == DRAFT
    val finished get() = state == FINISHED
    val canceled get() = state == CANCELED
    val over get() = finished || canceled

    val goal: Int = c.goal.units ?: snap.standings.firstOrNull()?.goal ?: 0
    val role: String = snap.me.role
    val isSpectator get() = role == SPECTATOR
    val canManage get() = role == OWNER || role == MANAGER
    fun can(action: String) = snap.me.can(action)

    val members: List<Member> = snap.members
    fun member(uuid: String): Member? = members.firstOrNull { it.uuid == uuid }

    /** Ranked rows (rank set), server order. */
    val ranked: List<StandingRow> = snap.standings.filter { it.rank != null }

    /** Left / disqualified rows, listed after the ranked ones. */
    val out: List<StandingRow> = snap.standings.filter { it.rank == null }

    val me: StandingRow? = snap.standings.firstOrNull { it.uuid == selfUuid }

    val spectators: List<Member> = members.filter { it.role == SPECTATOR && it.status == JOINED }

    /** Invited players who are not playing (running or over): "never joined", plus late-join requests. */
    val never: List<Member> = if (lobby) emptyList() else members.filter {
        it.role != SPECTATOR && (it.status == INVITED || it.status == REQUESTED || it.status == DECLINED || it.status == REJECTED) && snap.standings.none { s -> s.uuid == it.uuid }
    }

    /** Open late-join requests (status requested, or a spectator asking to play). */
    val requests: List<Member> = members.filter { it.request != null }

    val owner get() = c.owner

    fun isMe(uuid: String) = uuid == selfUuid

    /** A row's tie partner when the total ties but the best run decided (the TIEBREAK chip). */
    fun tieBreakWith(row: StandingRow): StandingRow? =
        if (row.shared || row.units == 0) null else ranked.firstOrNull { it !== row && it.units > 0 && it.total == row.total }

    /** Projected finish, best first: average x goal, or the final total once done. */
    class Projection(val row: StandingRow, val value: Long, val final: Boolean)

    fun projection(): List<Projection> = ranked.filter { it.units > 0 }.map {
        val done = it.units >= goal
        Projection(it, if (done) it.total else (it.avg ?: 0) * goal, done)
    }.sortedByDescending { it.value }

    /** Sorted for the Average switch (server ranks stay; only the order changes). */
    fun byAverage(): List<StandingRow> = ranked.sortedWith(compareByDescending<StandingRow> { it.avg ?: 0 }.thenByDescending { it.best })

    /** Pending runs after the end read as "Not confirmed by Hypixel". */
    val unconfirmed: Int get() = if (finished) ranked.sumOf { it.checks.pending } else 0

    /** Feed entries of the kinds this UI knows, newest first; [filter] is `all`, `drops` or `checks`. */
    fun feed(filter: String, now: Long, source: List<FeedEntry> = snap.feed): List<FeedEntry> = source.filter { e ->
        val known = e.kind in FEED_KINDS
        val inFilter = when (filter) {
            "drops" -> e.kind == "drop" || e.kind == "goal" || e.kind == "pass"
            "checks" -> e.kind == "flag" || e.kind == "void" || e.kind == "restore" || e.kind == "dq"
            else -> true
        }
        val fresh = e.kind != "run" || now - e.at <= ContestsLayout.FEED_WINDOW_MIN * 60_000L
        known && inFilter && fresh
    }

    companion object {
        const val DRAFT = "draft"
        const val LOBBY = "lobby"
        const val RUNNING = "running"
        const val FINISHED = "finished"
        const val CANCELED = "canceled"
        const val OWNER = "owner"
        const val MANAGER = "manager"
        const val PARTICIPANT = "participant"
        const val SPECTATOR = "spectator"
        const val INVITED = "invited"
        const val REQUESTED = "requested"
        const val JOINED = "joined"
        const val DECLINED = "declined"
        const val REJECTED = "rejected"
        const val LEFT = "left"
        const val REMOVED = "removed"
        const val DISQUALIFIED = "disqualified"

        val FEED_KINDS = setOf("run", "drop", "pass", "goal", "flag", "void", "restore", "dq", "request", "approve", "reject", "join", "leave", "start", "end", "cancel", "invite", "decline", "remove", "role", "created", "edit")
    }
}
