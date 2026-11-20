package com.soulreturns.features.profileviewer.gui

import com.soulreturns.data.hypixel.SoulStatsAnswer
import com.soulreturns.data.hypixel.SoulStatsClient
import com.soulreturns.features.profileviewer.ProfileLookupFailure

/**
 * Which state the viewer shows, decided from plain inputs so `ProfileViewerStateTest` covers every
 * combination without a client. Strings, not enums (the `$WhenMappings` trap, see AGENTS.md).
 */
object ProfileViewerState {
    /** Data older than this reads "Loaded N min ago" with a refresh banner. */
    const val STALE_AFTER_MS = 10 * 60_000L

    /** When a 429 carries no `Retry-After`. */
    const val DEFAULT_RETRY_S = 60L

    // Bodies.
    const val BODY_RESOLVING = "resolving"
    const val BODY_LOADING = "loading"
    const val BODY_SECTION = "section"
    const val BODY_NOT_FOUND = "not-found"
    const val BODY_NO_PROFILES = "no-profiles"
    const val BODY_OFFLINE_EMPTY = "offline-empty"
    const val BODY_RATE_LIMITED_EMPTY = "rate-limited-empty"
    const val BODY_ERROR = "error"

    // Banners above the body.
    const val BANNER_NONE = ""
    const val BANNER_STALE = "stale"
    const val BANNER_RATE_LIMITED = "rate-limited"
    const val BANNER_OFFLINE = "offline"

    // Header status.
    const val STATUS_NONE = ""
    const val STATUS_LOADING = "loading"
    const val STATUS_FRESH = "fresh"
    const val STATUS_STALE = "stale"
    const val STATUS_RATE_LIMITED = "rate-limited"
    const val STATUS_OFFLINE = "offline"

    /**
     * @property rail whether the section rail shows (no rail for "this player cannot be shown").
     * @property header "full" header (profile switcher, SkyBlock Level) only with a profile shown.
     */
    data class Shell(val body: String, val banner: String, val status: String, val rail: Boolean)

    /**
     * @param loading a lookup is in flight.
     * @param resolved the name resolved to an account (face and exact name known).
     * @param hasView a profile is on screen (fresh, refreshed, or a cached one kept through a failure).
     * @param failureKind the last lookup's [ProfileLookupFailure] kind, null when it succeeded.
     * @param loadedAtMs when the shown profile was fetched.
     * @param retryUntilMs when a rate limit ends (0 = none).
     */
    fun shell(loading: Boolean, resolved: Boolean, hasView: Boolean, failureKind: String?, nowMs: Long, loadedAtMs: Long, retryUntilMs: Long): Shell {
        if (loading) {
            if (hasView) return Shell(BODY_SECTION, BANNER_NONE, STATUS_LOADING, true)
            return Shell(if (resolved) BODY_LOADING else BODY_RESOLVING, BANNER_NONE, STATUS_LOADING, resolved)
        }
        val limited = retryUntilMs > nowMs
        if (failureKind != null) {
            if (failureKind == ProfileLookupFailure.NOT_FOUND || failureKind == ProfileLookupFailure.INVALID_NAME) {
                return Shell(BODY_NOT_FOUND, BANNER_NONE, STATUS_NONE, false)
            }
            if (failureKind == ProfileLookupFailure.NO_PROFILES) return Shell(BODY_NO_PROFILES, BANNER_NONE, STATUS_FRESH, false)
            if (failureKind == ProfileLookupFailure.RATE_LIMITED) {
                if (!hasView) return Shell(BODY_RATE_LIMITED_EMPTY, BANNER_NONE, if (limited) STATUS_RATE_LIMITED else STATUS_NONE, false)
                return Shell(BODY_SECTION, if (limited) BANNER_RATE_LIMITED else BANNER_STALE, if (limited) STATUS_RATE_LIMITED else STATUS_STALE, true)
            }
            if (failureKind == ProfileLookupFailure.OFFLINE) {
                if (!hasView) return Shell(BODY_OFFLINE_EMPTY, BANNER_NONE, STATUS_OFFLINE, false)
                return Shell(BODY_SECTION, BANNER_OFFLINE, STATUS_OFFLINE, true)
            }
            return Shell(BODY_ERROR, BANNER_NONE, STATUS_NONE, false)
        }
        if (!hasView) return Shell(BODY_RESOLVING, BANNER_NONE, STATUS_NONE, false)
        if (limited) return Shell(BODY_SECTION, BANNER_NONE, STATUS_RATE_LIMITED, true)
        val stale = nowMs - loadedAtMs >= STALE_AFTER_MS
        return Shell(BODY_SECTION, if (stale) BANNER_STALE else BANNER_NONE, if (stale) STATUS_STALE else STATUS_FRESH, true)
    }

    // Soul states (what the Soul cards and the Soul section show).
    const val SOUL_LOADING = "loading"
    const val SOUL_DATA = "data"

    /** 403 or `shared: false`: "<name> keeps their Soul stats private". */
    const val SOUL_PRIVATE = "private"

    /** 404: "<name> doesn't use Soul". */
    const val SOUL_NOT_A_USER = "not-a-user"

    /** Shares, but no tracker recorded anything for them yet. */
    const val SOUL_SHARED_EMPTY = "shared-empty"

    /** You, with nothing recorded yet: "Nothing recorded yet". */
    const val SOUL_SELF_EMPTY = "self-empty"

    /** Rate limited or unreachable: the cards say "couldn't load" instead of guessing a reason. */
    const val SOUL_UNAVAILABLE = "unavailable"

    /** [answer] null = still loading. Your own profile is never "private" or "not a user" to you. */
    fun soul(answer: SoulStatsAnswer?, isSelf: Boolean): String {
        if (answer == null) return SOUL_LOADING
        if (answer.envelope != null && answer.envelope.namespaces.isNotEmpty()) return SOUL_DATA
        if (answer.code == SoulStatsClient.CODE_UNAVAILABLE) return SOUL_UNAVAILABLE
        if (isSelf) return SOUL_SELF_EMPTY
        if (answer.code == SoulStatsClient.CODE_NOT_SHARED) return SOUL_PRIVATE
        if (answer.code == SoulStatsClient.CODE_NOT_A_USER) return SOUL_NOT_A_USER
        if (answer.code == SoulStatsClient.CODE_NO_NAMESPACES) return SOUL_SHARED_EMPTY
        return SOUL_UNAVAILABLE
    }
}
