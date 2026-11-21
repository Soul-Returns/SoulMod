package com.soulreturns.features.contests

import com.soulreturns.features.contests.model.ContestSnapshot

/** Why Soul does or does not record runs for a contest (see [CaptureStatus.state]). */
object CaptureStates {
    /** Runs are recorded and sent. */
    const val ACTIVE = "active"
    /** The contest is not running (lobby, finished, …). */
    const val NOT_RUNNING = "not_running"
    /** You watch, left, were disqualified or have not joined. */
    const val NOT_PLAYING = "not_playing"
    /** No profile is locked for you yet (join with a profile first). */
    const val NO_PROFILE_LOCKED = "no_profile_locked"
    /** The current SkyBlock profile is not known yet (not on SkyBlock, or before the profile chat line). */
    const val PROFILE_UNKNOWN = "profile_unknown"
    /** You are on a different SkyBlock profile than the one you joined with. */
    const val WRONG_PROFILE = "wrong_profile"
    /** Hypixel's Alpha network: nothing is recorded. */
    const val ALPHA = "alpha"
    /** This Soul version cannot record runs of that contest type. */
    const val UNSUPPORTED_TYPE = "unsupported_type"
}

/**
 * Capture state for one contest. [lockedProfileId] / [currentProfileId] are undashed lowercase.
 */
data class CaptureStatus(
    val contestId: Long,
    val state: String,
    val lockedProfileId: String?,
    val currentProfileId: String?,
) {
    val active: Boolean get() = state == CaptureStates.ACTIVE
}

/**
 * One contest type's run capture (the only implementation today: [NucleusProfitCapture]). It turns the
 * game's own signal into [com.soulreturns.features.contests.model.RunUpload]s via [ContestCaptures.record];
 * gating (participant, running, profile lock, Alpha) is done once in [ContestCaptures], never here.
 */
interface ContestCapture {
    /** The contest type id this capture serves, e.g. `nucleus-profit`. */
    val typeId: String

    /** Subscribe to the game signal; called once at startup. */
    fun register()
}

/** Snapshot-facing helpers used by the store; implemented in [ContestCaptures]. */
internal fun ContestSnapshot.playsAsMe(): Boolean = me.plays && me.status == com.soulreturns.features.contests.model.MemberStatuses.JOINED
