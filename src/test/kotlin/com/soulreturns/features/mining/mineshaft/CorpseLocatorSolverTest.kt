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
    fun aDingMeasuringAnotherCorpseAfterTheRunEndsNeverDropsALocatedOne() {
        val a = CorpseSpot(-150, 10, -170)
        val b = CorpseSpot(-150, 10, -150)
        val s = CorpseLocatorSolver(listOf(a, b))
        ding(s, doubleArrayOf(-149.5, 10.0, -178.0), stand(a))
        assertEquals(listOf(a), s.shown())
        // The player went out of a's range (the talisman may take b now), then the talisman measures b: a stays.
        s.moved(-149.5, 10.0, -195.0)
        ding(s, doubleArrayOf(-149.5, 10.0, -168.0), stand(b))
        assertEquals(setOf(a, b), s.shown().toSet())
    }

    /**
     * AMET_1: a ding from the corpse on A fits A and B, the player walks 6 blocks, A is now beyond 20
     * and the talisman takes the corpse on C, whose ding fits B and C. Intersecting the two dings would
     * place B, where no corpse is; A's possible reach beyond 20 ends the run between them, with or
     * without the tick's position check.
     */
    @Test
    fun twoCorpsesDingsAcrossATargetSwitchNeverPlaceTheSpotBothFit() {
        val spots = data.spots("AMET_1")!!
        val a = CorpseSpot(-184, 9, -178)
        val b = CorpseSpot(-178, 3, -163)
        val c = CorpseSpot(-158, 20, -165)
        val p1 = doubleArrayOf(-171.3, 10.8, -170.18)
        val p2 = doubleArrayOf(-167.06, 10.8, -165.89)
        for (tickCheck in listOf(true, false)) {
            val s = CorpseLocatorSolver(spots)
            s.moved(p1[0], p1[1], p1[2])
            assertEquals(setOf(a, b), ding(s, p1, stand(a, fy = 0.3))!!.fitting.toSet())
            assertTrue(dist(p2, stand(a, fy = 0.3)) > CorpseLocatorSolver.RANGE)
            if (tickCheck) s.moved(p2[0], p2[1], p2[2])
            assertEquals(setOf(b, c), ding(s, p2, stand(c, fy = 0.3))!!.fitting.toSet())
            assertTrue(b !in s.shown(), "the spot both dings fit was placed (tick check $tickCheck)")
        }
    }

    @Test
    fun aSpotOneDingPlacedIsWithdrawnWhenTheRunsNextDingMissesIt() {
        val a = CorpseSpot(-150, 10, -170)
        val b = CorpseSpot(-150, 10, -150)
        val s = CorpseLocatorSolver(listOf(a, b))
        // A stray measurement (no corpse on a): 8 blocks from a, nothing else fits.
        val stray = s.ding((-149.5 * 8).toInt(), 80, (-178.0 * 8).toInt(), CorpseLocatorSolver.byteFor(8.0)!!)!!
        assertEquals(a, stray.placed)
        // Same run, the talisman's real target b: a cannot be it, so a goes. One ding against one: b waits for the next.
        val next = ding(s, doubleArrayOf(-149.5, 10.0, -168.0), stand(b))!!
        assertEquals(a, next.withdrawn)
        assertEquals(emptyList(), s.shown())
        ding(s, doubleArrayOf(-149.5, 10.0, -167.0), stand(b))
        assertEquals(listOf(b), s.shown())
        assertEquals(1, s.withdrawals)
    }

    @Test
    fun oneStrayDingAfterTwoAgreeingOnesIsIgnored() {
        val a = CorpseSpot(-150, 10, -170)
        val b = CorpseSpot(-150, 10, -150)
        val s = CorpseLocatorSolver(listOf(a, b))
        ding(s, doubleArrayOf(-149.5, 10.0, -178.0), stand(a))
        ding(s, doubleArrayOf(-149.5, 10.0, -177.0), stand(a))
        // A stray 18 from the player there fits only b: held, nothing placed, a kept.
        val stray = s.ding((-149.5 * 8).toInt(), 80, (-168.0 * 8).toInt(), CorpseLocatorSolver.byteFor(18.0)!!)!!
        assertNull(stray.placed)
        assertNull(stray.withdrawn)
        assertEquals(listOf(a), s.shown())
        ding(s, doubleArrayOf(-149.5, 10.0, -176.0), stand(a))
        assertEquals(listOf(a), s.shown())
        assertEquals(0, s.withdrawals)
    }

    @Test
    fun unknownLayoutsHaveNoSpots() {
        for (id in listOf("LITT_L", "NEWT_1")) assertNull(data.spots(id), id)
        assertTrue(data.layouts.isNotEmpty())
        for ((id, spots) in data.layouts) assertTrue(spots.isNotEmpty() && spots.all { it.size == 3 }, id)
    }

    @Test
    fun everyCrystalShaftUsesTheSharedList() {
        val crystal = listOf(CorpseSpot(-181, 14, -189), CorpseSpot(-162, 10, -155), CorpseSpot(-109, 7, -159))
        // The six the source named, RUBY_C (recording mineshaft-corpses-4) and a gem nobody named yet.
        for (id in listOf("AQUA_C", "CITR_C", "JASP_C", "ONYX_C", "OPAL_C", "PERI_C", "RUBY_C", "XXXX_C")) assertEquals(crystal, data.spots(id), id)
        assertTrue(data.layouts.keys.none { CorpseSpotData.isCrystal(it) }, "stored once, under crystal")
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
     * mixes floor, round and ceil), a player wandering within 22 blocks, the server's ding (the
     * talisman's sticky target: kept until beyond 20, then the nearest unlooted; silent beyond 20) and
     * loots from next to a corpse. No prefix may show a spot without an unlooted corpse, drop a located
     * one, or lose the true stand from a spot's region.
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
        val layouts = data.layouts.keys + "CRYST_C"
        for (id in layouts) {
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
                var target: CorpseSpot? = null
                repeat(40) {
                    val unlooted = chosen.filter { it !in looted }
                    if (unlooted.isEmpty()) return@repeat
                    val focus = stands.getValue(unlooted.random(rnd))
                    if (rnd.nextInt(12) == 0) {
                        // Loot from reach (within ~2 blocks of the stand), after the walk-up's ding there. Opening it
                        // means looking at it: the sight scan has located it (the ding alone may measure another).
                        val spot = stands.entries.first { it.value === focus }.key
                        val p = doubleArrayOf(focus[0] + rnd.nextDouble(-1.5, 1.5), focus[1] - 0.5, focus[2] + rnd.nextDouble(-1.5, 1.5))
                        s.moved(p[0], p[1], p[2])
                        target = sticky(target, unlooted, stands, p)
                        target?.let { ding(s, p, stands.getValue(it)) }
                        assertEquals(spot, s.locate(focus[0], focus[1], focus[2]), "$id: the seen stand is its spot")
                        for (shown in s.shown()) assertTrue(shown in unlooted, "$id: shown $shown at the loot")
                        assertEquals(spot, s.loot(p[0], p[1], p[2]), "$id loot (corpses $chosen, confirmed ${s.confirmed})")
                        looted += spot
                        if (target == spot) target = null
                        return@repeat
                    }
                    val r = rnd.nextDouble(0.0, 22.0)
                    val u = doubleArrayOf(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-0.6, 0.6), rnd.nextDouble(-1.0, 1.0))
                    val len = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]).coerceAtLeast(1e-6)
                    val player = doubleArrayOf(focus[0] + u[0] / len * r, focus[1] + u[1] / len * r, focus[2] + u[2] / len * r)
                    val shownBefore = s.shown().toSet()
                    s.moved(player[0], player[1], player[2])
                    target = sticky(target, unlooted, stands, player)
                    val measured = target ?: return@repeat
                    val fit = ding(s, player, stands.getValue(measured)) ?: return@repeat
                    dings++
                    if (fit.fitting.size == 1) single++
                    assertTrue(measured in fit.fitting, "$id: the measured spot fits")
                    assertTrue(s.shown().containsAll(shownBefore), "$id: a located corpse disappeared: ${s.lastWithdrawal}; ding $fit measuring $measured at ${stands.getValue(measured).toList()} from ${player.toList()}; corpses $stands")
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
                    if (!fixed && measured !in since) since[measured] = 0
                    if (measured in since && since.getValue(measured) >= 0) {
                        since[measured] = since.getValue(measured) + 1
                        if (measured in s.confirmed) {
                            needed.merge(since.getValue(measured), 1, Int::plus)
                            since[measured] = -1
                        }
                    }
                }
            }
        }
        println(
            "simulation: $trials trials over ${layouts.size} layouts, $dings dings, ${"%.1f".format(100.0 * single / dings)}% fit one spot; " +
                "dings until confirmed -> corpses: $needed; drawn at the estimate ${"%.1f".format(100.0 * drawn / shownFrames)}% of shown frames, " +
                "horizontal error mean ${"%.2f".format(errSum / drawn)} max ${"%.2f".format(worst)}, height error max ${"%.2f".format(worstY)}",
        )
        assertTrue(single > dings * 0.85, "most dings decide alone")
        assertTrue(worst <= CorpseLocatorSolver.TIGHT + CorpseLocatorSolver.SLACK, "a tight estimate is within its spread of the stand")
    }

    /**
     * Incomplete lists. Every layout, one spot withheld from the list (a dataset gap) holding a corpse
     * among 0-3 others: (a) the gap unknown, as before learning; (b) the gap's stand learned from an
     * earlier visit; (c) a layout without bundled data whose learned list holds only some of its
     * spots. Sticky-target dings, random walks, loots after sight as in the simulation above. A wrong
     * waypoint = a shown spot with no unlooted corpse on it. (b) and (c) must never show one.
     */
    @Test
    fun simulationAnIncompleteListNeverShowsAWrongWaypoint() {
        val rnd = Random(20261119)
        val wrong = intArrayOf(0, 0, 0)
        val frames = intArrayOf(0, 0, 0)
        for (id in data.layouts.keys + "CRYST_C") {
            if (id in data.fixed) continue
            val spots = data.spots(id)!!
            repeat(150) {
                val gap = spots.random(rnd)
                val others = (spots - gap).shuffled(rnd).take(rnd.nextInt(0, minOf(3, spots.size - 1) + 1))
                val chosen = others + gap
                val box = CorpseLocatorSolver.SPREAD
                val stands = chosen.associateWith { stand(it, rnd.nextDouble(-box, box), rnd.nextDouble(-box, box), rnd.nextDouble(-box, box)) }
                val partial = chosen.filter { rnd.nextBoolean() }.map { stands.getValue(it) }
                val solvers = listOf(
                    CorpseLocatorSolver(spots - gap),
                    CorpseLocatorSolver(spots - gap, learned = listOf(stands.getValue(gap))),
                    CorpseLocatorSolver(emptyList(), learned = partial),
                )
                val looted = HashSet<CorpseSpot>()
                var target: CorpseSpot? = null
                repeat(40) {
                    val unlooted = chosen.filter { it !in looted }
                    if (unlooted.isEmpty()) return@repeat
                    val focus = unlooted.random(rnd)
                    val at = stands.getValue(focus)
                    if (rnd.nextInt(12) == 0) {
                        val p = doubleArrayOf(at[0] + rnd.nextDouble(-1.5, 1.5), at[1] - 0.5, at[2] + rnd.nextDouble(-1.5, 1.5))
                        for (s in solvers) {
                            s.moved(p[0], p[1], p[2])
                            assertNotNull(s.locate(at[0], at[1], at[2]), "$id: the seen stand is located")
                            val key = assertNotNull(s.loot(p[0], p[1], p[2]), "$id: the loot finds the seen corpse")
                            assertTrue(holds(s, key, at, spots - gap), "$id: loot attributed to $key, not the corpse on $focus")
                        }
                        looted += focus
                        if (target == focus) target = null
                        return@repeat
                    }
                    if (rnd.nextInt(10) == 0) {
                        // Seen from afar without looting it: a sight locates it in every list.
                        for (s in solvers) assertNotNull(s.locate(at[0], at[1], at[2]), "$id: the seen stand is located")
                        return@repeat
                    }
                    val r = rnd.nextDouble(0.0, 22.0)
                    val u = doubleArrayOf(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-0.6, 0.6), rnd.nextDouble(-1.0, 1.0))
                    val len = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]).coerceAtLeast(1e-6)
                    val player = doubleArrayOf(at[0] + u[0] / len * r, at[1] + u[1] / len * r, at[2] + u[2] / len * r)
                    for (s in solvers) s.moved(player[0], player[1], player[2])
                    target = sticky(target, unlooted, stands, player)
                    val measured = target ?: return@repeat
                    for ((i, s) in solvers.withIndex()) {
                        ding(s, player, stands.getValue(measured))
                        for (shown in s.shown()) {
                            frames[i]++
                            if (unlooted.none { holds(s, shown, stands.getValue(it), spots - gap) }) wrong[i]++
                        }
                    }
                }
            }
        }
        println("incomplete lists (shown frames with a wrong waypoint): gap unknown ${wrong[0]}/${frames[0]}, gap learned ${wrong[1]}/${frames[1]}, partial learned list ${wrong[2]}/${frames[2]}")
        assertEquals(0, wrong[1], "a learned gap never lets the gap's corpse confirm a wrong listed spot")
        assertEquals(0, wrong[2], "a partial learned list confirms only by sight")
    }

    /** Whether shown key [k] stands for the corpse whose stand is at [at]: its listed box, else its pin within [CorpseLocatorSolver.SAME_CORPSE]. */
    private fun holds(s: CorpseLocatorSolver, k: CorpseSpot, at: DoubleArray, listed: List<CorpseSpot>): Boolean =
        if (k in listed) {
            CorpseLocatorSolver.pointDistance(at[0], at[1], at[2], k) == 0.0
        } else {
            s.position(k)?.let { dist(it, at) <= CorpseLocatorSolver.SAME_CORPSE } ?: false
        }

    /** The talisman's target: kept while within 20, else the nearest unlooted corpse within 20 (null: silent). */
    private fun sticky(target: CorpseSpot?, unlooted: List<CorpseSpot>, stands: Map<CorpseSpot, DoubleArray>, p: DoubleArray): CorpseSpot? {
        val near = unlooted.filter { dist(p, stands.getValue(it)) <= CorpseLocatorSolver.RANGE }
        if (target != null && target in near) return target
        return near.minByOrNull { dist(p, stands.getValue(it)) }
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
                assertTrue(s.shown().isEmpty() && n == 1 || s.shown() == listOf(spot), "ding $n: shown ${s.shown()}")
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
        assertEquals(listOf(spot), s.shown())
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
        assertEquals(listOf(CorpseSpot(-117, 4, -168)), s.shown())
    }

    @Test
    fun aSeenStandLocatesItsSpotWithoutADing() {
        val spots = data.spots("AMBE_1")!!
        val s = CorpseLocatorSolver(spots)
        val spot = assertNotNull(s.locate(-171.8125, 16.4375, -185.375, "Lapis"))
        assertEquals(CorpseSpot(-172, 16, -186), spot)
        assertEquals(listOf(spot), s.shown())
        assertEquals(listOf(-171.8125, 16.4375, -185.375), s.position(spot)!!.toList())
        assertTrue(s.extras.isEmpty())
        // Its dings are explained (muted) from the first one.
        assertTrue(assertNotNull(ding(s, doubleArrayOf(-160.0, 16.0, -186.0), doubleArrayOf(-171.8125, 16.4375, -185.375))).explained)
    }

    @Test
    fun aStandOutsideEveryBoxIsAnExtraCorpse() {
        // A layout without data: sight alone locates, and the extra explains its dings and takes its loot.
        val s = CorpseLocatorSolver(emptyList())
        val stand = doubleArrayOf(-150.3, 12.0, -170.6)
        val extra = assertNotNull(s.locate(stand[0], stand[1], stand[2], "Umber"))
        assertEquals(CorpseSpot(-151, 12, -171), extra)
        assertEquals(listOf(extra), s.extras)
        assertEquals(listOf(extra), s.shown())
        assertEquals(extra, s.locate(-150.0, 12.1, -170.2, "Umber"), "the same corpse seen again (it moved a little)")
        assertEquals(1, s.extras.size)
        val fit = assertNotNull(ding(s, doubleArrayOf(-140.0, 12.0, -170.0), doubleArrayOf(-150.0, 12.1, -170.2)))
        assertEquals(listOf(extra), fit.fitting)
        assertTrue(fit.explained)
        assertEquals(extra, s.loot(-149.0, 12.0, -169.0, "UMBER"))
        assertTrue(s.shown().isEmpty())
        assertNull(s.locate(stand[0], stand[1], stand[2], "Umber"), "its stand outlives the loot")
        assertTrue(s.shown().isEmpty())
    }

    @Test
    fun aCorpseLootedBeforeItWasLocatedStaysHidden() {
        val s = CorpseLocatorSolver(emptyList())
        assertNull(s.loot(-150.0, 12.0, -170.0, "LAPIS"), "nothing located there yet")
        assertNull(s.locate(-151.5, 12.0, -172.0, "Lapis"), "the looted Lapis' stand")
        assertTrue(s.shown().isEmpty())
        // Another type next to it is a different corpse.
        assertNotNull(s.locate(-150.5, 12.0, -168.0, "Umber"))
        assertEquals(1, s.shown().size)
    }

    @Test
    fun aLearnedSpotIsConfirmedOnlyBySight() {
        val learned = doubleArrayOf(-150.3, 12.2, -170.6)
        val s = CorpseLocatorSolver(emptyList(), learned = listOf(learned))
        val key = CorpseSpot(-151, 12, -171)
        assertEquals(setOf(key), s.learnedSpots)
        // Dings fitting only the learned spot, from all around: no proof (the list is incomplete), nothing shown, all audible.
        for (p in listOf(doubleArrayOf(-140.0, 12.0, -170.0), doubleArrayOf(-150.0, 12.0, -160.0), doubleArrayOf(-158.0, 13.0, -178.0))) {
            val fit = assertNotNull(ding(s, p, learned))
            assertEquals(listOf(key), fit.fitting)
            assertTrue(!fit.explained)
        }
        // A corpse on an unrecorded spot 15 above it, measured from halfway up: the mirror fits the learned spot too.
        val above = doubleArrayOf(learned[0], learned[1] + 15, learned[2])
        assertEquals(listOf(key), assertNotNull(ding(s, doubleArrayOf(-142.0, learned[1] + 7.5, -166.0), above)).fitting)
        assertTrue(s.shown().isEmpty())
        // Seen: the learned spot is the corpse, at the seen position, and its dings are explained.
        assertEquals(key, s.locate(-150.31, 12.2, -170.58, "Umber"))
        assertEquals(listOf(key), s.shown())
        assertEquals(listOf(-150.31, 12.2, -170.58), s.position(key)!!.toList())
        assertTrue(assertNotNull(ding(s, doubleArrayOf(-140.0, 12.0, -170.0), learned)).explained)
    }

    @Test
    fun aLearnedGapKeepsAListedSpotFromAWrongConfirmation() {
        val spots = data.spots("AMBE_1")!!
        val listed = CorpseSpot(-172, 16, -186)
        val player = doubleArrayOf(-160.0, 16.5, -186.0)
        // A corpse on a spot the data misses, as far from the player as the listed spot's box.
        val gap = doubleArrayOf(-160.5, 16.9, -197.5)
        val unknown = CorpseLocatorSolver(spots)
        assertEquals(listOf(listed), assertNotNull(ding(unknown, player, gap)).fitting)
        assertEquals(listOf(listed), unknown.shown(), "without the learned gap the listed spot is confirmed wrongly")
        val learned = CorpseLocatorSolver(spots, learned = listOf(gap))
        assertEquals(2, assertNotNull(ding(learned, player, gap)).fitting.size)
        assertTrue(learned.shown().isEmpty())
    }

    @Test
    fun aLearnedPointOnAListedSpotIsDropped() {
        val s = CorpseLocatorSolver(data.spots("AMBE_1")!!, learned = listOf(doubleArrayOf(-171.8, 16.4, -185.4)))
        assertTrue(s.learnedSpots.isEmpty())
    }

    @Test
    fun aLootedSpotsStandNeverComesBack() {
        val spots = data.spots("AMBE_1")!!
        val s = CorpseLocatorSolver(spots)
        assertEquals(CorpseSpot(-172, 16, -186), s.loot(-170.57, 14.41, -184.95, "LAPIS"))
        assertNull(s.locate(-171.8125, 16.4375, -185.375, "Lapis"))
        assertTrue(s.shown().isEmpty())
    }

    @Test
    fun aPartySpotIsShownWithTheirPositionUntilTheDingsAreTight() {
        val spots = data.spots("AMBE_1")!!
        val spot = CorpseSpot(-172, 16, -186)
        val s = CorpseLocatorSolver(spots)
        assertTrue(s.locateSpot(spot))
        assertEquals(listOf(spot), s.shown())
        assertNull(s.position(spot), "the listed block until a position is known")
        s.hint(spot, -171.8, 16.4, -185.4)
        assertEquals(listOf(-171.8, 16.4, -185.4), s.position(spot)!!.toList())
        s.pin(spot, -171.81, 16.44, -185.37)
        assertEquals(listOf(-171.81, 16.44, -185.37), s.position(spot)!!.toList(), "the player's own sighting wins")
        // A looted spot is not brought back by a party member.
        s.loot(-171.0, 16.0, -185.0)
        assertTrue(!s.locateSpot(spot) && s.shown().isEmpty())
        // A party member's spot-less corpse does not move one the player saw.
        val t = CorpseLocatorSolver(emptyList())
        val mine = assertNotNull(t.locate(-150.3, 12.0, -170.6, "Umber"))
        assertEquals(mine, t.locate(-150.0, 12.0, -170.0, "Umber", own = false))
        assertEquals(listOf(-150.3, 12.0, -170.6), t.position(mine)!!.toList())
    }
}
