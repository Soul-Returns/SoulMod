package com.soulreturns.features.mining.mineshaft

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A corpse spawn spot as the data lists it. The dataset mixes conventions (floored, rounded, ceiled
 * stand position), so the stand lies anywhere in the spot's box: [x - SPREAD, x + SPREAD] per axis.
 */
data class CorpseSpot(val x: Int, val y: Int, val z: Int)

/**
 * What one ding said: the distance interval from its pitch byte and the candidates it fits.
 * [explained]: it fits only spots that were already confirmed before it (so it points at nothing new).
 */
data class DingFit(val ix: Int, val iy: Int, val iz: Int, val pitchByte: Int, val lo: Double, val hi: Double, val fitting: List<CorpseSpot>, val explained: Boolean = false)

/**
 * The bundled spawn spots per mineshaft layout (sidebar id, e.g. `AMBE_1`), from meowdding-repo
 * (MIT, `assets/soul/mineshaft/corpse_spots.json`). [fixed] layouts always hold a corpse on their
 * spots (the Vanguard shaft), keyed to its type. Any id not in the file is an unknown layout.
 */
@Serializable
data class CorpseSpotData(val layouts: Map<String, List<List<Int>>> = emptyMap(), val fixed: Map<String, String> = emptyMap()) {
    fun spots(layout: String): List<CorpseSpot>? = layouts[layout]?.map { CorpseSpot(it[0], it[1], it[2]) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CorpseSpotData = json.decodeFromString(serializer(), text)
    }
}

/**
 * Pure solver for the Organ Donor Talisman ding (rule://mineshaft, Corpse locator). The ding is a
 * harp at the player's server-side position whose pitch byte `n = round(pitch * 63)` encodes the 3D
 * distance d from the player's feet to the nearest **unlooted** corpse stand:
 * `n = floor(63 * (2 - (d / 20)^2))`, silent beyond 20 blocks.
 *
 * Fit test: the player is anywhere in the 1/8-block cell the packet position was truncated from
 * ([cellLo]/[cellHi]), the stand anywhere in what is known of it: its seen position ([pin]), else
 * the region the dings left of its box (confirmed spots), else the whole box. A spot is
 * **confirmed** when a ding fits exactly one candidate (spots not looted and not proven empty);
 * confirmed stays until looted or the stay ends. Each ding that fits only one spot also narrows that
 * spot's region ([Region]), which gives the drawn position once it is tight.
 */
class CorpseLocatorSolver(val spots: List<CorpseSpot>, fixed: Boolean = false) {
    private val confirmedSet = LinkedHashSet<CorpseSpot>()
    private val lootedSet = LinkedHashSet<CorpseSpot>()
    private val emptiedSet = LinkedHashSet<CorpseSpot>()
    private val fixedSpots: Set<CorpseSpot> = if (fixed) spots.toSet() else emptySet()
    private val regions = HashMap<CorpseSpot, Region>()
    private val pins = HashMap<CorpseSpot, DoubleArray>()

    init {
        for (s in fixedSpots) confirm(s)
    }

    val confirmed: Set<CorpseSpot> get() = confirmedSet
    val looted: Set<CorpseSpot> get() = lootedSet

    /** Confirmed spots a later ding proved hold no unlooted corpse (a missed loot line or a data error). */
    val emptied: Set<CorpseSpot> get() = emptiedSet

    var lastDing: DingFit? = null
        private set

    fun candidates(): List<CorpseSpot> = spots.filter { it !in lootedSet && it !in emptiedSet }

    /** Feeds one ding (packet ints = position * 8, pitch byte); null when the byte is not a talisman distance. */
    fun ding(ix: Int, iy: Int, iz: Int, pitchByte: Int): DingFit? {
        val (lo, hi) = interval(pitchByte) ?: return null
        // Every unlooted corpse is at least `lo` away; a confirmed spot whose whole box (or seen stand)
        // is closer cannot hold one.
        for (c in confirmedSet.toList()) {
            val far = pins[c]?.let { cellPointMax(ix, iy, iz, it[0], it[1], it[2]) } ?: maxDistance(ix, iy, iz, c)
            if (c !in fixedSpots && far < lo - EPS) {
                confirmedSet -= c
                emptiedSet += c
                regions -= c
                pins -= c
            }
        }
        val fitting = candidates().filter { fitsNow(ix, iy, iz, it, lo, hi) }
        val explained = fitting.isNotEmpty() && fitting.all { it in confirmedSet }
        if (fitting.size == 1) {
            val s = fitting[0]
            if (s !in confirmedSet) confirm(s)
            if (s !in pins) regions[s]?.cut(ix, iy, iz, lo, hi)
        }
        return DingFit(ix, iy, iz, pitchByte, lo, hi, fitting, explained).also { lastDing = it }
    }

