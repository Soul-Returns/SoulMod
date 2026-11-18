package com.soulreturns.features.mining.mineshaft

import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorpseLocatorSolverTest {
    private val data = CorpseSpotData.parse(javaClass.getResource("/assets/soul/mineshaft/corpse_spots.json")!!.readText())

    private fun dist(a: DoubleArray, b: DoubleArray): Double = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    /** What the server sends for a player at [player] and the nearest unlooted stand at [stand]: packet ints (truncated) and pitch byte. */
    private fun ding(s: CorpseLocatorSolver, player: DoubleArray, stand: DoubleArray): DingFit? {
        val n = CorpseLocatorSolver.byteFor(dist(player, stand)) ?: return null
        return s.ding((player[0] * 8).toInt(), (player[1] * 8).toInt(), (player[2] * 8).toInt(), n)
    }

    private fun stand(spot: CorpseSpot, fx: Double = 0.5, fy: Double = 0.9, fz: Double = 0.5) = doubleArrayOf(spot.x + fx, spot.y + fy, spot.z + fz)

    @Test
    fun pitchByteAndIntervalRoundTripAtTheEdges() {
        for (n in CorpseLocatorSolver.MIN_BYTE..CorpseLocatorSolver.MAX_BYTE) {
            val (lo, hi) = assertNotNull(CorpseLocatorSolver.interval(n))
            assertTrue(lo <= hi)
            if (n < 126) assertEquals(n, CorpseLocatorSolver.byteFor(hi - 1e-9), "byte $n top")
            if (n < 125) assertEquals(n, CorpseLocatorSolver.byteFor(lo + 1e-9), "byte $n bottom")
            if (n < 125) assertEquals(n + 1, CorpseLocatorSolver.byteFor(lo - 1e-9), "below byte $n")
            assertEquals(n, CorpseLocatorSolver.pitchByte(n / 63f), "pitch of byte $n")
        }
        assertEquals(126, CorpseLocatorSolver.byteFor(0.0))
        assertEquals(125, CorpseLocatorSolver.byteFor(2.5))
        assertEquals(63, CorpseLocatorSolver.byteFor(20.0))
        assertNull(CorpseLocatorSolver.byteFor(20.01))
        assertNull(CorpseLocatorSolver.interval(62))
        assertNull(CorpseLocatorSolver.interval(127))
        assertNull(CorpseLocatorSolver.pitchByte(60 / 63f), "below the talisman range")
        assertNull(CorpseLocatorSolver.pitchByte(1.4f), "not n / 63")
        assertEquals(90, CorpseLocatorSolver.pitchByte(1.4285715f), "Hypixel's packet value")
        val (lo, hi) = CorpseLocatorSolver.interval(90)!!
        assertEquals(14.91, lo, 0.01)
        assertEquals(15.12, hi, 0.01)
    }

    @Test
    fun packetCellsFollowTruncationTowardZero() {
        assertEquals(-192.75, CorpseLocatorSolver.cellLo(-1541))
        assertEquals(-192.625, CorpseLocatorSolver.cellHi(-1541))
        assertEquals(25.0, CorpseLocatorSolver.cellLo(200))
        assertEquals(25.125, CorpseLocatorSolver.cellHi(200))
        assertEquals(-0.125, CorpseLocatorSolver.cellLo(0))
        assertEquals(0.125, CorpseLocatorSolver.cellHi(0))
        for (v in listOf(-192.55, -0.07, 0.07, 25.06, 25.0)) {
            val i = (v * 8).toInt()
            assertTrue(v in CorpseLocatorSolver.cellLo(i)..CorpseLocatorSolver.cellHi(i), "$v in its cell")
        }
    }

    @Test
    fun twoFittingSpotsWaitForTheNextDing() {
        val a = CorpseSpot(-150, 10, -170)
        val b = CorpseSpot(-150, 10, -160)
        val s = CorpseLocatorSolver(listOf(a, b))
        val corpse = stand(a)
        // Halfway between the two spots both fit.
        val first = assertNotNull(ding(s, doubleArrayOf(-149.5, 10.9, -165.0), corpse))
        assertEquals(setOf(a, b), first.fitting.toSet())
        assertTrue(s.shown().isEmpty())
        val second = assertNotNull(ding(s, doubleArrayOf(-149.5, 10.0, -176.0), corpse))
        assertEquals(listOf(a), second.fitting)
        assertEquals(listOf(a), s.shown())
    }

    @Test
    fun aLootedSpotIsNeverConfirmedAgain() {
        // The research's 281.6 s ding (byte 65) fits both the looted first Lapis and the Umber.
        val spots = data.spots("AMBE_1")!!
        val lapis = CorpseSpot(-172, 16, -186)
        val umber = CorpseSpot(-192, 25, -161)
        val without = CorpseLocatorSolver(spots)
        assertEquals(setOf(lapis, umber), without.ding(-1393, 208, -1357, 65)!!.fitting.toSet())
        assertTrue(without.shown().isEmpty())

        val s = CorpseLocatorSolver(spots)
        assertEquals(lapis, s.loot(-170.57, 14.41, -184.95), "loot line next to the Lapis")
        assertEquals(listOf(umber), s.ding(-1393, 208, -1357, 65)!!.fitting)
        assertEquals(listOf(umber), s.shown())
        // A ding right at the looted spot (as if a second corpse were there) cannot bring it back.
        s.ding((-171.5 * 8).toInt(), 17 * 8, (-185.5 * 8).toInt(), 125)
        assertTrue(lapis !in s.shown() && lapis !in s.confirmed)
    }

    @Test
    fun lootNeedsASpotCloseByAndPrefersTheConfirmedOne() {
        val a = CorpseSpot(-150, 10, -170)
        val b = CorpseSpot(-150, 10, -164)
        val s = CorpseLocatorSolver(listOf(a, b))
        assertNull(s.loot(-150.0, 10.0, -185.0), "nothing within reach")
        ding(s, doubleArrayOf(-149.5, 10.0, -180.0), stand(b))
        assertEquals(listOf(b), s.shown())
        // Standing between both, slightly nearer the empty one: the confirmed corpse was looted.
        assertEquals(b, s.loot(-149.5, 10.0, -166.9))
        assertTrue(s.shown().isEmpty())
    }

    @Test
    fun aConfirmedSpotADingProvesEmptyIsRetracted() {
        val a = CorpseSpot(-150, 10, -170)
        val b = CorpseSpot(-150, 10, -150)
        val s = CorpseLocatorSolver(listOf(a, b))
        ding(s, doubleArrayOf(-149.5, 10.0, -178.0), stand(a))
        assertEquals(listOf(a), s.shown())
        // The Lapis at a was looted without a line we read: standing on it, the ding measures b.
        ding(s, doubleArrayOf(-149.5, 10.0, -168.0), stand(b))
        assertEquals(setOf(a), s.emptied)
        assertEquals(listOf(b), s.shown())
    }

    @Test
    fun unknownLayoutsHaveNoSpots() {
        for (id in listOf("RUBY_C", "LITT_L", "NEWT_1")) assertNull(data.spots(id), id)
        assertTrue(data.layouts.isNotEmpty())
        for ((id, spots) in data.layouts) assertTrue(spots.isNotEmpty() && spots.all { it.size == 3 }, id)
    }

    @Test
    fun theVanguardShaftShowsItsSpotFromEntry() {
        val type = assertNotNull(data.fixed["FAIR_1"])
        assertEquals("Vanguard", type)
        val spots = data.spots("FAIR_1")!!
        val s = CorpseLocatorSolver(spots, fixed = true)
        assertEquals(spots, s.shown())
        // Never retracted by a ding, only by the loot line.
        s.ding((-139.5 * 8).toInt(), 4 * 8, (-171.5 * 8).toInt(), 63)
        assertEquals(spots, s.shown())
        assertEquals(spots.single(), s.loot(-138.5, 3.0, -170.5))
        assertTrue(s.shown().isEmpty())
    }

    @Test
    fun aDingAwayFromThePlayerIsNotOurs() {
        val history = listOf(doubleArrayOf(-150.0, 10.0, -170.0), doubleArrayOf(-150.4, 10.0, -170.9))
        assertTrue(CorpseLocatorSolver.isOwnDing(-150.5, 10.0, -171.0, history))
        assertTrue(CorpseLocatorSolver.isOwnDing(-150.0, 11.4, -170.0, history))
        assertTrue(!CorpseLocatorSolver.isOwnDing(-153.0, 10.0, -170.0, history), "a party member three blocks away")
        assertTrue(!CorpseLocatorSolver.isOwnDing(-150.0, 10.0, -170.0, emptyList()))
    }

    /**
     * Every bundled layout: 1-4 corpses on random spots (stand anywhere in the spot's box: the data
     * mixes floor, round and ceil), a player wandering within 22 blocks, the server's ding (nearest
     * unlooted, silent beyond 20) and loots from next to a corpse. No prefix may show a spot without
     * an unlooted corpse, retract a real one, or lose the true stand from a spot's region.
     */
    @Test
    fun simulationNeverConfirmsAWrongSpot() {
        val rnd = Random(20261118)
        var dings = 0
        var single = 0
        // Per corpse: dings from the first one it was the nearest for, until it was confirmed.
        val needed = sortedMapOf<Int, Int>()
        var trials = 0
        var drawn = 0
        var shownFrames = 0
        var worst = 0.0
        var errSum = 0.0
        var worstY = 0.0
        for ((id, _) in data.layouts) {
            val spots = data.spots(id)!!
            val fixed = id in data.fixed
            repeat(400) {
                trials++
                val count = if (fixed) spots.size else rnd.nextInt(1, minOf(4, spots.size) + 1)
                val chosen = spots.shuffled(rnd).take(count)
                val box = CorpseLocatorSolver.SPREAD
                val stands = chosen.associateWith { stand(it, rnd.nextDouble(-box, box), rnd.nextDouble(-box, box), rnd.nextDouble(-box, box)) }
                val looted = HashSet<CorpseSpot>()
                val s = CorpseLocatorSolver(spots, fixed)
                val since = HashMap<CorpseSpot, Int>()
                repeat(40) {
                    val unlooted = chosen.filter { it !in looted }
                    if (unlooted.isEmpty()) return@repeat
                    val target = stands.getValue(unlooted.random(rnd))
                    if (rnd.nextInt(12) == 0) {
                        // Loot from reach (within ~2 blocks of the stand), after the walk-up's ding there.
                        val spot = stands.entries.first { it.value === target }.key
                        val p = doubleArrayOf(target[0] + rnd.nextDouble(-1.5, 1.5), target[1] - 0.5, target[2] + rnd.nextDouble(-1.5, 1.5))
                        ding(s, p, stands.getValue(unlooted.minBy { dist(p, stands.getValue(it)) }))
                        for (shown in s.shown()) assertTrue(shown in unlooted, "$id: shown $shown at the loot")
                        assertEquals(spot, s.loot(p[0], p[1], p[2]), "$id loot (corpses $chosen, confirmed ${s.confirmed})")
                        looted += spot
                        return@repeat
                    }
                    val r = rnd.nextDouble(0.0, 22.0)
                    val u = doubleArrayOf(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-0.6, 0.6), rnd.nextDouble(-1.0, 1.0))
                    val len = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]).coerceAtLeast(1e-6)
                    val player = doubleArrayOf(target[0] + u[0] / len * r, target[1] + u[1] / len * r, target[2] + u[2] / len * r)
                    val nearest = unlooted.minBy { dist(player, stands.getValue(it)) }
                    val fit = ding(s, player, stands.getValue(nearest)) ?: return@repeat
                    dings++
                    if (fit.fitting.size == 1) single++
                    assertTrue(nearest in fit.fitting, "$id: the true spot fits")
                    for (shown in s.shown()) {
                        assertTrue(shown in unlooted, "$id: shown $shown holds no unlooted corpse (corpses $chosen, looted $looted)")
                        val truth = stands.getValue(shown)
                        assertTrue(s.possibleDistance(shown, truth[0], truth[1], truth[2]) <= CorpseLocatorSolver.SLACK + 1e-9, "$id: the true stand left $shown's region")
                        shownFrames++
                        s.position(shown)?.let { at ->
                            drawn++
                            val e = sqrt((at[0] - truth[0]) * (at[0] - truth[0]) + (at[2] - truth[2]) * (at[2] - truth[2]))
                            errSum += e
                            worst = maxOf(worst, e)
                            worstY = maxOf(worstY, kotlin.math.abs(at[1] - truth[1]))
                        }
                    }
                    assertTrue(s.emptied.isEmpty(), "$id: retracted a real corpse ${s.emptied}")
                    if (!fixed && nearest !in since) since[nearest] = 0
                    if (nearest in since && since.getValue(nearest) >= 0) {
                        since[nearest] = since.getValue(nearest) + 1
                        if (nearest in s.confirmed) {
                            needed.merge(since.getValue(nearest), 1, Int::plus)
                            since[nearest] = -1
                        }
                    }
                }
            }
        }
        println(
            "simulation: $trials trials over ${data.layouts.size} layouts, $dings dings, ${"%.1f".format(100.0 * single / dings)}% fit one spot; " +
                "dings until confirmed -> corpses: $needed; drawn at the estimate ${"%.1f".format(100.0 * drawn / shownFrames)}% of shown frames, " +
                "horizontal error mean ${"%.2f".format(errSum / drawn)} max ${"%.2f".format(worst)}, height error max ${"%.2f".format(worstY)}",
        )
        assertTrue(single > dings * 0.85, "most dings decide alone")
        assertTrue(worst <= CorpseLocatorSolver.TIGHT + CorpseLocatorSolver.SLACK, "a tight estimate is within its spread of the stand")
    }

    @Test
    fun everyRealStandLiesInItsSpotsBox() {
        // Floored (AMBE_1 x3, JADE_1) and rounded/ceiled (CITR_2) positions: the dataset mixes conventions.
        val real =
            listOf(
                "AMBE_1" to doubleArrayOf(-191.5, 25.90625, -160.5),
                "AMBE_1" to doubleArrayOf(-171.8125, 16.4375, -185.375),
                "AMBE_1" to doubleArrayOf(-122.34375, 23.46875, -159.21875),
                "JADE_1" to doubleArrayOf(-154.0, 12.3, -174.0),
                "CITR_2" to doubleArrayOf(-117.5, 3.7, -168.5),
            )
        for ((layout, p) in real) {
            val spots = data.spots(layout)!!
            assertEquals(1, spots.count { CorpseLocatorSolver.pointDistance(p[0], p[1], p[2], it) == 0.0 }, "$layout ${p.toList()} in exactly one box")
        }
        val citr = doubleArrayOf(-117.5, 3.7, -168.5)
        assertTrue(CorpseSpot(floor(citr[0]).toInt(), floor(citr[1]).toInt(), floor(citr[2]).toInt()) !in data.spots("CITR_2")!!, "CITR_2 is not floored")
    }

    /**
     * The owner's CITR_2 Tungsten: stand (-117.5, 3.7, -168.5), listed as (-117, 4, -168). Walking
     * around it: confirmed, never retracted, the estimate closes in on the stand, and once seen the
     * waypoint is the stand and its dings are explained (muted).
     */
    @Test
    fun theRoundedCitr2SpotIsConfirmedRefinedAndNeverRetracted() {
        val spots = data.spots("CITR_2")!!
        val spot = CorpseSpot(-117, 4, -168)
        val truth = doubleArrayOf(-117.5, 3.7, -168.5)
        val s = CorpseLocatorSolver(spots)
        val errors = ArrayList<String>()
        var n = 0
        for (lap in 0 until 3) {
            for (step in 0 until 12) {
                val a = Math.toRadians(step * 30.0 + lap * 10)
                val r = 14.0 - lap * 4
                val player = doubleArrayOf(truth[0] + r * kotlin.math.cos(a), truth[1] + 0.3 + (step % 3 - 1), truth[2] + r * kotlin.math.sin(a))
                val fit = assertNotNull(ding(s, player, truth))
                n++
                assertTrue(spot in fit.fitting, "ding $n fits the spot")
                assertTrue(s.emptied.isEmpty(), "ding $n retracted ${s.emptied}")
                assertTrue(s.possibleDistance(spot, truth[0], truth[1], truth[2]) <= CorpseLocatorSolver.SLACK + 1e-9, "ding $n kept the stand possible")
                if (n in listOf(1, 2, 3, 5, 10, 20, 36)) errors += "$n:${"%.2f".format(dist(s.center(spot), truth))}"
            }
        }
        println("CITR_2 estimate error after N dings: $errors; spread ${"%.2f".format(s.spread(spot))}")
        assertEquals(listOf(spot), s.shown())
        val at = assertNotNull(s.position(spot), "tight after $n dings")
        assertTrue(dist(at, truth) < 0.5, "estimate ${at.toList()} near the stand")
        assertNotNull(CorpseLocatorSolver.standOffset(spot, truth[0], truth[1], truth[2]), "the seen stand belongs to the spot")
        s.pin(spot, truth[0], truth[1], truth[2])
        assertEquals(truth.toList(), s.position(spot)!!.toList())
        val after = assertNotNull(ding(s, doubleArrayOf(-110.0, 4.0, -160.0), truth))
        assertTrue(after.explained, "a ding from the seen corpse is explained")
        assertTrue(s.emptied.isEmpty())
    }

    @Test
    fun theOldBlockOnlyRuleWouldHaveRetractedCitr2() {
        // Beyond the listed block (-117, 4, -168) as seen from the stand: every point of that block is
        // nearer than the ding says, so the old block-only rule "proved" it empty (the owner lost it so).
        val spots = data.spots("CITR_2")!!
        val truth = doubleArrayOf(-117.5, 3.7, -168.5)
        val player = doubleArrayOf(-113.0, 6.0, -164.0)
        val (lo, _) = CorpseLocatorSolver.interval(CorpseLocatorSolver.byteFor(dist(player, truth))!!)!!
        val oldBlockFarthest = sqrt(4.0 * 4.0 + 2.0 * 2.0 + 4.0 * 4.0)
        assertTrue(oldBlockFarthest < lo - CorpseLocatorSolver.EPS, "old rule: $oldBlockFarthest < $lo")
        val s = CorpseLocatorSolver(spots)
        ding(s, doubleArrayOf(-110.0, 4.0, -168.0), truth)
        assertEquals(listOf(CorpseSpot(-117, 4, -168)), s.shown())
        ding(s, player, truth)
        assertTrue(s.emptied.isEmpty())
        assertEquals(listOf(CorpseSpot(-117, 4, -168)), s.shown())
    }
}
