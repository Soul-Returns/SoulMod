package com.soulreturns.features.contests.model

// The contract's enums as String constants. Values outside KNOWN are kept as they came (never a decode
// failure); callers treat them as "something newer" (refetch, generic label, ignore).

object ContestStates {
    const val DRAFT = "draft"
    const val LOBBY = "lobby"
    const val RUNNING = "running"
    const val FINISHED = "finished"
    const val CANCELED = "canceled"
    val KNOWN = setOf(DRAFT, LOBBY, RUNNING, FINISHED, CANCELED)
    fun isOver(state: String): Boolean = state == FINISHED || state == CANCELED
}

object Roles {
    const val OWNER = "owner"
    const val MANAGER = "manager"
    const val PARTICIPANT = "participant"
    const val SPECTATOR = "spectator"
    val KNOWN = setOf(OWNER, MANAGER, PARTICIPANT, SPECTATOR)

    /** Owner, manager and participant play; spectators watch. */
    fun plays(role: String): Boolean = role == OWNER || role == MANAGER || role == PARTICIPANT
    fun manages(role: String): Boolean = role == OWNER || role == MANAGER
}

object MemberStatuses {
    const val INVITED = "invited"
    const val REQUESTED = "requested"
    const val JOINED = "joined"
    const val DECLINED = "declined"
    const val REJECTED = "rejected"
    const val LEFT = "left"
    const val REMOVED = "removed"
    const val DISQUALIFIED = "disqualified"
    val KNOWN = setOf(INVITED, REQUESTED, JOINED, DECLINED, REJECTED, LEFT, REMOVED, DISQUALIFIED)
}

object EndReasons {
    const val DEADLINE = "deadline"
    const val ALL_FINISHED = "all_finished"
    const val ENDED_EARLY = "ended_early"
    val KNOWN = setOf(DEADLINE, ALL_FINISHED, ENDED_EARLY)
}

object CheckStates {
    const val PENDING = "pending"
    const val VERIFIED = "verified"
    const val FLAGGED = "flagged"
    const val VOIDED = "voided"
    val KNOWN = setOf(PENDING, VERIFIED, FLAGGED, VOIDED)
}

object CheckReasons {
    const val WAITING = "waiting"
    const val API_OFF = "api_off"
    const val API_ERROR = "api_error"
    const val NO_BASELINE = "no_baseline"
    const val PROFILE_NOT_FOUND = "profile_not_found"
    const val NOT_IN_API = "not_in_api"
    const val TOO_FAST = "too_fast"
    const val DUPLICATE_LOOT = "duplicate_loot"
    const val IMPLAUSIBLE_LOOT = "implausible_loot"
    const val IMPLAUSIBLE_VALUE = "implausible_value"
    val KNOWN = setOf(
        WAITING, API_OFF, API_ERROR, NO_BASELINE, PROFILE_NOT_FOUND,
        NOT_IN_API, TOO_FAST, DUPLICATE_LOOT, IMPLAUSIBLE_LOOT, IMPLAUSIBLE_VALUE,
    )
}

object FriendRequestResults {
    const val SENT = "sent"
    const val ACCEPTED = "accepted"
}

object GoalKinds {
    const val UNITS = "units"
}

object MetricFormats {
    const val COINS = "coins"
    const val COUNT = "count"
    const val DURATION = "duration"
}

/** `me.can` action names (contract 5). */
object Can {
    const val UPLOAD = "upload"
    const val READY = "ready"
    const val LEAVE = "leave"
    const val JOIN = "join"
    const val DECLINE = "decline"
    const val INVITE = "invite"
    const val APPOINT = "appoint"
    const val EDIT = "edit"
    const val OPEN = "open"
    const val START = "start"
    const val APPROVE = "approve"
    const val REMOVE = "remove"
    const val DISQUALIFY = "disqualify"
    const val VOID = "void"
    const val END = "end"
    const val CANCEL = "cancel"
}

object FeedKinds {
    const val CREATED = "created"
    const val INVITE = "invite"
    const val JOIN = "join"
    const val DECLINE = "decline"
    const val LEAVE = "leave"
    const val REQUEST = "request"
    const val APPROVE = "approve"
    const val REJECT = "reject"
    const val ROLE = "role"
    const val REMOVE = "remove"
    const val DQ = "dq"
    const val EDIT = "edit"
    const val START = "start"
    const val RUN = "run"
    const val DROP = "drop"
    const val PASS = "pass"
    const val GOAL = "goal"
    const val FLAG = "flag"
    const val VOID = "void"
    const val RESTORE = "restore"
    const val END = "end"
    const val CANCEL = "cancel"
    val KNOWN = setOf(
        CREATED, INVITE, JOIN, DECLINE, LEAVE, REQUEST, APPROVE, REJECT, ROLE, REMOVE, DQ, EDIT,
        START, RUN, DROP, PASS, GOAL, FLAG, VOID, RESTORE, END, CANCEL,
    )
}

/** `contest-update` kinds. */
object UpdateKinds {
    const val RUN = "run"
    const val CHECK = "check"
    const val VOID = "void"
    const val MEMBER = "member"
    const val STATE = "state"
    const val SETTINGS = "settings"
    val KNOWN = setOf(RUN, CHECK, VOID, MEMBER, STATE, SETTINGS)
}

/** `friend-update` kinds. */
object FriendUpdateKinds {
    const val REQUEST = "request"
    const val ACCEPTED = "accepted"
    const val DECLINED = "declined"
    const val CANCELED = "canceled"
    const val REMOVED = "removed"
}
