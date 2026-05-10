package com.soulreturns.features.farming.seasoning

import com.soulreturns.features.farming.FarmingTimer
import com.soulreturns.stats.PersistentStats

/**
 * Single owner of all seasoning-related mutable state.
 *
 * Reads are pulled from [PersistentStats] (canonical persistence layer) and from
 * [FarmingTimer] (the global active-farming clock). Writes funnel through the methods here so
 * the persistence and session-metric updates stay in sync.
 */
object SeasoningState {
    /** Chat-driven gain since the last [resetSession]. Used by the per-hour rate. */
    @Volatile
    var sessionChatGain: Long = 0L
        private set

    /** Snapshot of [FarmingTimer.totalMs] at session start; subtracted to get session-local farming time. */
    @Volatile
    private var farmingTimerCheckpointMs: Long = 0L

    /** The persisted total seasoning count. Same as `PersistentStats.current.seasonings`. */
    val total: Long get() = PersistentStats.current.seasonings

    /** Sorted ascending milestone Y values from the most recent menu read (e.g. `[5, 25, 75, 150, 250]`). */
    val targets: List<Long> get() = PersistentStats.current.milestoneTargets

    fun init() {
        farmingTimerCheckpointMs = FarmingTimer.totalMs
    }

    /** Bumps persisted total + session chat gain. Called once per `RARE CROP! Seasoning` chat line. */
    fun incrementFromChat() {
        PersistentStats.update { seasonings += 1 }
        sessionChatGain += 1
    }

    /**
     * Apply a Harvest Feast menu read. Always saves [targets]; [total] is only written when
     * non-null (the cumulative-aware decoder returns null for the all-maxed / transitioning state).
     */
    fun applyMenuSnapshot(
        total: Long?,
        targets: List<Long>
    ) {
        PersistentStats.update {
            milestoneTargets = targets
            if (total != null) seasonings = total
        }
    }

    /** `/soul dev resetSeasonings` — wipe persisted total, milestone targets, and session metrics. */
    fun reset() {
        PersistentStats.update {
            seasonings = 0L
            milestoneTargets = emptyList()
        }
        resetSession()
    }

    /** `[Reset Session]` HUD button — reset only the session-local metrics. Persisted total stays. */
    fun resetSession() {
        sessionChatGain = 0L
        farmingTimerCheckpointMs = FarmingTimer.totalMs
    }

    /** Time spent in the global [FarmingTimer]'s ACTIVE state since the last [resetSession]. */
    fun seasoningFarmingMs(): Long = (FarmingTimer.totalMs - farmingTimerCheckpointMs).coerceAtLeast(0L)
}
