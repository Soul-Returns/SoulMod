package com.soulreturns.features.mining.mineshaft

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Replay of the mineshaft stays in recordings 2026-11-18_02-55-36_mineshaft-corpses (AMBE_1,
 * `corpses-2026-11-18.txt`), 2026-11-18_04-41-37_mineshaft-corpses-2 (CITR_2, CITR_C, JASP_1,
 * AMET_1, `corpses-2026-11-18b.txt`) and 2026-11-18_17-42-36_mineshaft-corpses-4 (AMET_1, TOPA_1,
 * RUBY_C, `corpses-2026-11-18c.txt`). The corpse stand positions in the fixtures are the ground
 * truth for this test only (`corpse~` = located from its dings, approximate); the solver sees the
 * dings, the loot lines and the player position.
 */
class CorpseLocatorReplayTest {
    private class Corpse(val type: String, val x: Double, val y: Double, val z: Double, val approx: Boolean) {
        /** The listed spot whose box holds the stand (the data mixes floor/round/ceil). */
        lateinit var spot: CorpseSpot
        var lootedAt = Int.MAX_VALUE

        fun dist(px: Double, py: Double, pz: Double): Double = sqrt((px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z))
    }

    private sealed interface Event { val tick: Int }
    private class Ding(override val tick: Int, val ix: Int, val iy: Int, val iz: Int, val pitch: Float) : Event
    private class Loot(override val tick: Int, val type: String, val x: Double, val y: Double, val z: Double) : Event
    private class Tab(override val tick: Int, val looted: Int, val unlooted: Int) : Event
    private class Pos(override val tick: Int, val x: Double, val y: Double, val z: Double) : Event

    private class Stay(val layout: String) {
        val corpses = ArrayList<Corpse>()
        val events = ArrayList<Event>()
    }