    private fun confirm(s: CorpseSpot) {
        confirmedSet += s
        regions[s] = Region(s)
    }

    private fun fitsNow(ix: Int, iy: Int, iz: Int, s: CorpseSpot, lo: Double, hi: Double): Boolean {
        pins[s]?.let { return cellPointMin(ix, iy, iz, it[0], it[1], it[2]) <= hi + EPS && cellPointMax(ix, iy, iz, it[0], it[1], it[2]) >= lo - EPS }
        regions[s]?.let { return it.fits(ix, iy, iz, lo, hi) }
        return fits(ix, iy, iz, s, lo, hi)
    }

    /** The stand of confirmed [spot] was seen at ([x], [y], [z]): from now on that is its position. */
    fun pin(spot: CorpseSpot, x: Double, y: Double, z: Double) {
        if (spot in confirmedSet) pins[spot] = doubleArrayOf(x, y, z)
    }

    /** Where to draw a shown spot's stand: its seen position, else the ding estimate once tight; null = the listed block. */
    fun position(spot: CorpseSpot): DoubleArray? = pins[spot] ?: regions[spot]?.takeIf { it.spread() <= TIGHT }?.centroid()

    /** Best guess of the stand (seen, else the region's centroid, else the box center), for ranking candidates. */
    fun center(spot: CorpseSpot): DoubleArray =
        pins[spot] ?: regions[spot]?.centroid() ?: doubleArrayOf(spot.x.toDouble(), spot.y.toDouble(), spot.z.toDouble())

    /** Radius of the region the dings left for [spot] (0 once seen); null when not confirmed. */
    fun spread(spot: CorpseSpot): Double? = if (spot in pins) 0.0 else regions[spot]?.spread()

    /** Distance from (x, y, z) to the nearest stand position still possible for [spot] (tests and loot). */
    fun possibleDistance(spot: CorpseSpot, x: Double, y: Double, z: Double): Double =
        pins[spot]?.let { norm(x - it[0], y - it[1], z - it[2]) } ?: regions[spot]?.nearest(x, y, z) ?: pointDistance(x, y, z, spot)

    /**
     * `<TYPE> CORPSE LOOT!` with the player at (px, py, pz): the spot whose possible stand positions
     * come within [LOOT_RADIUS] (confirmed ones first, then the nearest) becomes looted. Null when no
     * candidate spot is that close.
     */
    fun loot(px: Double, py: Double, pz: Double): CorpseSpot? {
        val near = candidates().map { it to possibleDistance(it, px, py, pz) }.filter { it.second <= LOOT_RADIUS }
        val spot = near.minWithOrNull(compareBy<Pair<CorpseSpot, Double>>({ it.first !in confirmedSet }, { it.second }))?.first ?: return null
        lootedSet += spot
        confirmedSet -= spot
        regions -= spot
        pins -= spot
        return spot
    }

    /** Spots to draw: confirmed and not looted. */
    fun shown(): List<CorpseSpot> = confirmedSet.filter { it !in lootedSet }

    /**
     * The stand positions of one confirmed spot still consistent with every ding attributed to it alone:
     * a grid over its box ([STEP]), each point standing for the cube around it (slack [SLACK]) so the
     * point nearest the true stand is never cut. Work per ding is bounded by the grid ([N]^3 points,
     * less once the alive bounds shrink). A ding that would cut every point is ignored.
     */
    private class Region(spot: CorpseSpot) {
        private val xs = DoubleArray(N) { spot.x - SPREAD + it * STEP }
        private val ys = DoubleArray(N) { spot.y - SPREAD + it * STEP }
        private val zs = DoubleArray(N) { spot.z - SPREAD + it * STEP }
        private val alive = BooleanArray(N * N * N) { true }
        private val min = intArrayOf(0, 0, 0)
        private val max = intArrayOf(N - 1, N - 1, N - 1)
        private var centroid: DoubleArray? = null
        private var spread = -1.0

        private inline fun forAlive(action: (Int, Double, Double, Double) -> Boolean) {
            for (i in min[0]..max[0]) for (j in min[1]..max[1]) for (k in min[2]..max[2]) {
                val idx = (i * N + j) * N + k
                if (alive[idx] && !action(idx, xs[i], ys[j], zs[k])) return
            }
        }

