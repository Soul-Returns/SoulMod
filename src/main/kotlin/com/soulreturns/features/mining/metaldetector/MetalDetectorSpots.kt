package com.soulreturns.features.mining.metaldetector

import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.StatsHandle
import kotlinx.serialization.Serializable

@Serializable
data class LearnedSpot(val count: Long = 0L, val firstSeen: Long = 0L, val lastSeen: Long = 0L)

/** Chest offsets dug up with a solved waypoint, keyed `"dx,dy,dz"` relative to the center. */
@Serializable
data class LearnedSpots(val spots: Map<String, LearnedSpot> = emptyMap())

/**
 * `metaldetectorSpots` stats namespace: every solved chest offset, so spots missing from
 * [MetalDetectorSolver.OFFSETS] join the candidate list for later rounds. Not per profile: the
 * Mines of Divan layout is the same for everyone.
 */
object MetalDetectorSpots {
    const val NAMESPACE = "metaldetectorSpots"
    private const val KEY = "all"

    private val handle: StatsHandle<LearnedSpots> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = LearnedSpots.serializer(),
            default = { LearnedSpots() },
            key = { KEY },
        )
    }

    fun all(): Map<ChestOffset, LearnedSpot> =
        handle.read().spots.mapNotNull { (k, v) -> parse(k)?.let { it to v } }.toMap()

    /** Learned offsets that are not in the built-in list. */
    fun extras(): List<ChestOffset> = all().keys.filter { it !in MetalDetectorSolver.OFFSETS }

    fun record(offset: ChestOffset) {
        val now = System.currentTimeMillis()
        val key = "${offset.x},${offset.y},${offset.z}"
        handle.update {
            val old = spots[key]
            copy(spots = spots + (key to LearnedSpot((old?.count ?: 0L) + 1, old?.firstSeen ?: now, now)))
        }
    }

    private fun parse(key: String): ChestOffset? {
        val p = key.split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (p.size == 3) ChestOffset(p[0], p[1], p[2]) else null
    }
}