    private val stays: List<Stay> =
        ArrayList<Stay>().also { out ->
            listOf("/mineshaft/corpses-2026-11-18.txt", "/mineshaft/corpses-2026-11-18b.txt", "/mineshaft/corpses-2026-11-18c.txt")
                .flatMap { javaClass.getResource(it)!!.readText().lines() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .forEach { line ->
                    val f = line.split(' ')
                    val stay = out.lastOrNull()
                    when (f[0]) {
                        "stay" -> out += Stay(f[1])
                        "corpse", "corpse~" -> stay!!.corpses += Corpse(f[1], f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[0] == "corpse~")
                        "ding" -> stay!!.events += Ding(f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toFloat())
                        "loot" -> stay!!.events += Loot(f[1].toInt(), f[2], f[3].toDouble(), f[4].toDouble(), f[5].toDouble())
                        "pos" -> stay!!.events += Pos(f[1].toInt(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble())
                        "tab" -> {
                            val rows = f[2].split(',')
                            stay!!.events += Tab(f[1].toInt(), rows.count { it.endsWith(":LOOTED") }, rows.count { it.endsWith(":NOT_LOOTED") })
                        }
                    }
                }
        }

    private val data = CorpseSpotData.parse(javaClass.getResource("/assets/soul/mineshaft/corpse_spots.json")!!.readText())

    /** Ground truth: each corpse's spot (the box holding its stand) and, for loots, the corpse of that type nearest the player. */
    private fun labelLoots(stay: Stay) {
        val spots = assertNotNull(data.spots(stay.layout), "layout ${stay.layout} in the data")
        for (c in stay.corpses) c.spot = spots.single { CorpseLocatorSolver.pointDistance(c.x, c.y, c.z, it) == 0.0 }
        for (e in stay.events.filterIsInstance<Loot>()) {
            val c = stay.corpses.filter { it.lootedAt == Int.MAX_VALUE && it.type.equals(e.type, ignoreCase = true) }.minBy { it.dist(e.x, e.y, e.z) }
            c.lootedAt = e.tick
        }
    }

    @Test
    fun everyStayConfirmsOnlyTrueCorpsesAndEachOneInRange() {
        assertTrue(stays.isNotEmpty())
        val report = ArrayList<String>()
        val errors = ArrayList<String>()
        for (stay in stays) {
            labelLoots(stay)
            val spots = data.spots(stay.layout)!!
            val solver = CorpseLocatorSolver(spots, stay.layout in data.fixed)
            val inRange = HashSet<Corpse>()
            val firstTarget = HashMap<Corpse, Int>()
            val confirmedAfter = HashMap<Corpse, Int>()
            val attributed = HashMap<Corpse, Int>()
            var dings = 0
            var player = doubleArrayOf(0.0, 0.0, 0.0)
            var target: Corpse? = null
            for (e in stay.events) {
                when (e) {
                    is Pos -> player = doubleArrayOf(e.x, e.y, e.z)
                    is Ding -> {
                        val n = assertNotNull(CorpseLocatorSolver.pitchByte(e.pitch), "pitch ${e.pitch} is a byte")
                        val fit = assertNotNull(solver.ding(e.ix, e.iy, e.iz, n))
                        dings++
                        player = doubleArrayOf(e.ix / 8.0, e.iy / 8.0, e.iz / 8.0)
                        val unlooted = stay.corpses.filter { it.lootedAt > e.tick }
                        target = sticky(target, unlooted, player)
                        val t = assertNotNull(target, "tick ${e.tick}: a corpse within 20")
                        assertTrue(t.spot in fit.fitting, "tick ${e.tick}: the talisman's target ${t.spot} fits (${fit.lo}..${fit.hi})")
                        firstTarget.putIfAbsent(t, dings)
                        for (s in solver.shown()) {
                            val c = unlooted.firstOrNull { it.spot == s }
                            assertNotNull(c, "tick ${e.tick}: confirmed $s is no unlooted corpse")
                            confirmedAfter.putIfAbsent(c, dings)
                            if (c.approx) continue
                            assertTrue(solver.possibleDistance(s, c.x, c.y, c.z) <= CorpseLocatorSolver.SLACK + 1e-9, "tick ${e.tick}: true stand left $s's region")
                            if (c === t) {
                                val k = (attributed[c] ?: 0) + 1
                                attributed[c] = k
                                val at = solver.center(s)
                                if (k in listOf(1, 2, 3, 5, 10, 20, 40)) {
                                    errors += "${stay.layout} ${c.type}@$k:${"%.2f".format(c.dist(at[0], at[1], at[2]))}${if (solver.position(s) != null) "*" else ""}"
                                }
                            }
                        }
                        for (c in unlooted) assertTrue(c.spot in solver.shown() || c !in confirmedAfter, "tick ${e.tick}: located ${c.spot} disappeared")
                    }
                    is Loot -> {
                        val truth = stay.corpses.single { it.lootedAt == e.tick }
                        assertEquals(truth.spot, solver.loot(e.x, e.y, e.z), "tick ${e.tick}: loot attributed")
                        assertTrue(truth.spot !in solver.shown())
                    }
                    is Tab -> assertEquals(e.looted, solver.looted.size, "tick ${e.tick}: tab LOOTED count")
                }
                for (c in stay.corpses) if (c.lootedAt > e.tick && c.dist(player[0], player[1], player[2]) <= CorpseLocatorSolver.RANGE) inRange += c
            }
            for (c in inRange) {
                val at = assertNotNull(confirmedAfter[c], "${stay.layout}: ${c.type} at ${c.spot} came within 20 and was never confirmed")
                report += "${stay.layout} ${c.type} ${c.spot}: ${at - firstTarget.getValue(c) + 1} ding(s)"
            }
        }
        println("dings until confirmed (from the first ding that measured it): $report")
        println("estimate error (corpse@dings it was measured since confirmed: centroid error, * = drawn there): $errors")
    }

    /**
     * The talisman's target as observed: it keeps its corpse until that one is beyond 20 blocks, then
     * takes the nearest unlooted one (AMET_1 below contradicts "always the nearest").
     */
    private fun sticky(target: Corpse?, unlooted: List<Corpse>, p: DoubleArray): Corpse? {
        val near = unlooted.filter { it.dist(p[0], p[1], p[2]) <= CorpseLocatorSolver.RANGE + 0.25 }
        if (target != null && target in near) return target
        return near.minByOrNull { it.dist(p[0], p[1], p[2]) }
    }

    /**
     * AMET_1, 1123-1133 s of recording mineshaft-corpses-2: eleven dings measure the Umber (~17.1)
     * while the Tungsten is ~15 away. The old rule took that as proof the confirmed Tungsten spot was
     * empty and dropped it (the owner then heard its dings with nothing shown); now it stays.
     */
    @Test
    fun theAmet1TungstenStaysShownWhileTheTalismanMeasuresTheUmber() {
        val stay = stays.first { it.layout == "AMET_1" && it.corpses.any { c -> c.type == "Tungsten" } }
        labelLoots(stay)
        val tungsten = stay.corpses.single { it.type == "Tungsten" }
        val umber = stay.corpses.single { it.type == "Umber" }
        val solver = CorpseLocatorSolver(data.spots("AMET_1")!!)
        var notNearest = 0
        var located = false
        for (e in stay.events.filterIsInstance<Ding>()) {
            val fit = assertNotNull(solver.ding(e.ix, e.iy, e.iz, CorpseLocatorSolver.pitchByte(e.pitch)!!))
            val p = doubleArrayOf(e.ix / 8.0, e.iy / 8.0, e.iz / 8.0)
            if (tungsten.dist(p[0], p[1], p[2]) < umber.dist(p[0], p[1], p[2]) - 1 && tungsten.spot !in fit.fitting) {
                notNearest++
                assertEquals(listOf(umber.spot), fit.fitting, "tick ${e.tick}: the farther Umber is measured")
            }
            located = located || tungsten.spot in solver.shown()
            if (located) assertTrue(tungsten.spot in solver.shown(), "tick ${e.tick}: the Tungsten stays shown")
        }
        assertEquals(11, notNearest)
        assertEquals(setOf(tungsten.spot, umber.spot), solver.shown().toSet())
    }

    /**
     * RUBY_C (recording mineshaft-corpses-4): the owner's "broken" shaft. The source data never named
     * it, so 0.3.2-beta.2 had no spots and played all 31 dings. With the shared crystal list the
     * Tungsten is located from the dings and its dings are muted while he stands next to it.
     */
    @Test
    fun theRubyCStayLocatesFromTheDingsWithTheSharedCrystalList() {
        val stay = stays.single { it.layout == "RUBY_C" }
        labelLoots(stay)
        val tungsten = stay.corpses.single { it.type == "Tungsten" }
        val solver = CorpseLocatorSolver(assertNotNull(data.spots("RUBY_C")))
        var dings = 0
        var confirmedAt = -1
        var muted = 0
        for (e in stay.events) {
            if (e is Loot) solver.loot(e.x, e.y, e.z)
            if (e !is Ding) continue
            val fit = assertNotNull(solver.ding(e.ix, e.iy, e.iz, CorpseLocatorSolver.pitchByte(e.pitch)!!))
            dings++
            if (confirmedAt < 0 && tungsten.spot in solver.shown()) confirmedAt = dings
            if (CorpseLocatorSolver.mutes(fit, locatorOn = true, muteOn = true)) muted++
        }
        assertTrue(confirmedAt > 0, "the Tungsten is located")
        assertEquals(listOf(tungsten.spot), solver.shown())
        println("RUBY_C: $dings dings, Tungsten located at ding $confirmedAt, $muted muted")
        assertTrue(muted >= dings - confirmedAt - 5, "its dings are muted once located")
    }

    @Test
    fun theLootedLapisNeverComesBack() {
        // The ding at tick 1525 (byte 65) fits the looted first Lapis and the Umber: only the Umber may show.
        val stay = stays.first { it.layout == "AMBE_1" }
        labelLoots(stay)
        val solver = CorpseLocatorSolver(data.spots("AMBE_1")!!)
        for (e in stay.events) {
            if (e is Ding) solver.ding(e.ix, e.iy, e.iz, CorpseLocatorSolver.pitchByte(e.pitch)!!)
            if (e is Loot) solver.loot(e.x, e.y, e.z)
            if (e.tick == 1525) break
        }
        val lapis = stay.corpses.first { it.type == "Lapis" && it.lootedAt == 716 }.spot
        assertTrue(lapis !in solver.shown() && lapis in solver.looted)
        assertEquals(listOf(stay.corpses.first { it.type == "Umber" }.spot), solver.shown())
    }
}
