package com.soulreturns.features.mining.metaldetector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replay of capture 2026-11-07_15-33-19 (center (630, 90, 416), 8 dug chests, 342 readings): per reading
 * the harp packet ints, the client's last sent position (outbound `move_player*` with a position) and
 * the TREASURE value, paired the way [MetalDetector] pairs them (`capture-15-33-19.txt`).
 */
class MetalDetectorPreciseReplayTest {
    private class Reading(val round: Int, val ix: Int, val iy: Int, val iz: Int, val player: DoubleArray?, val shown: Double)

    private val truth =
        listOf(
            ChestOffset(-31, -21, -12), ChestOffset(-14, -21, 22), ChestOffset(25, -22, 17), ChestOffset(-20, -22, 0),
            ChestOffset(30, -21, -25), ChestOffset(24, -22, 27), ChestOffset(43, -21, -16), ChestOffset(-37, -21, -22),
        )

    private val readings: List<Reading> =
        javaClass.getResource("/metaldetector/capture-15-33-19.txt")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map {
                val f = it.split(' ')
                val player = if (f[4] == "-") null else doubleArrayOf(f[4].toDouble(), f[5].toDouble(), f[6].toDouble())
                Reading(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toInt(), player, f[7].toDouble())
            }

    private fun samples(round: Int, precise: Boolean) =
        readings.filter { it.round == round }.map {
            val s = MetalDetectorSolver.harpSample(it.ix, it.iy, it.iz, if (precise) it.player else null)
            s.copy(shown = it.shown)
        }

    private fun solve(s: List<DetectorSample>) = MetalDetectorSolver.solve(630, 90, 416, s)

    /** Readings until solved (1-based), asserting no prefix solves to a wrong chest. */
    private fun needed(round: Int, precise: Boolean): Int {
        val s = samples(round, precise)
        var first = 0
        for (n in 1..s.size) {
            val solved = solve(s.take(n)).solved
            assertTrue(solved == null || solved == truth[round], "round $round after $n (precise=$precise) solved to $solved")
            if (solved != null && first == 0) first = n
        }
        assertEquals(truth[round], solve(s).solved, "round $round final (precise=$precise)")
        return first
    }

    @Test
    fun preciseSamplesSolveNoLaterAndNeverWrong() {
        val before = truth.indices.map { needed(it, precise = false) }
        val after = truth.indices.map { needed(it, precise = true) }
        assertEquals(listOf(7, 1, 1, 1, 1, 1, 1, 3), before)
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1, 3), after)
    }

    @Test
    fun mostReadingsAreExact() {
        val n = truth.indices.sumOf { r -> samples(r, true).count { it.tol == MetalDetectorSolver.PRECISE_TOL } }
        assertTrue(n > 150, "only $n exact samples")
    }
}