        private fun passes(ix: Int, iy: Int, iz: Int, x: Double, y: Double, z: Double, lo: Double, hi: Double): Boolean =
            cellPointMin(ix, iy, iz, x, y, z) - SLACK <= hi + EPS && cellPointMax(ix, iy, iz, x, y, z) + SLACK >= lo - EPS

        fun fits(ix: Int, iy: Int, iz: Int, lo: Double, hi: Double): Boolean {
            var any = false
            forAlive { _, x, y, z ->
                any = passes(ix, iy, iz, x, y, z, lo, hi)
                !any
            }
            return any
        }

        fun cut(ix: Int, iy: Int, iz: Int, lo: Double, hi: Double) {
            val drop = ArrayList<Int>()
            var keep = 0
            forAlive { idx, x, y, z ->
                if (passes(ix, iy, iz, x, y, z, lo, hi)) keep++ else drop += idx
                true
            }
            if (keep == 0 || drop.isEmpty()) return
            for (idx in drop) alive[idx] = false
            val lo3 = intArrayOf(N, N, N)
            val hi3 = intArrayOf(-1, -1, -1)
            forAlive { idx, _, _, _ ->
                val i = idx / (N * N)
                val j = idx / N % N
                val k = idx % N
                lo3[0] = minOf(lo3[0], i)
                lo3[1] = minOf(lo3[1], j)
                lo3[2] = minOf(lo3[2], k)
                hi3[0] = maxOf(hi3[0], i)
                hi3[1] = maxOf(hi3[1], j)
                hi3[2] = maxOf(hi3[2], k)
                true
            }
            lo3.copyInto(min)
            hi3.copyInto(max)
            centroid = null
            spread = -1.0
        }

        fun centroid(): DoubleArray {
            centroid?.let { return it }
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            var n = 0
            forAlive { _, x, y, z ->
                sx += x
                sy += y
                sz += z
                n++
                true
            }
            return doubleArrayOf(sx / n, sy / n, sz / n).also { centroid = it }
        }

        /**
         * Farthest alive point from the centroid **horizontally**, plus the cube slack. Height stays
         * loose longer (the player dings from about one height) but the drawn box is two blocks tall.
         */
        fun spread(): Double {
            if (spread >= 0) return spread
            val c = centroid()
            var far = 0.0
            forAlive { _, x, _, z ->
                far = max(far, norm(x - c[0], 0.0, z - c[2]))
                true
            }
            spread = far + SLACK
            return spread
        }

        fun nearest(x: Double, y: Double, z: Double): Double {
            var best = Double.MAX_VALUE
            forAlive { _, px, py, pz ->
                best = minOf(best, norm(x - px, y - py, z - pz))
                true
            }
            return best
        }
    }

