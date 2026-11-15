package com.soulreturns.features.mining.metaldetector

import kotlin.math.abs
import kotlin.math.sqrt

/** A chest position relative to the Mines of Divan center. */
data class ChestOffset(val x: Int, val y: Int, val z: Int)

/**
 * One measurement: a position and the `TREASURE: X.Xm` value shown for it. [tol] is how far the true
 * distance may lie from [shown]: [MetalDetectorSolver.COARSE_TOL] for the harp sound's position (1/8-block
 * quantized server feet position), [MetalDetectorSolver.PRECISE_TOL] when the exact client position
 * is known to match it ([MetalDetectorSolver.harpSample]).
 */
data class DetectorSample(
    val x: Double,
    val y: Double,
    val z: Double,
    val shown: Double,
    val tol: Double = MetalDetectorSolver.COARSE_TOL,
)

data class CandidateScore(val offset: ChestOffset, val fits: Int)

/**
 * [solved] is the chest when the evidence is conclusive; otherwise [possible] holds up to three
 * best guesses (empty before the first sample). [ranked] is every candidate, best first.
 */
data class DetectorSolution(
    val ranked: List<CandidateScore>,
    val solved: ChestOffset?,
    val possible: List<ChestOffset>,
    /** Distinct samples scored (repeats of the same position + value count once). */
    val pairs: Int = 0,
)

/**
 * Pure (no Minecraft classes) so `./gradlew test` covers it. Mechanics: rule://metaldetector.
 *
 * The shown distance is measured to the integer corner of `(chestX, chestY + 1, chestZ)` and cut to
 * rounded to one decimal; with the 1/8-block sound quantization and movement, live data shows
 * `shown - true` in -0.16..+0.16 from the harp position, so a sample fits when the true distance
 * lies in `shown ± tol` (0.2 for the harp position, 0.06 for the exact client position).
 * Candidates are scored by how many **distinct** samples they fit (standing still repeats the same
 * pair dozens of times, which must not inflate the lead or the misfit ratio).
 */
object MetalDetectorSolver {
    /** Window for the harp position: -0.167..+0.196 measured over 327 readings (rounding + 1/8 quantization + movement). */
    const val COARSE_TOL = 0.2
    /** Window for the exact client position: within +-0.06 in 190 of 191 readings (only the bar's 0.1 rounding left; one outlier 0.158). */
    const val PRECISE_TOL = 0.06
    /** The client position counts as the server's when it is within this many 1/8-block units of the packet coordinate on every axis. */
    private const val AGREE_UNITS = 1.01
    private const val MIN_LEAD = 2
    /** Misfit allowance among distinct samples; the lead guard is what keeps the solver from guessing early. */
    private const val MAX_MISFIT_FRACTION = 0.37
    private const val MAX_POSSIBLE = 3

    /** Skyblocker's 42 chest spots in the Mines of Divan, relative to the center. */
    val OFFSETS: List<ChestOffset> =
        listOf(
            ChestOffset(-38, -22, 26), ChestOffset(38, -22, -26), ChestOffset(-40, -22, 18), ChestOffset(-41, -20, 22),
            ChestOffset(-5, -21, 16), ChestOffset(40, -22, -30), ChestOffset(-42, -20, -28), ChestOffset(-43, -22, -40),
            ChestOffset(42, -19, -41), ChestOffset(43, -21, -16), ChestOffset(-1, -22, -20), ChestOffset(6, -21, 28),
            ChestOffset(7, -21, 11), ChestOffset(7, -21, 22), ChestOffset(-12, -21, -44), ChestOffset(12, -22, 31),
            ChestOffset(12, -22, -22), ChestOffset(12, -21, 7), ChestOffset(12, -21, -43), ChestOffset(-14, -21, 43),
            ChestOffset(-14, -21, 22), ChestOffset(-17, -21, 20), ChestOffset(-20, -22, 0), ChestOffset(1, -21, 20),
            ChestOffset(19, -22, 29), ChestOffset(20, -22, 0), ChestOffset(20, -21, -26), ChestOffset(-23, -22, 40),
            ChestOffset(22, -21, -14), ChestOffset(-24, -22, 12), ChestOffset(23, -22, 26), ChestOffset(23, -22, -39),
            ChestOffset(24, -22, 27), ChestOffset(25, -22, 17), ChestOffset(29, -21, -44), ChestOffset(-31, -21, -12),
            ChestOffset(-31, -21, -40), ChestOffset(30, -21, -25), ChestOffset(-32, -21, -40), ChestOffset(-36, -20, 42),
            ChestOffset(-37, -21, -14), ChestOffset(-37, -21, -22),
        )

