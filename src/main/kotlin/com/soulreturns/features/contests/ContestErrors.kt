package com.soulreturns.features.contests

import com.soulreturns.features.contests.model.ApiError

/** English sentences for the contract's error codes (the server's `message` is for logs, not the UI). */
object ContestErrors {
    private val TEXT = mapOf(
        "unauthorized" to "Soul could not sign you in. Try again in a moment.",
        "rate_limited" to "Too many requests. Wait a moment and try again.",
        "not_found" to "That contest does not exist or you are not in it.",
        "body_too_large" to "That was too much data to send.",
        "invalid_body" to "Soul sent something the server did not understand.",
        "invalid_name" to "That is not a valid name.",
        "lookup_unavailable" to "Soul could not look that name up right now. Try again in a moment.",
        "self" to "That's you.",
        "not_a_user" to "That player doesn't use Soul.",
        "already_friends" to "You are already friends.",
        "already_requested" to "You already sent them a request.",
        "you_blocked" to "You blocked that player. Unblock them first.",
        "friend_limit" to "You reached the friend limit (200).",
        "no_request" to "There is no open request.",
        "not_friends" to "You can only invite your Soul friends.",
        "not_blocked" to "That player is not blocked.",
        "unknown_type" to "This contest type is not available.",
        "invalid_goal" to "That goal is not allowed for this contest type.",
        "invalid_time" to "Check the start and end times (at most 7 days).",
        "invalid_profile" to "Your SkyBlock profile is not known yet. Join SkyBlock and try again.",
        "too_many_players" to "A contest has at most 10 players.",
        "too_many_spectators" to "A contest has at most 20 spectators.",
        "too_many_contests" to "You can own at most 3 open contests.",
        "forbidden" to "You are not allowed to do that.",
        "owner_only" to "Only the owner can do that.",
        "already_joined" to "You already joined.",
        "contest_full" to "The contest is full.",
        "late_join_closed" to "This contest does not allow joining after the start.",
        "not_open" to "The contest is not open for that any more.",
        "not_invited" to "You are not invited.",
        "owner_cannot_leave" to "The owner cannot leave; cancel the contest instead.",
        "already_member" to "That player is already in the contest.",
        "no_players" to "No one has joined with a profile yet.",
        "not_running" to "The contest is not running.",
        "already_voided" to "That run is already voided.",
        "not_voided" to "That run is not voided.",
        "future_time" to "The run's time is in the future (check your clock).",
        "not_player" to "You are not playing in this contest.",
        "disqualified" to "You were disqualified from this contest.",
        "left" to "You left this contest.",
        "no_profile" to "No SkyBlock profile is locked for you in this contest.",
        "profile_mismatch" to "This run is from a different SkyBlock profile than the one you joined with.",
        "outside_window" to "The run happened outside the contest's time.",
        "late_upload_closed" to "The contest ended more than 24 hours ago.",
        ContestsApi.ERR_TRANSPORT to "Soul's server is unreachable right now.",
        ContestsApi.ERR_MALFORMED to "The server's answer could not be read. Update Soul?",
        ContestsApi.ERR_ALPHA to "Contests are not available on the Alpha network.",
        ContestsApi.ERR_NO_AUTH to "Soul could not sign you in. Try again in a moment.",
        ContestsApi.ERR_UNKNOWN to "Something went wrong.",
    )

    fun describe(code: String?): String = TEXT[code] ?: "Something went wrong."

    fun describe(error: ApiError?): String = describe(error?.error)
}