    companion object {
        const val RANGE = 20.0
        const val MIN_BYTE = 63
        const val MAX_BYTE = 126

        /** Slack on the pitch interval for float rounding; the geometry is exact. */
        const val EPS = 0.02

        /**
         * Half-size of a spot's box: the data's spot is the stand position floored (AMBE_1, JADE_1),
         * rounded or ceiled (CITR_2: stand -117.5, 3.7, -168.5 listed as -117, 4, -168), so the stand
         * is within one block of it on every axis.
         */
        const val SPREAD = 1.0

        /** Region grid: 17 points per axis over the 2-block box. */
        const val STEP = 0.125
        private const val N = 17

        /** Half the diagonal of a grid cube: the point nearest the true stand is at most this far from it. */
        val SLACK = STEP / 2 * sqrt(3.0)

        /** A region whose points all lie this close to their centroid is drawn there. */
        const val TIGHT = 0.5

        /** How far outside its spot's box a seen stand may stand (entity positions sit on block edges). */
        const val STAND_MARGIN = 0.5

        /** Player feet to the looted stand's possible positions at the loot line; reach is ~4.5 from the eyes. */
        const val LOOT_RADIUS = 6.0

        /** A ding farther than this from every recent client position is not ours (party member, lag spike). */
        const val OWN_RADIUS = 1.5

        /**
         * Whether to cancel this ding's playback: only one fully explained by already confirmed,
         * unlooted spots. A ding that could be a new corpse (fits an unconfirmed candidate), the one
         * that confirms, one that fits nothing and any ding while the locator is off stay audible.
         */
        fun mutes(fit: DingFit?, locatorOn: Boolean, muteOn: Boolean): Boolean = locatorOn && muteOn && fit != null && fit.explained

        /** `round(pitch * 63)` when the pitch is a talisman byte (n / 63 within float error), else null. */
        fun pitchByte(pitch: Float): Int? {
            val scaled = pitch * 63.0
            val n = scaled.roundToInt()
            if (abs(scaled - n) > 0.01 || n !in MIN_BYTE..MAX_BYTE) return null
            return n
        }

        /** What the server would send at distance [d]; null beyond the range. */
        fun byteFor(d: Double): Int? {
            if (d > RANGE) return null
            return floor(63 * (2 - (d / RANGE) * (d / RANGE))).toInt().coerceAtMost(MAX_BYTE)
        }

        /** The distance interval `(lo, hi]` a pitch byte stands for; null outside 63..126. */
        fun interval(n: Int): Pair<Double, Double>? {
            if (n !in MIN_BYTE..MAX_BYTE) return null
            val lo = RANGE * sqrt(max(0.0, 2 - (n + 1) / 63.0))
            val hi = RANGE * sqrt(max(0.0, 2 - n / 63.0))
            return lo to hi
        }

        fun fits(ix: Int, iy: Int, iz: Int, spot: CorpseSpot, lo: Double, hi: Double): Boolean =
            minDistance(ix, iy, iz, spot) <= hi + EPS && maxDistance(ix, iy, iz, spot) >= lo - EPS

        /**
         * The packet holds `(int) (pos * 8)`, truncated toward zero (vanilla's d2i; Hypixel's 135 dings
         * of the 2026-11-18 recording fit the stands only that way), so pos lies in [cellLo, cellHi].
         */
        fun cellLo(i: Int): Double = (if (i <= 0) i - 1 else i) / 8.0

        fun cellHi(i: Int): Double = (if (i >= 0) i + 1 else i) / 8.0

        /** Nearest distance between the packet's cell and the spot's box. */
        fun minDistance(ix: Int, iy: Int, iz: Int, spot: CorpseSpot): Double =
            norm(gap(cellLo(ix), cellHi(ix), spot.x), gap(cellLo(iy), cellHi(iy), spot.y), gap(cellLo(iz), cellHi(iz), spot.z))

        /** Farthest distance between the packet's cell and the spot's box. */
        fun maxDistance(ix: Int, iy: Int, iz: Int, spot: CorpseSpot): Double =
            norm(span(cellLo(ix), cellHi(ix), spot.x), span(cellLo(iy), cellHi(iy), spot.y), span(cellLo(iz), cellHi(iz), spot.z))

        /** Distance from a point to the spot's box. */
        fun pointDistance(px: Double, py: Double, pz: Double, spot: CorpseSpot): Double =
            norm(gap(px, px, spot.x), gap(py, py, spot.y), gap(pz, pz, spot.z))

        /** Distance from a seen stand at (x, y, z) to [spot]'s box (0 inside); null beyond [STAND_MARGIN]. */
        fun standOffset(spot: CorpseSpot, x: Double, y: Double, z: Double): Double? = pointDistance(x, y, z, spot).takeIf { it <= STAND_MARGIN }

        private fun cellPointMin(ix: Int, iy: Int, iz: Int, x: Double, y: Double, z: Double): Double =
            norm(gapTo(cellLo(ix), cellHi(ix), x), gapTo(cellLo(iy), cellHi(iy), y), gapTo(cellLo(iz), cellHi(iz), z))

        private fun cellPointMax(ix: Int, iy: Int, iz: Int, x: Double, y: Double, z: Double): Double =
            norm(max(abs(x - cellLo(ix)), abs(x - cellHi(ix))), max(abs(y - cellLo(iy)), abs(y - cellHi(iy))), max(abs(z - cellLo(iz)), abs(z - cellHi(iz))))

        private fun norm(dx: Double, dy: Double, dz: Double): Double = sqrt(dx * dx + dy * dy + dz * dz)

        private fun gapTo(a0: Double, a1: Double, v: Double): Double = max(0.0, max(v - a1, a0 - v))

        private fun gap(a0: Double, a1: Double, b: Int): Double = max(0.0, max(b - SPREAD - a1, a0 - (b + SPREAD)))

        private fun span(a0: Double, a1: Double, b: Int): Double = max(a1 - (b - SPREAD), (b + SPREAD) - a0)

        /** True when the ding at (sx, sy, sz) is within [OWN_RADIUS] of any of the client's recent positions. */
        fun isOwnDing(sx: Double, sy: Double, sz: Double, positions: Iterable<DoubleArray>): Boolean =
            positions.any { p ->
                val dx = p[0] - sx
                val dy = p[1] - sy
                val dz = p[2] - sz
                dx * dx + dy * dy + dz * dz <= OWN_RADIUS * OWN_RADIUS
            }
    }
}
