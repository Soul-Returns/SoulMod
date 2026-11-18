package com.soulreturns.features.mining.mineshaft

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A corpse spawn spot: the block holding the corpse armor stand (stand position floored). */
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
 * ([cellLo]/[cellHi]), the stand anywhere in its spot block; the min/max distance between those two
 * boxes must meet the pitch interval (±[EPS]). A spot is **confirmed** when a ding fits exactly one
 * candidate (spots not looted and not proven empty); confirmed stays until looted or the stay ends.
 */
class CorpseLocatorSolver(val spots: List<CorpseSpot>, fixed: Boolean = false) {
    private val confirmedSet = LinkedHashSet<CorpseSpot>()
    private val lootedSet = LinkedHashSet<CorpseSpot>()
    private val emptiedSet = LinkedHashSet<CorpseSpot>()
    private val fixedSpots: Set<CorpseSpot> = if (fixed) spots.toSet() else emptySet()

    init {
        confirmedSet += fixedSpots
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
        // Every unlooted corpse is at least `lo` away; a confirmed spot entirely closer cannot hold one.
        for (c in confirmedSet.toList()) {
            if (c !in fixedSpots && maxDistance(ix, iy, iz, c) < lo - EPS) {
                confirmedSet -= c
                emptiedSet += c
            }
        }
        val fitting = candidates().filter { fits(ix, iy, iz, it, lo, hi) }
        val explained = fitting.isNotEmpty() && fitting.all { it in confirmedSet }
        if (fitting.size == 1) confirmedSet += fitting[0]
        return DingFit(ix, iy, iz, pitchByte, lo, hi, fitting, explained).also { lastDing = it }
    }

    /**
     * `<TYPE> CORPSE LOOT!` with the player at (px, py, pz): the spot within [LOOT_RADIUS] (confirmed
     * ones first, then the nearest) becomes looted. Null when no candidate spot is that close.
     */
    fun loot(px: Double, py: Double, pz: Double): CorpseSpot? {
        val near = candidates().map { it to pointDistance(px, py, pz, it) }.filter { it.second <= LOOT_RADIUS }
        val spot = near.minWithOrNull(compareBy<Pair<CorpseSpot, Double>>({ it.first !in confirmedSet }, { it.second }))?.first ?: return null
        lootedSet += spot
        confirmedSet -= spot
        return spot
    }

    /** Spots to draw: confirmed and not looted. */
    fun shown(): List<CorpseSpot> = confirmedSet.filter { it !in lootedSet }

    companion object {
        const val RANGE = 20.0
        const val MIN_BYTE = 63
        const val MAX_BYTE = 126

        /** Slack on the pitch interval for float rounding; the geometry is exact. */
        const val EPS = 0.02

        /** Player feet to the looted spot's block at the loot line; reach is ~4.5 from the eyes. */
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

        /** Nearest distance between the packet's cell and the spot's block. */
        fun minDistance(ix: Int, iy: Int, iz: Int, spot: CorpseSpot): Double =
            norm(gap(cellLo(ix), cellHi(ix), spot.x), gap(cellLo(iy), cellHi(iy), spot.y), gap(cellLo(iz), cellHi(iz), spot.z))

        /** Farthest distance between the packet's cell and the spot's block. */
        fun maxDistance(ix: Int, iy: Int, iz: Int, spot: CorpseSpot): Double =
            norm(span(cellLo(ix), cellHi(ix), spot.x), span(cellLo(iy), cellHi(iy), spot.y), span(cellLo(iz), cellHi(iz), spot.z))

        /** Distance from a point to the spot's block. */
        fun pointDistance(px: Double, py: Double, pz: Double, spot: CorpseSpot): Double =
            norm(gap(px, px, spot.x), gap(py, py, spot.y), gap(pz, pz, spot.z))

        private fun norm(dx: Double, dy: Double, dz: Double): Double = sqrt(dx * dx + dy * dy + dz * dz)

        private fun gap(a0: Double, a1: Double, b: Int): Double = max(0.0, max(b - a1, a0 - (b + 1)))

        private fun span(a0: Double, a1: Double, b: Int): Double = max(a1 - b, (b + 1) - a0)

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
