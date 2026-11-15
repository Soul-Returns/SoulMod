package com.soulreturns.features.dungeons.devices

import com.soulreturns.features.dungeons.devices.SharpshooterModel.Companion.ALL
import com.soulreturns.features.dungeons.devices.SharpshooterModel.Companion.index
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sharpshooter on the user's own F7 recordings (`docs/research/f7-devices-2026-11-12.md` §12), cut by
 * `tools/devbridge/sharp-fixtures` into `src/test/resources/devices/`: run1, run2, run3, storm, both ring fights
 * and the places recording, 7 or 8 targets each.
 */
class SharpshooterFixturesTest {
    private val fixtures = listOf("sharp-run1", "sharp-run2", "sharp-run3", "sharp-storm", "sharp-ring1", "sharp-ring2", "sharp-places")

    private class Ev(val t: Long, val tick: Long, val kind: String, val cell: Int, val v: Int)

    private class Snap(val t: Long, val current: Int, val hit: Int, val plate: Int)

    private fun load(name: String): List<Ev> {
        val stream = SharpshooterFixturesTest::class.java.getResourceAsStream("/devices/$name.ndjson.gz") ?: error("fixture $name missing")
        val out = ArrayList<Ev>()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = Json.parseToJsonElement(line).jsonObject
                val t = o["t"]!!.jsonPrimitive.long
                val tick = o["tick"]!!.jsonPrimitive.long
                when (val k = o["k"]!!.jsonPrimitive.content) {
                    "cell" -> {
                        val cell = index(o["x"]!!.jsonPrimitive.int, o["y"]!!.jsonPrimitive.int)
                        assertTrue(cell >= 0, "$name: not a cell: $line")
                        out += Ev(t, tick, k, cell, if (o["s"]!!.jsonPrimitive.content == "emerald") 1 else 0)
                    }
                    "plate" -> out += Ev(t, tick, k, -1, o["p"]!!.jsonPrimitive.int)
                    else -> out += Ev(t, tick, k, -1, 0)
                }
            }
        }
        return out.sortedBy { it.t }
    }

    /**
     * Plays [name] into a model the way [Sharpshooter] does: a look at the nine cells and the plate every
     * [stepMs] of client time (0 = after every event, -1 = after every server tick's events (a client applies a
     * tick's packets together), both miss nothing), all cells blue and the plate
     * free before the first event. Returns the snapshots and the model's log.
     */
    private fun replay(name: String, stepMs: Long, from: Long = Long.MIN_VALUE): Pair<List<Snap>, List<String>> {
        val events = load(name)
        val logs = ArrayList<String>()
        val model = SharpshooterModel { logs += it }
        val snaps = ArrayList<Snap>()
        var emerald = 0
        var plate = 0
        fun look(t: Long) {
            if (t < from) return
            model.observe(emerald, plate)
            snaps += Snap(t, model.current, model.hit, plate)
        }
        fun apply(e: Ev) {
            when (e.kind) {
                "cell" -> emerald = if (e.v == 1) emerald or (1 shl e.cell) else emerald and (1 shl e.cell).inv()
                "plate" -> plate = e.v
            }
        }
        if (stepMs == -1L) {
            look(events.first().t - 1)
            var i = 0
            while (i < events.size) {
                val tick = events[i].tick
                while (i < events.size && events[i].tick == tick) apply(events[i++])
                look(events[i - 1].t)
            }
        } else if (stepMs == 0L) {
            look(events.first().t - 1)
            for (e in events) {
                apply(e)
                look(e.t)
            }
        } else {
            var t = events.first().t - stepMs
            var i = 0
            while (i < events.size || t < events.last().t + 2 * stepMs) {
                t += stepMs
                while (i < events.size && events[i].t <= t) apply(events[i++])
                look(t)
            }
        }
        return snaps to logs
    }

    /** The recording's own truth: the cells that went emerald then blue, in order. */
    private fun recordedHits(name: String): List<Int> {
        val order = ArrayList<Int>()
        val up = HashSet<Int>()
        for (e in load(name)) {
            if (e.kind != "cell") continue
            if (e.v == 1) up += e.cell else if (up.remove(e.cell)) order += e.cell
        }
        return order
    }

    @Test
    fun everyRunShowsExactlyOneTargetAtATimeAndNeverTheSameCellTwice() {
        for (f in fixtures) {
            val hits = recordedHits(f)
            assertEquals(hits.toSet().size, hits.size, "$f: a cell was the target twice: $hits")
            assertTrue(hits.size in 7..8, "$f: ${hits.size} targets")
            val (snaps, _) = replay(f, -1)
            for (s in snaps) assertTrue(Integer.bitCount(s.current) <= 1, "$f @${s.t}: ${Integer.bitCount(s.current)} targets at once")
        }
    }

    @Test
    fun theTargetShownIsTheEmeraldBlockAtEveryLook() {
        for (f in fixtures) {
            val events = load(f)
            val (snaps, _) = replay(f, 0)
            var emerald = 0
            var k = 0
            // snaps[0] is the look before the first event, then one per event.
            assertEquals(0, snaps[0].current, f)
            for (e in events) {
                if (e.kind == "cell") emerald = if (e.v == 1) emerald or (1 shl e.cell) else emerald and (1 shl e.cell).inv()
                k++
                assertEquals(emerald, snaps[k].current, "$f @${e.t}")
            }
        }
    }

    @Test
    fun aPerfectWatcherMarksEveryRecordedHitInOrderAndForgetsThemWhenThePlateIsFree() {
        for (f in fixtures) {
            val recorded = recordedHits(f)
            val (snaps, logs) = replay(f, 0)
            val peak = snaps.maxOf { Integer.bitCount(it.hit) }
            assertEquals(recorded.size, peak, "$f: $logs")
            val maskOfAll = recorded.fold(0) { m, c -> m or (1 shl c) }
            assertTrue(snaps.any { it.hit == maskOfAll }, "$f: never had all ${recorded.size} marked")
            // The marks only grow while the plate is pressed.
            var prev = 0
            for (s in snaps) {
                if (s.plate == 0) {
                    assertEquals(0, s.hit, "$f @${s.t}: marks kept with a free plate")
                } else {
                    assertEquals(prev, prev and s.hit, "$f @${s.t}: a mark vanished with the plate pressed")
                }
                prev = s.hit
            }
            assertEquals(0, snaps.last().hit, "$f: marks left after the plate went free")
        }
    }

    @Test
    fun theCandidatesAreTheCellsNotYetHitAndNotTheTarget() {
        for (f in fixtures) {
            val model = SharpshooterModel()
            var emerald = 0
            for (e in load(f)) {
                if (e.kind == "cell") emerald = if (e.v == 1) emerald or (1 shl e.cell) else emerald and (1 shl e.cell).inv()
                model.observe(emerald, if (e.kind == "plate") e.v else 1)
                assertEquals(ALL and model.hit.inv() and model.current.inv(), model.candidates, f)
                assertEquals(0, model.candidates and model.current, f)
            }
        }
    }

    @Test
    fun aWatcherAtTwentyHertzNeverMarksAWrongCellAndMissesAtMostTheTargetThatWasShotWithinOneTick() {
        for (f in fixtures) for (step in listOf(50L, 100L)) {
            val recorded = recordedHits(f)
            val (snaps, logs) = replay(f, step)
            val seen = snaps.fold(0) { m, s -> m or s.hit }
            val all = recorded.fold(0) { m, c -> m or (1 shl c) }
            assertEquals(0, seen and all.inv(), "$f @$step: marked a cell that was never the target")
            val missed = recorded.size - Integer.bitCount(seen)
            // A target that lived 1 server tick (places: 68,128, hit one tick after it appeared) can fall between two looks.
            assertTrue(missed <= if (f == "sharp-places") 1 else 0, "$f @$step: missed $missed: $logs")
        }
    }

    @Test
    fun joiningMidRunMarksOnlyWhatWasSeenAndStaysConsistent() {
        for (f in fixtures) {
            val events = load(f)
            val cut = events.first { it.kind == "cell" && it.v == 0 }.t + 1
            val (snaps, _) = replay(f, 0, from = cut)
            val first = snaps.first()
            assertEquals(0, first.hit, "$f: a hit credited on the first look")
            val recorded = recordedHits(f).fold(0) { m, c -> m or (1 shl c) }
            for (s in snaps) assertEquals(0, s.hit and recorded.inv(), "$f @${s.t}")
        }
    }

    @Test
    fun theHitsAreForgottenWhenThePlateIsPressedAgain() {
        val m = SharpshooterModel()
        m.observe(0, 0)
        m.observe(1 shl 4, 1)
        m.observe(0, 1)
        assertEquals(1 shl 4, m.hit)
        m.observe(0, 0)
        assertEquals(0, m.hit)
        m.observe(1 shl 2, 1)
        m.observe(0, 1)
        assertEquals(1 shl 2, m.hit)
    }

    @Test
    fun anEmeraldThatVanishesWithTheFreePlateIsNotAHit() {
        val m = SharpshooterModel()
        m.observe(1 shl 1, 1)
        m.observe(0, 0)
        assertEquals(0, m.hit)
        assertEquals(0, m.current)
    }

    @Test
    fun aRepeatedBlockUpdateIsNoHit() {
        val m = SharpshooterModel()
        m.observe(0, 1)
        m.observe(1 shl 3, 1)
        m.observe(1 shl 3, 1)
        assertEquals(0, m.hit)
        assertEquals(1 shl 3, m.current)
    }

    @Test
    fun aCellThatIsTheTargetAgainIsNoLongerMarkedHit() {
        val m = SharpshooterModel()
        m.observe(0, 1)
        m.observe(1 shl 5, 1)
        m.observe(0, 1)
        assertEquals(1 shl 5, m.hit)
        m.observe(1 shl 5, 1)
        assertEquals(0, m.hit)
        assertEquals(1 shl 5, m.current)
        m.observe(0, 1)
        assertEquals(1 shl 5, m.hit)
    }

    @Test
    fun cellsAreTheNineTargetBlocksAndNothingElse() {
        val seen = HashSet<Int>()
        for (x in 60..72) for (y in 124..132) {
            val i = index(x, y)
            if (i >= 0) {
                assertTrue(x % 2 == 0 && y % 2 == 0 && x in 64..68 && y in 126..130, "$x,$y")
                assertEquals(x, SharpshooterModel.x(i))
                assertEquals(y, SharpshooterModel.y(i))
                seen += i
            }
        }
        assertEquals(9, seen.size)
    }
}
