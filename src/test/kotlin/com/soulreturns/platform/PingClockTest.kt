package com.soulreturns.platform

import com.soulreturns.features.dungeons.boss.BossTimings
import com.soulreturns.features.dungeons.boss.GoldorSource
import com.soulreturns.features.dungeons.boss.StormModel
import com.soulreturns.features.dungeons.devices.GoldorPhaseModel
import com.google.gson.JsonParser
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lone-ping clock on the F7 solo recording of 2026-11-13 (`f7-pings`: lone pings, `set_time`, the boss and
 * counter lines and the collapse's falling blocks in arrival order, ms times), where the user watched our Goldor tick.
 */
class PingClockTest {
    private class Run {
        val clock = ServerClock()
        val ticks = mutableListOf<Pair<String, Long>>()
        var times = 0
        var pinging = 0
        var ahead = 0
        var behind = 0
        val model = StormModel(BossTimings.F7)
    }

    private fun run(): Run {
        val r = Run()
        var seq = 0L
        val stream = javaClass.getResourceAsStream("/boss/f7-pings.ndjson.gz") ?: error("missing fixture")
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (line in lines) {
                val o = JsonParser.parseString(line).asJsonObject
                val nanos = o["t"].asLong * 1_000_000 + seq++
                when (o["k"].asString) {
                    "ping" -> r.clock.onPing(nanos)
                    "time" -> {
                        val g = o["g"].asLong
                        r.clock.onSetTime(g, nanos)
                        r.times++
                        if (r.clock.pinging()) r.pinging++
                        val now = r.clock.now(nanos)!!
                        if (now > g) r.ahead++
                        if (now < g) r.behind++
                    }
                    "fall" -> r.clock.now(nanos)?.let { r.model.falling(100.5, 221.5, 42.5, it) }
                    else -> {
                        val tick = r.clock.now(nanos) ?: continue
                        val s = o["s"].asString
                        r.ticks += s to tick
                        r.model.chat(s, tick)
                    }
                }
            }
        }
        return r
    }

    @Test
    fun lonePingsAreTheTickClock() {
        val r = run()
        // Off only around joins and server switches, where the interval before has no matching ping count (9 of 388).
        assertTrue(r.pinging >= r.times * 0.97, "ping mode at ${r.pinging} of ${r.times} set_time packets")
        assertEquals(0, r.behind, "never behind a set_time packet")
        assertTrue(r.ahead <= 1, "ahead of a set_time packet ${r.ahead} times (the late one after a 4-ping burst)")
    }

    @Test
    fun goldorChecksSitOnWhoDaresMinusOne() {
        val r = run()
        val wd = r.ticks.first { it.first == StormModel.WHO_DARES }.second
        val zone = r.ticks.filter { it.first == StormModel.DEATH_ZONE }.map { it.second - wd }
        assertEquals(listOf(479L, 539L, 599L, 659L, 779L), zone)
        val ants = r.ticks.first { it.first.startsWith(StormModel.LITTLE_ANTS) }.second - wd
        assertEquals(62L, ants)
        val m = r.model
        assertEquals(GoldorSource.ANTS, m.goldorSource)
        for (c in m.goldorChecks) assertTrue(abs(c[1]) <= 1, "check WD+${c[0] - wd} off the grid by ${c[1]}")
        assertEquals(0L, Math.floorMod(m.goldorAnchor!! - (wd + GoldorPhaseModel.WD_TO_START), 60L))
    }
}