    /** Distance from ([x],[y],[z]) to the corner the server measures for a chest at ([cx],[cy],[cz]). */
    fun distance(x: Double, y: Double, z: Double, cx: Int, cy: Int, cz: Int): Double {
        val dx = x - cx
        val dy = y - (cy + 1)
        val dz = z - cz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun fits(sample: DetectorSample, cx: Int, cy: Int, cz: Int): Boolean {
        val d = distance(sample.x, sample.y, sample.z, cx, cy, cz)
        return d >= sample.shown - sample.tol && d <= sample.shown + sample.tol
    }

    /**
     * The sample (shown still 0) for a harp sound packet whose raw coordinates are [ix],[iy],[iz]
     * (position * 8) and the client's last sent position [player] (x, y, z; null when unknown). When
     * the two agree the player has not moved since the server computed the distance, so the exact
     * position is used with [PRECISE_TOL]; else (moving) the harp position with [COARSE_TOL].
     */
    fun harpSample(ix: Int, iy: Int, iz: Int, player: DoubleArray?): DetectorSample {
        if (player != null &&
            abs(player[0] * 8 - ix) < AGREE_UNITS &&
            abs(player[1] * 8 - iy) < AGREE_UNITS &&
            abs(player[2] * 8 - iz) < AGREE_UNITS
        ) {
            return DetectorSample(player[0], player[1], player[2], 0.0, PRECISE_TOL)
        }
        return DetectorSample(ix / 8.0, iy / 8.0, iz / 8.0, 0.0, COARSE_TOL)
    }

    /**
     * Distinct readings (same position + value; standing still repeats one reading dozens of times).
     * A repeat of the same position and value with a different tolerance keeps the tighter one.
     */
    fun distinctSamples(samples: List<DetectorSample>): List<DetectorSample> {
        val byReading = LinkedHashMap<List<Double>, DetectorSample>()
        for (s in samples) byReading.merge(listOf(s.x, s.y, s.z, s.shown), s) { a, b -> if (b.tol < a.tol) b else a }
        return byReading.values.toList()
    }

    fun solve(
        centerX: Int,
        centerY: Int,
        centerZ: Int,
        samples: List<DetectorSample>,
        candidates: List<ChestOffset> = OFFSETS,
    ): DetectorSolution {
        fun fitsOffset(s: DetectorSample, o: ChestOffset) = fits(s, centerX + o.x, centerY + o.y, centerZ + o.z)

        val ds = distinctSamples(samples)
        val ranked =
            candidates
                .map { o -> CandidateScore(o, ds.count { fitsOffset(it, o) }) }
                .sortedByDescending { it.fits }
        val firstFits = if (ds.isEmpty()) emptyList() else candidates.filter { fitsOffset(ds[0], it) }
        return decide(ranked, ds.size, onlyFitForFirst = firstFits.singleOrNull())
    }

    /** Misfits a solution may have among [n] samples. */
    fun allowedMisfits(n: Int): Int = (n * MAX_MISFIT_FRACTION).toInt()

    /** Chest y relative to the center: every spot seen so far lies in this band. */
    const val CHEST_Y_MIN = -26
    const val CHEST_Y_MAX = -14

    /** Shells of this many distinct opening samples seed the center-free candidates (a stale first bar is common). */
    private const val SEED_SAMPLES = 3
    const val NO_CENTER_Y_BELOW = 30
    const val NO_CENTER_Y_ABOVE = 10

    /**
     * Solving before the center is known (the keepers only carry their names while the player is
     * near). Candidates are the integer chest positions on the shells of the first [SEED_SAMPLES]
     * distinct samples (corner+1 model, same window, chest y within harpY -30..+10), scored against all
     * samples with the usual rules. Offsets in the result are **absolute block positions** (center 0).
     */
    fun freeSearchNoCenter(allSamples: List<DetectorSample>): DetectorSolution {
        val samples = distinctSamples(allSamples)
        val n = samples.size
        if (n == 0) return DetectorSolution(emptyList(), null, emptyList())
        val seeds = samples.take(SEED_SAMPLES)
        val candidates = LinkedHashSet<ChestOffset>()
        for (s in seeds) {
            val r = s.shown + s.tol + 1
            val lo = (s.shown - s.tol).coerceAtLeast(0.0).let { it * it }
            val hi = (s.shown + s.tol).let { it * it }
            val yMin = (s.y - NO_CENTER_Y_BELOW).toInt()
            val yMax = (s.y + NO_CENTER_Y_ABOVE).toInt()
            for (y in yMin..yMax) {
                val ey = s.y - (y + 1)
                for (x in (s.x - r).toInt()..(s.x + r).toInt()) {
                    val ex = s.x - x
                    for (z in (s.z - r).toInt()..(s.z + r).toInt()) {
                        val ez = s.z - z
                        val d2 = ex * ex + ey * ey + ez * ez
                        if (d2 >= lo && d2 <= hi) candidates += ChestOffset(x, y, z)
                    }
                }
            }
        }
        val ranked =
            candidates
                .map { c -> CandidateScore(c, samples.count { fits(it, c.x, c.y, c.z) }) }
                .sortedByDescending { it.fits }
        return decide(ranked, n, onlyFitForFirst = null)
    }

    /**
     * Solved = the top fits every sample and no other candidate does; or, with misfits, it fits all but
     * [allowedMisfits] samples and leads the runner-up by [MIN_LEAD] or is [onlyFitForFirst] (the single
     * candidate consistent with the first sample); or it is the only candidate that fits any sample at
     * all (the misfits are then stale bars that fit no spot, so they say nothing against it).
     */
    private fun decide(ranked: List<CandidateScore>, n: Int, onlyFitForFirst: ChestOffset?): DetectorSolution {
        val top = ranked.firstOrNull()
        if (n == 0 || top == null || top.fits == 0) return DetectorSolution(ranked, null, emptyList(), n)
        val runnerUp = ranked.getOrNull(1)?.fits ?: 0
        // Clean: the top fits every pair and every other spot misses one (live data has no misfits).
        val clean = top.fits == n && runnerUp < n
        val onlyOne = runnerUp == 0
        val fitsEnough = top.fits >= n - allowedMisfits(n)
        val solved = if (clean || onlyOne || (fitsEnough && (top.fits - runnerUp >= MIN_LEAD || onlyFitForFirst == top.offset))) top.offset else null
        val possible = if (solved != null) emptyList() else ranked.filter { it.fits > 0 }.take(MAX_POSSIBLE).map { it.offset }
        return DetectorSolution(ranked, solved, possible, n)
    }

    /**
     * Sticky solving: once a round is solved it never falls back to "possible". A new solve (by the
     * rules) replaces [previous]; an unsolved [current] keeps showing [previous].
     */
    fun keepSolved(previous: ChestOffset?, current: DetectorSolution): DetectorSolution =
        if (current.solved != null || previous == null) current else current.copy(solved = previous, possible = emptyList())
}
