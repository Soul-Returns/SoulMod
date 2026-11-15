package com.soulreturns.features.mining.metaldetector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-sample tolerance: the exact client position (when it agrees with the harp packet) is held to
 * the bar's 0.1 rounding only, the harp position keeps the 1/8-block-quantization window.
 */
class MetalDetectorPreciseSampleTest {
    private val coarse = MetalDetectorSolver.COARSE_TOL
    private val precise = MetalDetectorSolver.PRECISE_TOL

    @Test
    fun preciseSampleExcludesASpotACoarseOneAllows() {
        // Chest (10,-1,0): corner (10,0,0), 9.92 from (0.08,0,0); shown 10.0 is 0.08 off.
        assertTrue(MetalDetectorSolver.fits(DetectorSample(0.08, 0.0, 0.0, 10.0, coarse), 10, -1, 0))
        assertFalse(MetalDetectorSolver.fits(DetectorSample(0.08, 0.0, 0.0, 10.0, precise), 10, -1, 0))
        assertTrue(MetalDetectorSolver.fits(DetectorSample(0.05, 0.0, 0.0, 10.0, precise), 10, -1, 0))
    }

    @Test
    fun preciseSampleSolvesWhereACoarseOneCannotChoose() {
        val a = ChestOffset(10, -1, 0) // 10.0 from the origin
        val b = ChestOffset(10, -1, 2) // 10.198: inside +-0.2, outside +-0.06
        val candidates = listOf(a, b)
        assertNull(MetalDetectorSolver.solve(0, 0, 0, listOf(DetectorSample(0.0, 0.0, 0.0, 10.0, coarse)), candidates).solved)
        assertEquals(a, MetalDetectorSolver.solve(0, 0, 0, listOf(DetectorSample(0.0, 0.0, 0.0, 10.0, precise)), candidates).solved)
    }

    @Test
    fun mixedPreciseAndCoarseSamplesSolve() {
        val a = ChestOffset(10, -1, 0)
        val b = ChestOffset(10, -1, 2)
        val samples =
            listOf(
                DetectorSample(0.0, 0.0, 0.0, 10.1, coarse), // both fit
                DetectorSample(10.0, 0.0, -5.0, 5.0, coarse), // moving: coarse, both ~5.0/5.4
                DetectorSample(0.0, 0.0, 0.0, 10.0, precise), // exact: only a
            )
        val sol = MetalDetectorSolver.solve(0, 0, 0, samples, listOf(a, b))
        assertEquals(a, sol.solved)
        assertEquals(3, sol.pairs)
    }

    @Test
    fun repeatsOfOneReadingCountOnce() {
        val p = DetectorSample(1.0, 2.0, 3.0, 10.0, precise)
        val c = p.copy(tol = coarse)
        assertEquals(listOf(p), MetalDetectorSolver.distinctSamples(listOf(p, p, p)))
        // Same position and value, different tolerance: one reading, the tighter bound.
        assertEquals(listOf(p), MetalDetectorSolver.distinctSamples(listOf(c, p, c)))
        assertEquals(2, MetalDetectorSolver.distinctSamples(listOf(p, p.copy(shown = 10.1))).size)
    }

    @Test
    fun harpSampleUsesTheExactPositionOnlyWhenItAgrees() {
        // Packet ints are position * 8.
        val agree = MetalDetectorSolver.harpSample(4761, 552, 3449, doubleArrayOf(595.143451953923, 69.0, 431.14154214272787))
        assertEquals(precise, agree.tol)
        assertEquals(595.143451953923, agree.x)
        // One axis a full block off (moving): harp position, coarse.
        val moved = MetalDetectorSolver.harpSample(4761, 552, 3449, doubleArrayOf(596.5, 69.0, 431.14))
        assertEquals(coarse, moved.tol)
        assertEquals(4761 / 8.0, moved.x)
        assertEquals(coarse, MetalDetectorSolver.harpSample(4761, 552, 3449, null).tol)
        // Quantization keeps both a floor and a round within one unit.
        assertEquals(precise, MetalDetectorSolver.harpSample(8, 0, 0, doubleArrayOf(1.12, 0.0, 0.0)).tol)
        assertEquals(coarse, MetalDetectorSolver.harpSample(8, 0, 0, doubleArrayOf(1.13, 0.0, 0.0)).tol)
    }
}
