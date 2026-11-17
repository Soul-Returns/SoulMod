package com.soulreturns.features.mining.mineshaft

import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.util.DebugLogger
import java.time.Instant
import java.util.UUID

/**
 * How the player got into the current Glacite Mineshaft.
 *
 * Plain string constants rather than an enum, deliberately twice over: these **are** the wire values
 * (`"discovered" | "warped" | "unknown"` on `POST /mineshaft/visit`), so there is no mapping layer to
 * get wrong, and the project bans `when (enumValue)` — Kotlin emits a synthetic `$WhenMappings` class
 * that Fabric's `KnotClassLoader` can fail to resolve. With strings, `when (source)` is the safe
 * string form and nothing has to remember the rule.
 */
object VisitSource {
    /** You broke glacite yourself — the `Sending to Mineshaft...` line. This is *your* mineshaft. */
    const val DISCOVERED = "discovered"

    /** A party leader's `/p warp` pulled you in. You are a guest; no alert may claim the find. */
    const val WARPED = "warped"

    /**
     * Neither signal arrived in time. **Not** the same as discovered, which is the whole reason this
     * value reaches the backend: the admin views strict-filter to self-discovered visits when they
     * compute your own spawn/loot odds, and folding `unknown` into that bucket quietly poisons it.
     */
    const val UNKNOWN = "unknown"
}

/**
 * The single source of per-visit truth for the whole mineshaft feature.
 *
 * The legacy mod scattered this across four files — `isWarpedVisit()` on the tracker, `armedAtMs` on
 * two alerts, `pingedThisVisit` on a third — and each owned its own reset. One object with one
 * [beginVisit] removes the entire "did I remember to clear that flag" class of bug.
 *
 * ### Two lifecycles, not one
 * The fields split into two groups and confusing them breaks the feature in opposite directions:
 *
 *  - **Per-visit** ([enteredAt] … [warpSeenAtMs]) — cleared by [beginVisit] on entering the area and
 *    by [endVisit] on leaving.
 *  - **Pre-entry signals** ([sendingSeenAtMs], [summonedSeenAtMs], [summonedBy]) — the two chat lines
 *    that classify a visit arrive **before** `AreaChanged`, in the *previous* world. They must
 *    therefore survive the world transfer, so [beginVisit] must not be the thing that clears them.
 *    It marks them [signalsConsumed] instead, which stops a second visit inheriting a stale
 *    classification without destroying the timestamp the corpse alerts are still counting from.
 *
 * All fields are `@Volatile`: chat handlers and the client tick both touch them, and while both are
 * on the client thread today, a publisher that is not (see `MessageHandler`) would otherwise be an
 * invisible data race.
 */
object MineshaftState {
    /** Max age of a pre-entry chat signal that can still classify the next mineshaft entry. */
    const val SIGNAL_TTL_MS = 10_000L

    /** The tab-list `Area:` value for a Glacite Mineshaft. */
    const val AREA = "Mineshaft"

    /** The sidebar sublocation that makes the identifier parse trustworthy. */
    const val SUBLOCATION = "Glacite Mineshafts"

    // ------------------------------------------------------------------ per visit

    /** Non-null exactly while a visit is in progress. */
    @Volatile
    var enteredAt: Instant? = null
        private set

    /**
     * The idempotency key for this visit, minted at **entry** so a retry from the outbox is provably
     * the same visit as the original POST rather than a fresh one that merely looks alike.
     */
    @Volatile
    var visitId: String = ""
        private set

    /** e.g. `ONYX` — the part of the identifier before the first `_`. Null until the sidebar renders. */
    @Volatile
    var mineshaftType: String? = null

    /** e.g. `ONYX_1` — the full sidebar identifier. */
    @Volatile
    var serverInstance: String? = null

    /** Set the first time Littlefoot is seen **in line of sight** this visit. */
    @Volatile
    var littlefootFound: Boolean = false

    /** One of the [VisitSource] constants. */
    @Volatile
    var source: String = VisitSource.UNKNOWN
        private set

    /** The bare username of the leader who warped us in, or null when this is not a warped visit. */
    @Volatile
    var warpedBy: String? = null
        private set

    /**
     * The SkyBlock profile this visit is on: [SkyblockProfileState.profileId] (dashed lowercase) and
     * the profile name read with it, or null until the id is known. Only ever taken from
     * [SkyblockProfileState] during the visit ([captureProfile]); never guessed.
     */
    @Volatile
    var profileId: String? = null
        private set

    @Volatile
    var profileName: String? = null
        private set

    /** True once `/pc !ptme Found Littlefoot` has gone out for this visit. */
    @Volatile
    var littlefootPinged: Boolean = false

    /** Last **LOS-confirmed** Littlefoot position, and when. `0` = never seen this session. */
    @Volatile
    var sightingAtMs: Long = 0L

    @Volatile
    var sightingX: Int = 0

    @Volatile
    var sightingY: Int = 0

    @Volatile
    var sightingZ: Int = 0

    /** When > 0, the waypoint share is due at this instant + the alert's delay. */
    @Volatile
    var warpSeenAtMs: Long = 0L

    // ------------------------------------------------------------------ pre-entry signals

    /** When `Sending to Mineshaft...` was last seen. Arms both the classification and the alerts. */
    @Volatile
    var sendingSeenAtMs: Long = 0L
        private set

