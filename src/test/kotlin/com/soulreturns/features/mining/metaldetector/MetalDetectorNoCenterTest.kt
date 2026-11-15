package com.soulreturns.features.mining.metaldetector

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Solving without the center (keeper names only exist while the player is near). Results are
 * absolute positions.
 */
class MetalDetectorNoCenterTest {
    private fun r(x: Double, y: Double, z: Double, shown: Double, count: Int) = List(count) { DetectorSample(x, y, z, shown) }

    /**
     * Live round from capture `2026-11-07_16-24-58.ndjson`, chest dug up at (690, 68, 371). Its 9
     * distinct pairs fit (691, 68, 371) just as well under the `± 0.2` window, so without the center
     * it stays at those two (with the center the list solves it at the first pair).
     */
    private val liveChest = ChestOffset(690, 68, 371)
    private val live =
        listOf(
            r(690.75, 69.0, 361.25, 9.7, 33), r(690.375, 70.875, 366.125, 5.2, 1), r(691.375, 92.25, 343.0, 36.5, 1),
            r(692.0, 96.125, 348.75, 35.1, 1), r(692.75, 88.0, 353.375, 26.0, 1), r(692.75, 70.0, 357.0, 14.3, 1),
            r(692.125, 69.0, 362.125, 9.1, 1), r(692.125, 69.0, 362.375, 8.8, 68), r(692.0, 69.0, 363.625, 7.6, 1),
        ).flatten()

    @Test
    fun liveRoundNarrowsToTwoNeighbours() {
        val sol = MetalDetectorSolver.freeSearchNoCenter(live)
        assertNull(sol.solved)
        assertEquals(setOf(liveChest, ChestOffset(691, 68, 371)), sol.possible.take(2).toSet())
    }

    @Test
    fun liveRoundNeverSolvesWrong() {
        for (n in 1..live.size) {
            val solved = MetalDetectorSolver.freeSearchNoCenter(live.take(n)).solved
            assertTrue(solved == null || solved == liveChest, "after $n samples solved to $solved")
        }
    }

    /** A walk with varied heights around a chest at (640, 70, 381), pinged with the server's formula. */
    private val walkChest = ChestOffset(640, 70, 381)
    private val walk =
        listOf(
            Triple(600.31, 70.0, 400.77), Triple(607.12, 74.88, 395.43), Triple(615.86, 69.0, 390.21), Triple(622.47, 71.5, 384.09),
            Triple(631.73, 75.75, 378.62), Triple(639.28, 68.0, 372.55), Triple(646.94, 69.0, 377.18), Triple(652.05, 72.25, 386.83),
            Triple(648.66, 76.0, 395.37), Triple(641.19, 70.0, 401.91), Triple(633.58, 69.0, 407.44), Triple(626.02, 73.0, 412.66),
        ).map { (x, y, z) ->
            val dx = x - walkChest.x
            val dy = y - (walkChest.y + 1)
            val dz = z - walkChest.z
            fun q(v: Double) = (v * 8).roundToInt() / 8.0
            DetectorSample(q(x), q(y), q(z), (sqrt(dx * dx + dy * dy + dz * dz) * 10).roundToInt() / 10.0)
        }

    @Test
    fun walkSolvesWithoutCenter() {
        assertEquals(walkChest, MetalDetectorSolver.freeSearchNoCenter(walk).solved)
        for (n in 1..walk.size) {
            val solved = MetalDetectorSolver.freeSearchNoCenter(walk.take(n)).solved
            assertTrue(solved == null || solved == walkChest, "after $n samples solved to $solved")
        }
    }
}
