package com.soulreturns.features.mining.mineshaft

import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.StatsHandle
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** One corpse stand seen on a spot the bundled data does not list: its exact position. */
@Serializable
data class LearnedCorpseSpot(val pos: List<Double>, val type: String? = null, val seen: Int = 1, val firstSeen: Long = 0L, val lastSeen: Long = 0L)

/**
 * Stands seen on unlisted spots, per layout id as the sidebar reports it. Pure: [withSighting] merges a
 * sighting, [forLayout] reads what applies to a layout (every crystal shaft shares its spots).
 */
@Serializable
data class LearnedCorpseSpots(val version: Int = VERSION, val layouts: Map<String, List<LearnedCorpseSpot>> = emptyMap()) {
    /** The learned spots for [layout]; a crystal shaft gets every `_C` layout's, merged within [MERGE]. */
    fun forLayout(layout: String): List<LearnedCorpseSpot> {
        if (!CorpseSpotData.isCrystal(layout)) return layouts[layout].orEmpty()
        val out = ArrayList<LearnedCorpseSpot>()
        for ((id, spots) in layouts) {
            if (!CorpseSpotData.isCrystal(id)) continue
            for (s in spots) if (out.none { near(it.pos, s.pos) }) out += s
        }
        return out
    }

    /**
     * A stand of [type] seen at ([x], [y], [z]) in [layout]: counted on a learned spot within
     * [MERGE] (for a crystal shaft, under whichever `_C` id holds it), else added under [layout].
     * Unchanged when it stands on a [listed] spot, or the caps ([MAX_LAYOUTS], [MAX_PER_LAYOUT]) are reached.
     */
    fun withSighting(layout: String, x: Double, y: Double, z: Double, type: String?, now: Long, listed: List<CorpseSpot>?): LearnedCorpseSpots {
        if (listed != null && listed.any { CorpseLocatorSolver.standOffset(it, x, y, z) != null }) return this
        val pos = listOf(round(x), round(y), round(z))
        val ids = if (CorpseSpotData.isCrystal(layout)) layouts.keys.filter { CorpseSpotData.isCrystal(it) } else listOf(layout)
        for (id in ids) {
            val spots = layouts[id] ?: continue
            val i = spots.indexOfFirst { near(it.pos, pos) }
            if (i < 0) continue
            val old = spots[i]
            val updated = old.copy(type = old.type ?: type, seen = old.seen + 1, lastSeen = now)
            return copy(layouts = layouts + (id to spots.toMutableList().also { it[i] = updated }))
        }
        val spots = layouts[layout]
        if (spots == null && layouts.size >= MAX_LAYOUTS) return this
        if (spots != null && spots.size >= MAX_PER_LAYOUT) return this
        return copy(layouts = layouts + (layout to spots.orEmpty() + LearnedCorpseSpot(pos, type, 1, now, now)))
    }

    companion object {
        const val VERSION = 1

        /** Two sightings this close are one spot (the same spot's stand stands at the same position every visit). */
        const val MERGE = 0.5
        const val MAX_LAYOUTS = 64
        const val MAX_PER_LAYOUT = 16

        private fun near(a: List<Double>, b: List<Double>): Boolean {
            val dx = a[0] - b[0]
            val dy = a[1] - b[1]
            val dz = a[2] - b[2]
            return sqrt(dx * dx + dy * dy + dz * dz) <= MERGE
        }

        private fun round(v: Double): Double = Math.round(v * 1000) / 1000.0
    }
}

/**
 * `corpseSpots` stats namespace (one global bucket: mineshaft layouts are the same for everyone;
 * the stats store keeps Alpha in its own dataset): corpse stands the player saw with line of sight
 * on spots the bundled data does not list. The locator uses them as an incomplete list (sight only,
 * [CorpseLocatorSolver]); `/soul dev corpsespots` prints them in the data file's form for bundling.
 */
object CorpseSpotMemory {
    const val NAMESPACE = "corpseSpots"
    private const val KEY = "all"

    private val handle: StatsHandle<LearnedCorpseSpots> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = LearnedCorpseSpots.serializer(),
            default = { LearnedCorpseSpots() },
            key = { KEY },
        )
    }

    fun all(): LearnedCorpseSpots = handle.read()

    fun forLayout(layout: String): List<DoubleArray> = all().forLayout(layout).map { doubleArrayOf(it.pos[0], it.pos[1], it.pos[2]) }

    /** A corpse stand seen with line of sight; ignored when it stands on a listed spot. */
    fun record(layout: String, x: Double, y: Double, z: Double, type: String?) {
        val listed = CorpseLocator.data.spots(layout)
        val now = System.currentTimeMillis()
        handle.update { withSighting(layout, x, y, z, type, now, listed) }
    }
}
