package com.soulreturns.features.contests

import com.soulreturns.api.HypixelNetwork
import com.soulreturns.core.events.Events
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestStates
import com.soulreturns.features.contests.model.MemberStatuses
import com.soulreturns.features.contests.model.RunUpload
import com.soulreturns.features.contests.model.UploadItem
import com.soulreturns.features.mining.nucleus.NucleusRunFinished
import com.soulreturns.stats.PlayerDataset
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.SoulLogger
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * What capture needs to know about a contest you play in, kept on disk with the outbox so a run is
 * still recorded when the backend is unreachable at login (no snapshot yet).
 */
@Serializable
internal data class CaptureTarget(
    val contestId: Long,
    val type: String,
    val state: String,
    /** Your locked profile, undashed lowercase; null = none locked yet. */
    val profileId: String? = null,
    val startedAt: Long? = null,
    val endsAt: Long? = null,
    val name: String = "",
)

/**
 * Gating for every [ContestCapture]: a run is recorded only for a contest of its type that is running,
 * in which you play (owner/manager/participant, joined), on the profile you joined with, on production
 * (never Hypixel's Alpha network) and inside the contest window. Then it gets a client `runId` and goes
 * to the [ContestOutbox] (disk first, upload after).
 */
object ContestCaptures {
    private val logger = SoulLogger("Soul/Contests")
    const val NUCLEUS_PROFIT = "nucleus-profit"

    private val captures = LinkedHashMap<String, ContestCapture>()

    // seams for tests
    internal var alpha: () -> Boolean = { HypixelNetwork.isAlpha || !PlayerDataset.confirmedProduction() }
    internal var currentProfile: () -> String? = { MojangUuid.undashedOrNull(SkyblockProfileState.profileId)?.lowercase() }
    internal var submit: (Long, RunUpload) -> Unit = { id, run -> ContestOutbox.submit(id, run) }
    internal var targets: () -> List<CaptureTarget> = { ContestOutbox.targets() }
    internal var saveTarget: (Long, CaptureTarget?) -> Unit = { id, t -> ContestOutbox.setTarget(id, t) }
    internal var newRunId: () -> String = { MojangUuid.undashed(UUID.randomUUID()) }

    fun register() {
        add(NucleusProfitCapture)
    }

    fun add(capture: ContestCapture) {
        if (captures.putIfAbsent(capture.typeId, capture) == null) capture.register()
    }

    fun supports(typeId: String): Boolean = typeId in captures

    fun currentProfileUndashed(): String? = currentProfile()

    /** Keeps the on-disk target of [snap] in step (called by the store for every accepted snapshot). */
    internal fun onSnapshot(snap: ContestSnapshot) {
        val id = snap.contest.id
        val plays = snap.me.plays && snap.me.status == MemberStatuses.JOINED
        val live = snap.contest.state == ContestStates.RUNNING || snap.contest.state == ContestStates.LOBBY
        val target = if (plays && live) {
            CaptureTarget(id, snap.contest.type, snap.contest.state, snap.me.profileId?.lowercase(), snap.contest.startedAt, snap.contest.endsAt, snap.contest.name)
        } else {
            null
        }
        val held = targets().firstOrNull { it.contestId == id }
        if (held != target) saveTarget(id, target)
    }

    /** Held snapshot of [id] as a target, else the one on disk. */
    private fun target(id: Long): CaptureTarget? {
        val snap = ContestsStore.heldSnapshots().firstOrNull { it.contest.id == id }
        if (snap != null) {
            if (!(snap.me.plays && snap.me.status == MemberStatuses.JOINED)) return null
            return CaptureTarget(id, snap.contest.type, snap.contest.state, snap.me.profileId?.lowercase(), snap.contest.startedAt, snap.contest.endsAt, snap.contest.name)
        }
        return targets().firstOrNull { it.contestId == id }
    }

    private fun allTargets(): List<CaptureTarget> {
        val ids = LinkedHashSet<Long>()
        ContestsStore.heldSnapshots().forEach { ids += it.contest.id }
        targets().forEach { ids += it.contestId }
        return ids.mapNotNull(::target)
    }

    /** Capture state of [contestId] for the UI; null when you do not play in it. */
    fun status(contestId: Long): CaptureStatus? {
        val t = target(contestId) ?: return null
        return CaptureStatus(contestId, stateOf(t), t.profileId, currentProfile())
    }

    internal fun stateOf(t: CaptureTarget): String {
        val current = currentProfile()
        return when {
            alpha() -> CaptureStates.ALPHA
            !supports(t.type) -> CaptureStates.UNSUPPORTED_TYPE
            t.state != ContestStates.RUNNING -> CaptureStates.NOT_RUNNING
            t.profileId == null -> CaptureStates.NO_PROFILE_LOCKED
            current == null -> CaptureStates.PROFILE_UNKNOWN
            current != t.profileId -> CaptureStates.WRONG_PROFILE
            else -> CaptureStates.ACTIVE
        }
    }

    /**
     * Called by a capture with one finished run. [profileId] = the profile at the run (any form), [body]
     * builds the upload for each contest that accepts it. Returns the contests it was queued for.
     */
    fun record(
        typeId: String,
        finishedAt: Long,
        profileId: String?,
        body: (runId: String, seq: Int, profileId: String) -> RunUpload,
    ): List<Long> {
        if (alpha()) return emptyList()
        val profile = MojangUuid.undashedOrNull(profileId)?.lowercase() ?: return emptyList()
        val queued = ArrayList<Long>()
        for (t in allTargets()) {
            if (t.type != typeId || t.state != ContestStates.RUNNING) continue
            if (t.profileId == null || t.profileId != profile) continue
            if (t.startedAt != null && finishedAt < t.startedAt) continue
            if (t.endsAt != null && finishedAt > t.endsAt) continue
            val seq = ContestOutbox.nextSeq(t.contestId)
            submit(t.contestId, body(newRunId(), seq, profile))
            queued += t.contestId
        }
        if (queued.isNotEmpty()) logger.info("Contest run recorded for ${queued.joinToString()}")
        return queued
    }
}

/** `nucleus-profit`: one run = one Crystal Nucleus loot bundle ([NucleusRunFinished]). */
object NucleusProfitCapture : ContestCapture {
    override val typeId: String = ContestCaptures.NUCLEUS_PROFIT

    override fun register() {
        Events.subscribe<NucleusRunFinished> { onRun(it) }
    }

    internal fun onRun(e: NucleusRunFinished): List<Long> =
        ContestCaptures.record(typeId, e.finishedAt, e.profileId) { runId, seq, profile -> upload(e, runId, seq, profile) }

    /** The contract's upload body (60 items max, names 1..100 chars, counts 1..1 000 000). */
    internal fun upload(e: NucleusRunFinished, runId: String, seq: Int, profileId: String): RunUpload = RunUpload(
        schemaVersion = 1,
        runId = runId,
        seq = seq,
        profileId = profileId,
        finishedAt = e.finishedAt,
        startedAt = e.startedAt,
        durationMs = e.durationMs?.takeIf { it in 1..86_400_000L },
        partial = e.partial,
        items = e.items.filter { it.name.isNotBlank() && it.count > 0 }.take(60)
            .map { UploadItem(it.name.take(100), it.id, it.count.coerceAtMost(1_000_000L)) },
        powder = e.powder.mapValues { it.value.coerceIn(0L, 10_000_000L) },
    )
}
