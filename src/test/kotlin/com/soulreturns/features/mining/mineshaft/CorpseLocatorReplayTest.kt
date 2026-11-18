package com.soulreturns.features.mining.mineshaft

import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Replay of every mineshaft stay in recording 2026-11-18_02-55-36_mineshaft-corpses
 * (`mineshaft/corpses-2026-11-18.txt`). The corpse stand positions in the fixture are the ground
 * truth for this test only; the solver sees the dings, the loot lines and the player position.
 */
class CorpseLocatorReplayTest {
    private class Corpse(val type: String, val x: Double, val y: Double, val z: Double) {
        val spot = CorpseSpot(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())
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
            javaClass.getResource("/mineshaft/corpses-2026-11-18.txt")!!.readText().lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .forEach { line ->
                    val f = line.split(' ')
                    val stay = out.lastOrNull()
                    when (f[0]) {
                        "stay" -> out += Stay(f[1])
                        "corpse" -> stay!!.corpses += Corpse(f[1], f[2].toDouble(), f[3].toDouble(), f[4].toDouble())
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

    /** Ground truth for loots: the corpse of that type nearest the player when the line came. */
    private fun labelLoots(stay: Stay) {
        for (e in stay.events.filterIsInstance<Loot>()) {
            val c = stay.corpses.filter { it.lootedAt == Int.MAX_VALUE && it.type.equals(e.type, ignoreCase = true) }.minBy { it.dist(e.x, e.y, e.z) }
            c.lootedAt = e.tick
        }
    }

    @Test
    fun everyStayConfirmsOnlyTrueCorpsesAndEachOneInRange() {
        assertTrue(stays.isNotEmpty())
        val report = ArrayList<String>()
        for (stay in stays) {
            labelLoots(stay)
            val spots = assertNotNull(data.spots(stay.layout), "layout ${stay.layout} in the data")
            for (c in stay.corpses) assertTrue(c.spot in spots, "${stay.layout}: corpse at ${c.spot} is a listed spot")
            val solver = CorpseLocatorSolver(spots, stay.layout in data.fixed)
            val inRange = HashSet<Corpse>()
            val firstNearest = HashMap<Corpse, Int>()
            val confirmedAfter = HashMap<Corpse, Int>()
            var dings = 0
            var player = doubleArrayOf(0.0, 0.0, 0.0)
            for (e in stay.events) {
                when (e) {
                    is Pos -> player = doubleArrayOf(e.x, e.y, e.z)
                    is Ding -> {
                        val n = assertNotNull(CorpseLocatorSolver.pitchByte(e.pitch), "pitch ${e.pitch} is a byte")
                        val fit = assertNotNull(solver.ding(e.ix, e.iy, e.iz, n))
                        dings++
                        player = doubleArrayOf(e.ix / 8.0, e.iy / 8.0, e.iz / 8.0)
                        val unlooted = stay.corpses.filter { it.lootedAt > e.tick }
                        val nearest = unlooted.minBy { it.dist(player[0], player[1], player[2]) }
                        assertTrue(nearest.spot in fit.fitting, "tick ${e.tick}: true spot ${nearest.spot} fits (${fit.lo}..${fit.hi})")
                        firstNearest.putIfAbsent(nearest, dings)
                        for (s in solver.shown()) {
                            val c = unlooted.firstOrNull { it.spot == s }
                            assertNotNull(c, "tick ${e.tick}: confirmed $s is no unlooted corpse")
                            confirmedAfter.putIfAbsent(c, dings)
                        }
                        assertTrue(solver.emptied.isEmpty(), "tick ${e.tick}: retracted ${solver.emptied}")
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
                report += "${stay.layout} ${c.type} ${c.spot}: ${at - firstNearest.getValue(c) + 1} ding(s)"
            }
        }
        println("dings until confirmed (from the first ding it was the nearest): $report")
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
