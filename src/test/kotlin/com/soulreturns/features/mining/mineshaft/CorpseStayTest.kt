package com.soulreturns.features.mining.mineshaft

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorpseStayTest {
    private val data = CorpseSpotData.parse(javaClass.getResource("/assets/soul/mineshaft/corpse_spots.json")!!.readText())

    private fun dist(a: DoubleArray, b: DoubleArray): Double = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    private var tick = 0L

    /** The server's ding for a player at [player] measuring the stand at [stand], 10 ticks after the last one. */
    private fun ding(st: CorpseStay, player: DoubleArray, stand: DoubleArray): DingFit? {
        tick += 10
        return st.ding((player[0] * 8).toInt(), (player[1] * 8).toInt(), (player[2] * 8).toInt(), CorpseLocatorSolver.byteFor(dist(player, stand))!!, tick)
    }

    // CITR_2's two spots 6.16 apart, the closest pair in the data.
    private val citrA = CorpseSpot(-142, 10, -187)
    private val citrB = CorpseSpot(-143, 11, -181)
    private val standA = doubleArrayOf(-141.5, 10.73, -186.5)
    private val standB = doubleArrayOf(-142.5, 11.73, -180.5)

    /** A located by its dings (not seen), B walked up to and opened before the sight scan saw it. */
    private fun citrStay(): CorpseStay {
        val st = CorpseStay(CorpseLocatorSolver(data.spots("CITR_2")!!))
        ding(st, doubleArrayOf(-150.0, 10.0, -195.0), standA)
        assertEquals(listOf(citrA), st.solver.shown())
        return st
    }

    @Test
    fun aLootGuessedFromThePositionCanTakeTheNeighbor() {
        // Between the two, B in reach: the guess prefers the located A, whose box is 3.5 away.
        val st = citrStay()
        assertEquals(citrA, st.loot(-142.5, 11.0, -182.5, "LAPIS"))
        // B's stand, seen afterwards, is shown although it was the one looted; A is hidden although unlooted.
        st.see(standB[0], standB[1], standB[2], "Lapis")
        assertEquals(listOf(citrB), st.solver.shown())
    }

    @Test
    fun theStandUnderTheCrosshairIsTheOneLooted() {
        val st = citrStay()
        assertEquals(citrB, st.loot(-142.5, 11.0, -182.5, "LAPIS", aimed = standB))
        assertEquals(listOf(citrA), st.solver.shown())
        // Its stand seen again later stays hidden.
        st.see(standB[0], standB[1], standB[2], "Lapis")
        assertEquals(listOf(citrA), st.solver.shown())
    }

    @Test
    fun neverMoreDrawnThanTheTabListsPerType() {
        // AMBE_1, the recording's stands: two Lapis seen, the tab says one Lapis left (one was looted unseen).
        val st = CorpseStay(CorpseLocatorSolver(data.spots("AMBE_1")!!))
        val looted = doubleArrayOf(-171.7869, 16.4411, -185.3481)
        val live = doubleArrayOf(-122.3381, 23.4838, -159.2151)
        st.see(looted[0], looted[1], looted[2], "Lapis")
        st.see(live[0], live[1], live[2], "Lapis")
        // The talisman measures only unlooted corpses: its dings point at the live one.
        ding(st, doubleArrayOf(-130.0, 23.0, -160.0), live)
        ding(st, doubleArrayOf(-122.0, 23.0, -170.0), live)
        val drawn = st.shown(mapOf("Lapis" to 1, "Umber" to 1))
        assertEquals(listOf(CorpseSpot(-123, 23, -160)), drawn)
        assertEquals(1, st.capEvents)
        assertTrue(st.capped.getValue(CorpseSpot(-172, 16, -186)).startsWith("seen, 0 ding(s)"), st.capped.toString())
        // Same answer next frame: one event, not one per frame.
        st.shown(mapOf("Lapis" to 1, "Umber" to 1))
        assertEquals(1, st.capEvents)
    }

    @Test
    fun aSpotOnlyTheDingsPointAtGoesBeforeASeenOne() {
        val st = CorpseStay(CorpseLocatorSolver(data.spots("AMBE_1")!!))
        val umber = doubleArrayOf(-191.5, 25.9083, -160.5)
        ding(st, doubleArrayOf(-185.0, 25.0, -150.0), umber)
        val lapis = doubleArrayOf(-171.7869, 16.4411, -185.3481)
        st.see(lapis[0], lapis[1], lapis[2], "Lapis")
        assertEquals(2, st.solver.shown().size)
        assertEquals(listOf(CorpseSpot(-172, 16, -186)), st.shown(mapOf("Lapis" to 1)))
        assertTrue(st.capped.getValue(CorpseSpot(-192, 25, -161)).startsWith("dings only, 1 ding(s)"), st.capped.toString())
        // The tab catching up (an Umber row) draws both again.
        assertEquals(2, st.shown(mapOf("Lapis" to 1, "Umber" to 1)).size)
        assertTrue(st.capped.isEmpty())
    }

    @Test
    fun aDingSpotContradictedByASeenStandGoesFirst() {
        // LITT_L-like: no listed spot near; a gap corpse's ding confirmed listed AMBE_1 spot X, then the gap stand was seen.
        val spots = data.spots("AMBE_1")!!
        val listed = CorpseSpot(-172, 16, -186)
        val player = doubleArrayOf(-160.0, 16.5, -186.0)
        val gap = doubleArrayOf(-160.5, 16.9, -197.5)
        val st = CorpseStay(CorpseLocatorSolver(spots))
        ding(st, player, gap)
        assertEquals(listOf(listed), st.solver.shown())
        val other = doubleArrayOf(-122.3381, 23.4838, -159.2151)
        ding(st, doubleArrayOf(-130.0, 23.0, -160.0), other)
        st.see(gap[0], gap[1], gap[2], "Umber")
        assertEquals(0, st.solver.support(listed), "its only ding is the seen gap stand's")
        assertEquals(3, st.solver.shown().size)
        val drawn = st.shown(mapOf("Umber" to 1, "Lapis" to 1))
        assertEquals(setOf(CorpseSpot(-161, 16, -198), CorpseSpot(-123, 23, -160)), drawn.toSet())
        assertNotNull(st.capped[listed])
    }

    @Test
    fun theVanguardSpotIsNeverHeldBack() {
        val st = CorpseStay(CorpseLocatorSolver(data.spots("FAIR_1")!!, fixed = true))
        st.book.seenTypes[CorpseSpot(-139, 3, -171)] = "Vanguard"
        st.see(-150.0, 3.5, -160.0, "Lapis")
        assertEquals(listOf(CorpseSpot(-139, 3, -171)), st.shown(mapOf("Vanguard" to 1)))
    }

    /**
     * The owner's stay on 0.3.2-beta.3: three Lapis corpses, a fourth waypoint from one stray ding at
     * the start on an empty spot X, typed Lapis by elimination once two were seen, still there after
     * walking up to it. Now: never more than three drawn, and looking at X's empty box removes it.
     */
    @Test
    fun aPhantomFromAStrayDingNeverMakesAFourthWaypointAndGoesWhenSeenEmpty() {
        val c1 = CorpseSpot(-150, 10, -170)
        val c2 = CorpseSpot(-130, 10, -170)
        val c3 = CorpseSpot(-110, 10, -170)
        val x = CorpseSpot(-150, 10, -140)
        val stands = mapOf(c1 to doubleArrayOf(-149.5, 10.9, -169.5), c2 to doubleArrayOf(-129.5, 10.9, -169.5), c3 to doubleArrayOf(-109.5, 10.9, -169.5))
        val st = CorpseStay(CorpseLocatorSolver(listOf(c1, c2, c3, x)))
        val tab = mapOf("Lapis" to 3)
        fun drawn() = st.shown(tab).also { assertTrue(it.size <= 3, "drawn $it") }
        // A stray ding (8 from where the player stands, nothing there but X fits).
        val stray = st.ding((-150 * 8), 80, (-148 * 8), CorpseLocatorSolver.byteFor(8.0)!!, tick.also { tick += 10 })!!
        assertEquals(x, stray.placed)
        // The player leaves X's range before another ding (the run ends), then the dings find c1.
        st.solver.moved(-150.0, 10.0, -176.0)
        ding(st, doubleArrayOf(-150.0, 10.0, -176.0), stands.getValue(c1))
        assertEquals(setOf(x, c1), drawn().toSet())
        st.see(stands.getValue(c1)[0], stands.getValue(c1)[1], stands.getValue(c1)[2], "Lapis")
        st.see(stands.getValue(c2)[0], stands.getValue(c2)[1], stands.getValue(c2)[2], "Lapis")
        st.eliminate(mapOf("Lapis" to (3 to 0)))
        assertEquals("Lapis", st.typeOf(x), "two of three seen: the one left is Lapis, X the only unknown")
        // c3 found by its dings: four located, three drawn; X (one ding) gives way to c3 (three).
        st.solver.moved(-110.0, 10.0, -176.0)
        for (z in listOf(-176.0, -177.0, -178.0)) ding(st, doubleArrayOf(-110.0, 10.0, z), stands.getValue(c3))
        assertEquals(4, st.solver.shown().size)
        assertEquals(setOf(c1, c2, c3), drawn().toSet())
        // The player walks up to X and sees its box empty on two scans: X is withdrawn and loses its type.
        st.look(setOf(x))
        assertTrue(x in st.solver.shown(), "one scan is not enough")
        st.look(setOf(x))
        assertEquals(setOf(c1, c2, c3), st.solver.shown().toSet())
        assertNull(st.typeOf(x))
        assertTrue(x in st.solver.empty)
        assertEquals(1, st.solver.withdrawals)
        assertEquals(setOf(c1, c2, c3), drawn().toSet())
    }

    /**
     * Every layout, 1-4 corpses, a player walking up to each corpse in turn (0.6 blocks per ding),
     * seeing stands within 6 blocks, looting from reach, the server's sticky-target ding every 10 ticks,
     * and one in [STRAY] dings replaced by a stray: a random distance byte (a harp from elsewhere) or the
     * distance from up to 3 blocks off the ding's position. Without strays nothing is ever withdrawn; with
     * them no real corpse stays withdrawn and a phantom is gone within its run's next dings or once the
     * player looks at its empty box; never more drawn than corpses unlooted.
     */
    @Test
    fun simulationStrayDingsLeaveNoLastingPhantom() {
        for (stray in listOf(0, STRAY)) {
            val r = strayRun(stray)
            println(
                "stray dings 1 in ${if (stray == 0) "never" else stray}: ${r.trials} stays, ${r.dings} dings (${r.strays} stray); " +
                    "phantoms ${r.phantoms}, gone after ${r.lifetimes.sorted().groupingBy { it }.eachCount()} ding(s) (withdrawn by the run ${r.byRun}, by sight ${r.bySight}, " +
                    "left when the last corpse was looted ${r.lasting}; beta.3 would have kept ${r.beta3Phantoms}); frames drawing a phantom while corpses were left ${r.phantomFrames} of ${r.frames}; " +
                    "real corpses withdrawn ${r.falseWithdrawals} (shown again after ${r.restored.groupingBy { it }.eachCount()} ding(s)); frames drawing more than unlooted ${r.over}",
            )
            assertEquals(0, r.over, "never more drawn than unlooted")
            if (stray == 0) {
                assertEquals(0, r.phantoms, "real dings place no phantom")
                assertEquals(0, r.falseWithdrawals, "real dings withdraw no real corpse")
            }
        }
    }

    private class StrayResult {
        var trials = 0
        var dings = 0
        var strays = 0
        var phantoms = 0
        var byRun = 0
        var bySight = 0
        var lasting = 0
        var falseWithdrawals = 0
        var over = 0
        var beta3Phantoms = 0
        var frames = 0
        var phantomFrames = 0
        val lifetimes = ArrayList<Int>()
        val restored = ArrayList<Int>()
    }

    private fun strayRun(strayEvery: Int): StrayResult {
        val rnd = kotlin.random.Random(20261119)
        val out = StrayResult()
        val types = listOf("Lapis", "Umber", "Tungsten")
        for (id in data.layouts.keys + "CRYST_C") {
            if (id in data.fixed) continue
            val spots = data.spots(id)!!
            repeat(60) {
                out.trials++
                val chosen = spots.shuffled(rnd).take(rnd.nextInt(1, minOf(4, spots.size) + 1))
                val box = CorpseLocatorSolver.SPREAD
                val stands = chosen.associateWith { doubleArrayOf(it.x + rnd.nextDouble(-box, box), it.y + rnd.nextDouble(-box, box), it.z + rnd.nextDouble(-box, box)) }
                val typeOf = chosen.associateWith { types.random(rnd) }
                val st = CorpseStay(CorpseLocatorSolver(spots))
                val looted = HashSet<CorpseSpot>()
                val start = stands.values.first()
                val p = doubleArrayOf(start[0] + rnd.nextDouble(-18.0, 18.0), start[1] + 1, start[2] + rnd.nextDouble(-18.0, 18.0))
                var target: CorpseSpot? = null
                var t = 0L
                val since = HashMap<CorpseSpot, Int>()
                val withdrawnAt = HashMap<CorpseSpot, Int>()
                fun holds(spot: CorpseSpot) = chosen.any { it !in looted && CorpseLocatorSolver.pointDistance(stands.getValue(it)[0], stands.getValue(it)[1], stands.getValue(it)[2], spot) == 0.0 }
                for (step in 0 until 400) {
                    val unlooted = chosen.filter { it !in looted }
                    if (unlooted.isEmpty()) break
                    val focus = unlooted.minBy { dist(p, stands.getValue(it)) }
                    val f = stands.getValue(focus)
                    val d = dist(p, f)
                    if (d > 1.5) for (i in 0..2) p[i] += (f[i] - p[i]) / d * 0.6 + rnd.nextDouble(-0.2, 0.2)
                    t += 10
                    st.solver.moved(p[0], p[1], p[2])
                    for (c in unlooted) if (dist(p, stands.getValue(c)) <= 6) stands.getValue(c).let { st.see(it[0], it[1], it[2], typeOf.getValue(c)) }
                    // A ding-only spot within 6 whose box holds no unlooted corpse: the player sees it empty.
                    st.look(st.solver.shown().filter { st.solver.isDingOnly(it) && CorpseLocatorSolver.pointDistance(p[0], p[1], p[2], it) <= 6 && !holds(it) }.toSet())
                    if (d <= 1.5) {
                        st.loot(p[0], p[1], p[2], typeOf.getValue(focus), aimed = f)
                        looted += focus
                        if (target == focus) target = null
                    } else {
                        target = unlooted.filter { dist(p, stands.getValue(it)) <= CorpseLocatorSolver.RANGE }.let { near -> target?.takeIf { it in near } ?: near.minByOrNull { dist(p, stands.getValue(it)) } }
                        target?.let { tg ->
                            val ix = (p[0] * 8).toInt()
                            val iy = (p[1] * 8).toInt()
                            val iz = (p[2] * 8).toInt()
                            var isStray = false
                            val n =
                                if (strayEvery > 0 && rnd.nextInt(strayEvery) == 0) {
                                    out.strays++
                                    isStray = true
                                    if (rnd.nextBoolean()) {
                                        rnd.nextInt(CorpseLocatorSolver.MIN_BYTE, CorpseLocatorSolver.MAX_BYTE + 1)
                                    } else {
                                        val off = doubleArrayOf(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0)).let { v -> val l = sqrt(v.sumOf { it * it }); v.map { it / l * rnd.nextDouble(0.0, 3.0) } }
                                        CorpseLocatorSolver.byteFor(dist(doubleArrayOf(p[0] + off[0], p[1] + off[1], p[2] + off[2]), stands.getValue(tg)))
                                    }
                                } else {
                                    CorpseLocatorSolver.byteFor(dist(p, stands.getValue(tg)))
                                }
                            if (n != null) {
                                out.dings++
                                val before = st.solver.shown().toSet()
                                val fit = st.ding(ix, iy, iz, n, t)
                                fit?.withdrawn?.let { w -> if (holds(w)) { out.falseWithdrawals++; withdrawnAt[w] = out.dings } }
                                // 0.3.2-beta.3 confirmed any spot a ding fit alone, for the rest of the stay.
                                if (isStray && fit != null && fit.fitting.size == 1 && !holds(fit.fitting[0]) && fit.fitting[0] !in before) out.beta3Phantoms++
                            }
                        }
                    }
                    for ((w, at) in withdrawnAt.entries.toList()) if (w in st.solver.shown()) { out.restored += out.dings - at; withdrawnAt -= w }
                    val unlootedNow = chosen.filter { it !in looted }
                    val tab = unlootedNow.groupingBy { typeOf.getValue(it) }.eachCount()
                    // All looted: the locator draws nothing (tab_all_looted).
                    val drawn = if (unlootedNow.isEmpty()) emptyList() else st.shown(tab)
                    if (drawn.size > unlootedNow.size) out.over++
                    if (unlootedNow.isNotEmpty()) {
                        out.frames++
                        if (drawn.any { !holds(it) }) out.phantomFrames++
                    }
                    val shown = st.solver.shown()
                    for (s in shown) if (!holds(s) && s !in since) { since[s] = out.dings; out.phantoms++ }
                    for (s in since.keys.toList()) {
                        if (s in shown) continue
                        out.lifetimes += out.dings - since.getValue(s)
                        if (s in st.solver.empty) out.bySight++ else out.byRun++
                        since -= s
                    }
                }
                out.lasting += since.size
            }
        }
        return out
    }

    companion object {
        private const val STRAY = 20
    }
}
