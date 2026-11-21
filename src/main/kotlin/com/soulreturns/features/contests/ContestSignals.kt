package com.soulreturns.features.contests

import com.soulreturns.core.events.Event
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestSummary
import com.soulreturns.features.contests.model.PlayerRef

// Moments the UI turns into toasts / the end reveal. Published on the client thread through
// `Events.publish`, only for changes seen live (never for state that was already there at the first
// load). Subscribe to each concrete type (the bus does not walk inheritance). The toast surface checks
// `cfg.contests.toasts` itself; the store never filters by settings.

/** Someone moved above you. [myRank] is your rank now; [behind] the coin gap to the rank above, if known. */
data class ContestPassedSignal(
    val contestId: Long,
    val contestName: String,
    val by: PlayerRef,
    val myRank: Int?,
    val behind: Long?,
) : Event

/** A playing member (you included) reached the goal. [stillGoing] = joined players below the goal. */
data class ContestPlayerFinishedSignal(
    val contestId: Long,
    val contestName: String,
    val player: PlayerRef,
    val total: Long,
    val stillGoing: Int,
    val isMe: Boolean,
) : Event

/** You were invited (first seen this session). */
data class ContestInvitedSignal(val summary: ContestSummary) : Event

/** One of your runs was flagged. [n] is its position among the counted runs, when counted. */
data class ContestRunFlaggedSignal(
    val contestId: Long,
    val contestName: String,
    val runId: String,
    val n: Int?,
    val reason: String?,
) : Event

/** A contest you are in started (lobby -> running, seen live). */
data class ContestStartedSignal(val snapshot: ContestSnapshot) : Event

/**
 * A contest you are in finished (or was canceled: check `snapshot.contest.state`). [missed] = it ended
 * while this client was not watching (found at the next load, ended under an hour ago); the reveal keeps
 * its own "played once per id" memory. [won] = your row has rank 1.
 */
data class ContestEndedSignal(val snapshot: ContestSnapshot, val missed: Boolean, val won: Boolean) : Event

/** A late run changed the final standings after the end. Ranks are yours (null for spectators). */
data class ContestStandingsUpdatedSignal(
    val contestId: Long,
    val contestName: String,
    val oldRank: Int?,
    val newRank: Int?,
    val runner: PlayerRef?,
) : Event

/** A friend request arrived (realtime). */
data class FriendRequestSignal(val from: PlayerRef) : Event
