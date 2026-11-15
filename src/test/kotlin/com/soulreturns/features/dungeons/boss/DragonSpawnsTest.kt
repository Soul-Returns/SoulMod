package com.soulreturns.features.dungeons.boss

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DragonSpawnsTest {
    @Test
    fun firstPulseStartsTheCountdownAndTheAddEndsIt() {
        val s = DragonSpawns()
        for (i in 0 until 10) s.particle(true, 20, 27.0, 19.0, 59.0, 1000.0 + 10 * i)
        s.particle(true, 20, 85.0, 19.0, 56.0, 1060.0)
        s.particle(false, 20, 85.0, 19.0, 56.0, 1061.0)
        s.particle(true, 40, 85.0, 19.0, 56.0, 1062.0)
        val p = s.pending(1095.0)
        assertEquals(listOf(Dragon.RED, Dragon.ORANGE), p.map { it.dragon }, "soonest first")
        assertEquals(1099.0, p[0].spawnAt)
        s.retime(Dragon.ORANGE, 1060.0, 1058.5)
        assertEquals(1157.5, p[1].spawnAt, "re-read between the set_time packets")
        s.dragon(27.0, 59.0)
        assertEquals(listOf(Dragon.ORANGE), s.pending(1100.0).map { it.dragon })
        assertEquals(emptyList(), s.pending(1180.0).map { it.dragon }, "no add 20 ticks after its tick: dropped")
        // A dragon whose statue survived comes back with a new countdown (M7 run 7's Red).
        s.particle(true, 20, 27.0, 19.0, 59.0, 1400.0)
        assertEquals(1499.0, s.pending(1400.0).single().spawnAt)
    }

    @Test
    fun allFortyOneDragonsOfTheM7RunsSpawnOnTheirCountdown() {
        var spawns = 0
        val offs = ArrayList<Double>()
        for (n in 1..8) {
            val s = DragonSpawns()
            val waiting = HashMap<Dragon, DragonSpawns.Pending>()
            for (e in lateFixture("m7-$n-late")) {
                val t = e.tick ?: continue
                if (e.k == "pulse") {
                    val d = Dragon.at(e.x, e.z, 0.01) ?: continue
                    val fresh = s.pending(t.toDouble()).none { it.dragon === d }
                    s.particle(true, DragonSpawns.PULSE_COUNT, e.x, e.y, e.z, t.toDouble())
                    // As the live glue: the first pulse is read again once the next set_time arrived.
                    if (fresh) e.exact?.let { s.retime(d, t.toDouble(), it) }
                    s.pending(t.toDouble()).firstOrNull { it.dragon === d }?.let { waiting.putIfAbsent(d, it) }
                } else if (e.k == "dragon") {
                    val d = Dragon.at(e.x, e.z, DragonSpawns.ADD_WITHIN) ?: continue
                    val p = waiting.remove(d) ?: continue
                    spawns++
                    s.dragon(e.x, e.z)
                    // M5 Orange was added 4 b off its spawn and late (tracking): its countdown is not valid (doc §3.2).
                    if (!(n == 5 && d === Dragon.ORANGE)) offs += t - p.spawnAt
                }
            }
        }
        assertEquals(41, spawns, "every spawn of the 8 runs")
        val sorted = offs.sorted()
        // The add's live tick against the countdown's 0 (40 valid spawns): 34 within 2 ticks, the rest at stalls
        // (the live clock extrapolates the add); see the dungeons rule.
        assertTrue(offs.count { abs(it) <= 2.0 } >= 34 && offs.all { abs(it) <= TOLERANCE } && abs(sorted[sorted.size / 2]) <= 1.0, "offsets $sorted")
    }

    @Test
    fun onTheTickClockEveryDragonSpawnsWithinOneTickOfTheCountdown() {
        // M7 2026-11-13 runs 1–3 (runs 4–6 lost their particles: the recorder shed the type), lone-ping clock: the first
        // pulse → add is 98, 99 or 100 ticks, never a constant, so the countdown's 0 (first pulse + 99) is within a tick.
        val offs = ArrayList<Long>()
        for (n in 1..3) {
            val s = DragonSpawns()
            val first = HashMap<Dragon, Long>()
            for (e in lateFixture("m7r2-$n-late")) {
                val t = e.tick ?: continue
                if (e.k == "pulse") {
                    val d = Dragon.at(e.x, e.z, 0.01) ?: continue
                    if (s.pending(t.toDouble()).none { it.dragon === d }) first[d] = t
                    s.particle(true, DragonSpawns.PULSE_COUNT, e.x, e.y, e.z, t.toDouble())
                } else if (e.k == "dragon") {
                    val d = Dragon.at(e.x, e.z, DragonSpawns.ADD_WITHIN) ?: continue
                    val p = first.remove(d) ?: continue
                    offs += t - (p + DragonSpawns.SPAWN_AFTER.toLong())
                    s.dragon(e.x, e.z)
                    assertTrue(s.pending(t.toDouble()).none { it.dragon === d }, "the add ends $n ${d.name}'s countdown")
                }
            }
        }
        assertEquals(19, offs.size, "spawns with their first pulse recorded")
        assertTrue(offs.all { it in -1L..1L }, "offsets $offs")
        assertEquals(mapOf(-1L to 4, 0L to 8, 1L to 7), offs.groupingBy { it }.eachCount(), "offsets $offs")
    }

    private companion object {
        const val TOLERANCE = 6.0
    }
}
