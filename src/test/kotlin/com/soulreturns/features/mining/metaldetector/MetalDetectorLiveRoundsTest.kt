package com.soulreturns.features.mining.metaldetector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every finished round of a 47-round Hypixel capture (2026-11-07_16-24-58, center (666, 90, 344)),
 * paired the way [MetalDetector] pairs them; the chest is the chest `block_update` the round ended
 * on. `r(x, y, z, shown, n)` is a pair repeated n times in a row (standing still). Displayed
 * solutions go through [MetalDetectorSolver.keepSolved] like the runtime does.
 */
class MetalDetectorLiveRoundsTest {
    /** [needed] = pairs (incl. repeats) until solved; 0 = the round ended before it could solve. */
    private class LiveRound(val index: Int, val chest: ChestOffset, val needed: Int, runs: List<List<DetectorSample>>) {
        val samples = runs.flatten()
    }

    private fun r(x: Double, y: Double, z: Double, shown: Double, n: Int) = List(n) { DetectorSample(x, y, z, shown) }

    private fun solve(samples: List<DetectorSample>) = MetalDetectorSolver.solve(666, 90, 344, samples)

    private val rounds =
        listOf(
            LiveRound(0, ChestOffset(24, -22, 27), 1, listOf(r(690.75, 69.0, 361.25, 9.7, 33), r(690.375, 70.875, 366.125, 5.2, 1), r(691.375, 92.25, 343.0, 36.5, 1), r(692.0, 96.125, 348.75, 35.1, 1), r(692.75, 88.0, 353.375, 26.0, 1), r(692.75, 70.0, 357.0, 14.3, 1), r(692.125, 69.0, 362.125, 9.1, 1), r(692.125, 69.0, 362.375, 8.8, 68), r(692.0, 69.0, 363.625, 7.6, 1))),
            LiveRound(1, ChestOffset(-14, -21, 43), 1, listOf(r(691.125, 69.0, 369.0, 43.1, 2), r(682.0, 69.0, 372.875, 33.2, 1), r(662.75, 70.0, 365.25, 24.2, 1), r(653.125, 70.0, 377.625, 9.4, 1), r(655.25, 72.0, 388.5, 4.2, 1), r(654.75, 70.0, 389.625, 3.9, 1))),
            LiveRound(2, ChestOffset(-36, -20, 42), 1, listOf(r(653.5, 70.0, 389.25, 23.8, 1), r(645.5, 69.75, 387.625, 15.7, 1), r(629.5, 71.0, 386.5, 0.7, 1), r(630.625, 71.0, 384.5, 1.7, 1))),
            LiveRound(3, ChestOffset(12, -21, -43), 1, listOf(r(630.75, 71.0, 384.375, 95.9, 1), r(632.0, 71.0, 386.25, 96.9, 1))),
            LiveRound(4, ChestOffset(-38, -22, 26), 1, listOf(r(678.5, 70.0, 300.25, 86.1, 1), r(679.25, 70.0, 306.375, 81.7, 1))),
            LiveRound(5, ChestOffset(-23, -22, 40), 5, listOf(r(629.75, 69.0, 369.125, 19.8, 3), r(628.375, 69.0, 368.625, 21.1, 1), r(629.875, 69.0, 367.75, 20.8, 1), r(636.75, 69.0, 359.625, 25.1, 1), r(636.875, 69.0, 359.625, 25.1, 1), r(639.5, 70.75, 378.625, 6.6, 1))),
            LiveRound(6, ChestOffset(22, -21, -14), 1, listOf(r(642.25, 69.0, 383.0, 70.1, 1), r(642.375, 69.0, 383.125, 70.1, 1), r(640.625, 69.0, 381.625, 70.1, 1), r(637.875, 69.0, 379.0, 70.1, 1), r(637.25, 69.0, 359.25, 58.6, 1))),
            LiveRound(7, ChestOffset(-37, -21, -22), 2, listOf(r(686.375, 70.0, 331.125, 58.2, 1), r(689.625, 70.0, 333.0, 61.6, 1), r(686.5, 69.0, 342.5, 61.2, 1), r(687.125, 75.0, 333.0, 59.5, 1), r(686.875, 70.0, 331.25, 58.7, 178), r(684.25, 70.0, 328.25, 55.7, 1), r(625.5, 77.125, 318.625, 8.7, 1))),
            LiveRound(8, ChestOffset(-36, -20, 42), 2, listOf(r(627.375, 71.0, 320.75, 65.3, 1), r(630.375, 70.0, 326.875, 59.1, 1), r(639.75, 70.0, 332.125, 54.8, 1), r(631.25, 74.75, 392.125, 7.4, 1), r(632.5, 71.0, 392.25, 6.8, 1))),
            LiveRound(9, ChestOffset(-14, -21, 22), 1, listOf(r(630.625, 71.0, 385.875, 29.2, 1), r(630.5, 71.0, 384.625, 28.4, 75), r(630.875, 71.0, 384.375, 27.9, 1), r(636.875, 69.875, 379.5, 20.3, 1), r(636.875, 69.0, 372.25, 16.4, 1), r(633.375, 69.0, 368.5, 18.8, 1), r(630.375, 69.0, 365.375, 21.5, 1), r(627.125, 69.0, 362.375, 25.1, 1), r(628.25, 69.0, 364.625, 23.8, 1), r(631.875, 69.0, 362.75, 20.3, 2), r(636.875, 69.0, 357.875, 17.1, 1), r(645.0, 69.0, 351.625, 15.9, 1), r(650.5, 72.0, 362.5, 4.3, 1))),
            LiveRound(10, ChestOffset(-38, -22, 26), 1, listOf(r(651.125, 70.0, 365.125, 23.7, 1), r(654.25, 70.0, 365.0, 26.8, 1), r(660.25, 70.0, 363.625, 33.0, 1), r(668.0, 70.0, 362.125, 40.8, 1), r(668.75, 73.5, 361.625, 41.9, 1), r(667.5, 70.0, 360.75, 40.7, 1), r(659.0, 75.5, 362.625, 32.5, 1), r(657.25, 74.125, 358.25, 32.0, 1), r(654.375, 75.0, 353.75, 31.6, 1), r(644.0, 71.625, 356.0, 21.4, 1), r(636.125, 69.0, 361.5, 11.8, 1))),
            LiveRound(11, ChestOffset(7, -21, 11), 1, listOf(r(630.0, 69.0, 368.125, 44.9, 1), r(630.0, 69.0, 370.25, 45.6, 1))),
            LiveRound(12, ChestOffset(-14, -21, 43), 1, listOf(r(675.5, 70.0, 357.5, 37.7, 1), r(676.875, 70.0, 351.25, 43.6, 1))),
            LiveRound(13, ChestOffset(-14, -21, 22), 1, listOf(r(651.125, 70.0, 388.625, 22.7, 2))),
            LiveRound(14, ChestOffset(7, -21, 11), 1, listOf(r(651.25, 70.0, 365.875, 24.3, 1), r(654.25, 71.0, 366.375, 21.9, 1))),
            LiveRound(15, ChestOffset(20, -21, -26), 1, listOf(r(673.0, 70.0, 356.125, 40.3, 1), r(679.5, 70.0, 350.375, 33.0, 1), r(685.25, 76.375, 315.625, 6.9, 1))),
            LiveRound(16, ChestOffset(-31, -21, -12), 2, listOf(r(685.625, 69.0, 316.125, 53.1, 1), r(686.375, 73.25, 317.375, 53.5, 1), r(677.875, 69.0, 323.375, 43.8, 1), r(667.625, 69.0, 325.625, 33.3, 1), r(636.75, 75.875, 330.375, 6.4, 1))),
            LiveRound(17, ChestOffset(7, -21, 22), 1, listOf(r(635.875, 70.0, 331.375, 50.7, 1), r(635.625, 70.0, 333.5, 49.5, 1), r(664.5, 72.0, 362.5, 9.4, 1), r(665.75, 67.125, 363.0, 8.3, 1))),
            LiveRound(18, ChestOffset(12, -21, -43), 1, listOf(r(672.125, 72.5, 365.625, 65.0, 1), r(676.625, 70.75, 365.125, 64.3, 1), r(681.875, 71.5, 321.5, 21.0, 1), r(683.125, 69.875, 317.125, 17.0, 1), r(680.125, 74.875, 302.625, 5.7, 1))),
            LiveRound(19, ChestOffset(-12, -21, -44), 1, listOf(r(679.5, 70.0, 302.5, 25.7, 1), r(679.625, 70.0, 303.0, 25.8, 1), r(644.75, 74.25, 300.5, 10.1, 1), r(651.0, 71.125, 302.125, 3.9, 1))),
            LiveRound(20, ChestOffset(25, -22, 17), 1, listOf(r(652.375, 70.0, 301.0, 71.2, 1), r(648.25, 70.0, 306.375, 69.3, 1))),
            LiveRound(21, ChestOffset(12, -22, 31), 1, listOf(r(689.625, 69.0, 361.125, 18.1, 1), r(692.375, 69.0, 362.875, 18.9, 1), r(695.0, 69.0, 366.25, 19.1, 1), r(695.0, 69.0, 366.375, 19.1, 87), r(693.25, 69.0, 367.75, 16.9, 1), r(684.625, 75.0, 375.25, 9.1, 1))),
            LiveRound(22, ChestOffset(24, -22, 27), 1, listOf(r(680.375, 69.0, 376.25, 10.9, 1), r(680.75, 69.0, 375.25, 10.2, 1), r(687.75, 69.0, 372.125, 2.5, 1))),
            LiveRound(23, ChestOffset(40, -22, -30), 1, listOf(r(689.625, 69.0, 371.5, 59.9, 1), r(691.375, 69.0, 368.25, 56.2, 3))),
            LiveRound(24, ChestOffset(-37, -21, -22), 1, listOf(r(706.375, 69.0, 315.875, 77.6, 1), r(626.375, 79.0, 318.5, 10.0, 1), r(626.75, 74.125, 319.375, 5.3, 1))),
            LiveRound(25, ChestOffset(-12, -21, -44), 1, listOf(r(627.5, 71.0, 320.125, 33.3, 1), r(628.375, 71.0, 323.0, 34.5, 1), r(654.125, 76.375, 304.0, 7.6, 1))),
            LiveRound(26, ChestOffset(7, -21, 22), 1, listOf(r(652.25, 70.0, 299.25, 69.8, 1), r(677.5, 71.75, 366.375, 4.9, 1))),
            LiveRound(27, ChestOffset(-1, -22, -20), 1, listOf(r(674.625, 68.875, 367.75, 44.9, 1))),
            LiveRound(28, ChestOffset(-37, -21, -14), 2, listOf(r(668.875, 69.0, 323.875, 40.5, 1), r(657.875, 73.25, 324.875, 29.5, 1), r(657.375, 73.0, 324.875, 29.0, 1), r(663.5, 69.0, 324.5, 35.0, 1), r(634.375, 75.75, 331.25, 8.1, 1))),
            LiveRound(29, ChestOffset(24, -22, 27), 1, listOf(r(629.75, 70.0, 329.125, 73.3, 1), r(634.125, 70.0, 331.75, 68.3, 1), r(634.125, 70.0, 331.75, 68.2, 2), r(643.625, 70.0, 332.625, 60.2, 1))),
            LiveRound(30, ChestOffset(12, -22, -22), 1, listOf(r(688.25, 69.125, 371.75, 50.8, 1), r(692.625, 73.25, 371.875, 52.2, 1))),
            LiveRound(31, ChestOffset(12, -22, 31), 1, listOf(r(679.75, 73.625, 321.875, 53.3, 1), r(680.0, 70.875, 323.75, 51.2, 1))),
            LiveRound(32, ChestOffset(19, -22, 29), 1, listOf(r(680.25, 69.0, 374.625, 4.9, 1), r(681.75, 71.375, 373.125, 4.0, 1), r(682.875, 73.75, 371.75, 5.3, 1))),
            LiveRound(33, ChestOffset(-43, -22, -40), 1, listOf(r(684.0, 70.875, 372.5, 91.9, 1), r(687.5, 70.625, 372.25, 94.0, 1), r(688.375, 69.0, 372.125, 94.4, 3), r(694.625, 69.0, 363.125, 92.9, 1), r(687.125, 69.0, 354.625, 81.8, 1), r(683.375, 69.0, 340.125, 70.5, 1))),
            LiveRound(34, ChestOffset(-38, -22, 26), 1, listOf(r(624.5, 69.125, 303.0, 67.1, 1), r(626.75, 73.125, 305.5, 64.6, 1))),
            LiveRound(35, ChestOffset(-1, -22, -20), 1, listOf(r(626.125, 71.625, 371.125, 61.2, 1), r(627.625, 70.75, 368.375, 58.1, 1), r(668.375, 69.0, 323.5, 3.5, 1))),
            LiveRound(36, ChestOffset(7, -21, 11), 1, listOf(r(666.375, 71.75, 326.0, 29.8, 1), r(667.125, 73.625, 326.5, 29.2, 1), r(674.125, 69.0, 322.125, 32.9, 1))),
            LiveRound(37, ChestOffset(38, -22, -26), 1, listOf(r(674.75, 70.0, 354.25, 46.6, 1), r(674.625, 70.0, 357.25, 49.1, 1), r(701.25, 70.0, 324.125, 6.9, 1), r(703.625, 69.0, 319.25, 1.3, 1), r(706.375, 69.0, 320.75, 3.8, 1))),
            LiveRound(38, ChestOffset(19, -22, 29), 2, listOf(r(706.875, 69.0, 320.0, 57.3, 1), r(688.5, 70.75, 376.5, 5.3, 1))),
            LiveRound(39, ChestOffset(1, -21, 20), 1, listOf(r(685.5, 74.5, 371.75, 20.6, 1), r(684.375, 73.25, 371.125, 19.2, 1), r(668.5, 70.0, 360.5, 3.8, 1))),
            LiveRound(40, ChestOffset(24, -22, 27), 1, listOf(r(667.125, 70.0, 359.25, 25.6, 1), r(667.5, 70.0, 359.75, 25.1, 1), r(667.5, 70.0, 359.875, 25.0, 1), r(666.75, 70.0, 360.5, 25.4, 2), r(669.0, 71.0, 361.125, 23.2, 1), r(683.875, 73.375, 371.375, 7.5, 1), r(684.75, 69.0, 371.25, 5.2, 1))),
            LiveRound(41, ChestOffset(-43, -22, -40), 1, listOf(r(688.125, 69.0, 372.75, 94.8, 1), r(690.625, 69.0, 367.625, 93.0, 1), r(691.125, 69.0, 359.375, 87.9, 1), r(687.5, 69.0, 345.125, 76.6, 1), r(650.875, 74.375, 321.375, 33.3, 1), r(644.375, 70.0, 316.25, 24.7, 1), r(637.625, 71.875, 315.0, 18.6, 1))),
            LiveRound(42, ChestOffset(23, -22, -39), 1, listOf(r(622.5, 73.125, 305.0, 66.5, 1), r(622.75, 71.375, 306.375, 66.2, 1))),
            LiveRound(43, ChestOffset(12, -21, 7), 1, listOf(r(688.25, 72.375, 307.0, 45.2, 1), r(689.0, 72.0, 308.5, 43.9, 1), r(687.75, 75.625, 306.75, 45.6, 1), r(682.875, 69.0, 311.875, 39.4, 1), r(682.0, 70.0, 318.125, 33.0, 1), r(683.625, 70.0, 346.625, 7.1, 1), r(681.125, 75.125, 350.125, 6.1, 1), r(680.25, 70.625, 351.0, 2.4, 1))),
            LiveRound(44, ChestOffset(7, -21, 11), 1, listOf(r(680.125, 70.0, 351.125, 8.2, 1), r(676.25, 70.0, 351.75, 4.6, 1), r(674.25, 70.0, 358.875, 4.1, 1), r(674.25, 70.0, 358.875, 4.2, 1), r(674.25, 70.0, 357.25, 2.7, 1), r(674.25, 70.0, 356.875, 2.3, 2))),
            LiveRound(45, ChestOffset(-31, -21, -12), 1, listOf(r(674.0, 70.0, 354.375, 45.1, 1), r(674.0, 70.0, 358.5, 47.2, 1), r(674.75, 70.0, 357.625, 47.4, 1), r(634.375, 82.125, 328.75, 12.6, 1), r(634.875, 74.375, 329.625, 5.0, 1))),
            LiveRound(46, ChestOffset(-24, -22, 12), 1, listOf(r(635.5, 72.0, 330.25, 26.7, 1), r(634.25, 70.0, 331.25, 25.8, 1), r(634.125, 70.0, 331.375, 25.8, 3), r(644.5, 76.75, 356.5, 8.2, 1))),
        )