    /** When `Party Leader, <display>, summoned you to their server.` was last seen. */
    @Volatile
    var summonedSeenAtMs: Long = 0L
        private set

    /** The bare username parsed out of that line. */
    @Volatile
    var summonedBy: String? = null
        private set

    /**
     * Whether the signals above have already classified a visit. Without it, one
     * `Sending to Mineshaft...` could classify two entries — and the second would be wrong in the
     * direction that pollutes the own-odds bucket.
     */
    @Volatile
    private var signalsConsumed: Boolean = true

    /** True once the Lapis alert has fired for the current arm. Reset by a fresh `Sending` line. */
    @Volatile
    var lapisFired: Boolean = false

    /** True once the Vanguard alert has fired for the current arm. */
    @Volatile
    var vanguardFired: Boolean = false

    // ------------------------------------------------------------------ transitions

    /** `Sending to Mineshaft...` — self-discovery, and the arm for both corpse alerts. */
    fun onSendingToMineshaft(nowMs: Long) {
        sendingSeenAtMs = nowMs
        signalsConsumed = false
        lapisFired = false
        vanguardFired = false
        DebugLogger.logFeatureEvent("Mineshaft: 'Sending to Mineshaft...' — discovery armed, alerts armed")
    }

    /** `Party Leader, <display>, summoned you to their server.` — a party warp, so we are a guest. */
    fun onSummonedByLeader(
        nowMs: Long,
        leader: String?,
    ) {
        summonedSeenAtMs = nowMs
        summonedBy = leader
        signalsConsumed = false
        DebugLogger.logFeatureEvent("Mineshaft: summoned by ${leader ?: "?"} — warp arrival armed")
    }

    /**
     * Enter a mineshaft: mint a [visitId], clear per-visit state, and resolve [source] from whichever
     * pre-entry signal is still fresh.
     *
     * **The discovered signal wins a tie.** If both are fresh (which should not happen), the
     * mineshaft-specific line is the more specific claim, and being wrong towards `warped` only
     * suppresses an alert while being wrong towards `discovered` publishes a guest visit as your own.
     */
    fun beginVisit(nowMs: Long) {
        enteredAt = Instant.now()
        visitId = UUID.randomUUID().toString()
        mineshaftType = null
        serverInstance = null
        littlefootFound = false
        littlefootPinged = false
        sightingAtMs = 0L
        warpSeenAtMs = 0L

        val discoveredFresh = !signalsConsumed && fresh(sendingSeenAtMs, nowMs)
        val warpedFresh = !signalsConsumed && summonedBy != null && fresh(summonedSeenAtMs, nowMs)

        source = if (discoveredFresh) {
            VisitSource.DISCOVERED
        } else if (warpedFresh) {
            VisitSource.WARPED
        } else {
            VisitSource.UNKNOWN
        }
        warpedBy = if (source == VisitSource.WARPED) summonedBy else null
        signalsConsumed = true

        profileId = null
        profileName = null
        captureProfile()

        DebugLogger.logFeatureEvent("Mineshaft: visit $visitId started — source=$source warpedBy=$warpedBy profile=$profileId")
    }

    /**
     * Take the active profile for this visit, once, as soon as its id is known. Called at entry and
     * every mineshaft tick: entering keeps the id from before the transfer (it survives server changes),
     * and right after a `/profile` switch the id line arrives a few seconds late.
     */
    fun captureProfile() {
        if (enteredAt == null || profileId != null) return
        val id = SkyblockProfileState.profileId ?: return
        val name = SkyblockProfileState.current
        // The id is cleared whenever the name moves, so an unchanged id after reading the name means
        // the two belong together.
        if (SkyblockProfileState.profileId != id) return
        profileId = id
        profileName = name
    }

    /** Leave a mineshaft. Callers must have snapshotted anything they need **before** calling this. */
    fun endVisit() {
        enteredAt = null
        mineshaftType = null
        serverInstance = null
        littlefootFound = false
        littlefootPinged = false
        sightingAtMs = 0L
        warpSeenAtMs = 0L
        source = VisitSource.UNKNOWN
        warpedBy = null
        profileId = null
        profileName = null
    }

    /** True when we are a guest in someone else's mineshaft — every `/pc` alert must skip. */
    fun isWarpedVisit(): Boolean = source == VisitSource.WARPED

    /** True while the corpse alerts' post-`Sending` window is still open. */
    fun alertWindowOpen(
        nowMs: Long,
        windowMs: Long,
    ): Boolean = sendingSeenAtMs != 0L && nowMs - sendingSeenAtMs <= windowMs

    /** Close the alert window — nothing more fires until the next `Sending to Mineshaft...`. */
    fun disarmAlerts() {
        lapisFired = true
        vanguardFired = true
    }

    /** Record an LOS-confirmed Littlefoot position. Only [MobSpotter][com.soulreturns.util.MobSpotter] results belong here. */
    fun recordSighting(
        nowMs: Long,
        x: Int,
        y: Int,
        z: Int,
    ) {
        sightingX = x
        sightingY = y
        sightingZ = z
        sightingAtMs = nowMs
    }

    private fun fresh(
        at: Long,
        nowMs: Long,
    ): Boolean = at != 0L && nowMs - at <= SIGNAL_TTL_MS
}