    @Test
    fun solvesEveryRoundAndNeverWrong() {
        for (round in rounds) {
            var kept: ChestOffset? = null
            var first = 0
            for (n in 1..round.samples.size) {
                val raw = solve(round.samples.take(n))
                assertTrue(raw.solved == null || raw.solved == round.chest, "round ${round.index} after $n pairs solved to ${raw.solved}")
                val shown = MetalDetectorSolver.keepSolved(kept, raw)
                if (kept != null) assertNotNull(shown.solved, "round ${round.index} fell back to possible after $n pairs")
                if (shown.solved != null && first == 0) first = n
                kept = shown.solved
            }
            assertEquals(round.needed, first, "round ${round.index} pairs to solve")
            if (round.needed > 0) assertEquals(round.chest, kept, "round ${round.index}")
        }
    }

    @Test
    fun repeatsDoNotCount() {
        val round = rounds.first { it.samples.size > 100 }
        assertEquals(round.samples.distinct().size, solve(round.samples).pairs)
    }

    @Test
    fun keepSolvedHoldsUntilAnotherSolve() {
        val a = ChestOffset(1, -21, 2)
        val b = ChestOffset(3, -22, 4)
        assertEquals(a, MetalDetectorSolver.keepSolved(a, DetectorSolution(emptyList(), null, listOf(b))).solved)
        assertEquals(b, MetalDetectorSolver.keepSolved(a, DetectorSolution(emptyList(), b, emptyList())).solved)
        assertNull(MetalDetectorSolver.keepSolved(null, DetectorSolution(emptyList(), null, listOf(b))).solved)
    }
}
