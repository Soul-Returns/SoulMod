package com.soulreturns.features.dungeons.devices

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.zip.GZIPInputStream
import kotlin.math.sqrt

/**
 * Replays a Simon Says fixture (`tools/devbridge/ss-fixtures`, a real F7 recording cut to the
 * device) through a [SimonSaysModel] the way the live glue does: the lamp and button masks after
 * every packet, the pling / error sounds near the grid, and the player's own presses.
 *
 * The recordings hold no outbound packets; a press is known from the server's answer to it, which
 * arrives one round trip after the click. [latencyMs] moves every press that far back in time, so
 * the model sees it where the client did (before the lamps and sounds it caused).
 */
class SimonSaysReplay(
    name: String,
    private val latencyMs: Long = 120,
    private val blockOn: Boolean = true,
    /** Called before every recorded press; a non-null target is pressed 1 ms before it (an extra click). */
    private val extra: ((Press) -> Int?)? = null,
    /** Events before this tick are dropped: the client arrives (or loads the chunk) mid-way. */
    private val fromTick: Long = Long.MIN_VALUE,
) {
    data class Press(val t: Long, val tick: Long, val target: Int, val start: Boolean)
    /** [visibleTick] / [visibleMs]: when the highlight for this press's button became visible (arrival), null = it was not shown at the click. */
    data class Outcome(
        val press: Press, val decision: SimonSaysModel.Decision, val expected: Int?, val skip: Boolean, val injected: Boolean,
        val visibleTick: Long? = null, val visibleMs: Long? = null,
    )

    private class Event(val t: Long, val tick: Long, val o: JsonObject, val press: Boolean)

    val logs = ArrayList<String>()
    val model = SimonSaysModel { logs += it }
    val outcomes = ArrayList<Outcome>()
    /** Every round the model decided: (tick, solution). */
    val rounds = ArrayList<Pair<Long, List<Int>>>()
    val chat = ArrayList<Long>()

    private var segTarget: Int? = null
    private var segStartMs = 0L
    private var segStartTick = 0L
    private var lastWall: List<SimonSaysModel.WallCell> = emptyList()

    /** A change of what the lamp wall shows, with whether the round was already decided (buttons phase) then. */
    data class WallSeen(val tick: Long, val ms: Long, val cells: List<SimonSaysModel.WallCell>, val decided: Boolean)
    val walls = ArrayList<WallSeen>()

    private val lamps = BooleanArray(16)
    private val buttons = BooleanArray(16)

    init {
        val stream = SimonSaysReplay::class.java.getResourceAsStream("/devices/$name.ndjson.gz") ?: error("fixture $name missing")
        val events = ArrayList<Event>()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = Json.parseToJsonElement(line).jsonObject
                val press = o["k"]!!.jsonPrimitive.content == "press"
                val t = o["t"]!!.jsonPrimitive.long - if (press) latencyMs else 0
                events += Event(t, o["tick"]!!.jsonPrimitive.long, o, press)
            }
        }
        events.sortWith(compareBy<Event> { it.t }.thenBy { if (it.press) 0 else 1 })
        var i = 0
        while (i < events.size) {
            val e = events[i]
            if (e.tick < fromTick) {
                // Not seen live; the world still holds the result when the client gets there.
                if (e.o["k"]!!.jsonPrimitive.content == "block") apply(e)
                i++
                continue
            }
            if (e.press) {
                press(e)
                i++
                continue
            }
            // One packet (same arrival time) at a time, like one client-thread pass.
            var j = i
            while (j < events.size && events[j].t == e.t && !events[j].press) {
                apply(events[j])
                j++
            }
            model.check(e.t, e.t)
            track(e.t, e.tick)
            val before = model.solution
            model.observe(e.t, mask(lamps), mask(buttons))
            val after = model.solution
            if (after != null && after !== before) rounds += e.tick to after
            track(e.t, e.tick)
            i = j
        }
    }

    private fun apply(e: Event) {
        val o = e.o
        when (o["k"]!!.jsonPrimitive.content) {
            "block" -> {
                val x = o["x"]!!.jsonPrimitive.int
                val y = o["y"]!!.jsonPrimitive.int
                val z = o["z"]!!.jsonPrimitive.int
                if (y !in 120..123 || z !in 92..95) return
                val idx = SimonSaysModel.index(y, z)
                val s = o["s"]!!.jsonPrimitive.content
                if (x == 111) lamps[idx] = s == "sea_lantern"
                if (x == 110) buttons[idx] = s == "stone_button"
            }
            "sound" -> {
                val d = sqrt(sq(o.d("x") - 110.0) + sq(o.d("y") - 121.5) + sq(o.d("z") - 93.5))
                if (d > SimonSays.SOUND_RANGE) return
                if (o["id"]!!.jsonPrimitive.content == "pling") model.pling(e.t) else model.error(e.t)
                track(e.t, e.tick)
            }
            "chat" -> chat += e.tick
        }
    }

    private fun press(e: Event) {
        val y = e.o["y"]!!.jsonPrimitive.int
        val z = e.o["z"]!!.jsonPrimitive.int
        val start = y == 121 && z == 91
        val p = Press(e.t, e.tick, if (start) -1 else SimonSaysModel.index(y, z), start)
        extra?.invoke(p)?.let { send(Press(p.t - 1, p.tick, it, it < 0), injected = true) }
        send(p, injected = false)
    }

    private fun send(p: Press, injected: Boolean) {
        model.check(p.t, p.t)
        track(p.t, p.tick)
        val expected = model.next()
        // Visible = the button this press hits was shown as next (and existed) at the click.
        val visible = segTarget == p.target && !p.start
        val d = if (p.start) model.startPress(p.t, blockOn) else model.buttonPress(p.t, p.target, blockOn)
        outcomes += Outcome(p, d, expected, model.skip, injected, if (visible) segStartTick else null, if (visible) segStartMs else null)
        track(p.t, p.tick)
    }

    /** The highlight segment: the button the model shows as next (and that exists), since when; and every change of the lamp wall tint. */
    private fun track(t: Long, tick: Long) {
        val n = model.next()?.takeIf { model.buttonUp(it) }
        if (n != segTarget) {
            segTarget = n
            segStartMs = t
            segStartTick = tick
        }
        val cells = model.wall(t, true)
        if (cells.isNotEmpty() && cells != lastWall) walls += WallSeen(tick, t, cells, model.solution != null)
        lastWall = cells
    }

    private fun mask(a: BooleanArray): Int {
        var m = 0
        for (i in a.indices) if (a[i]) m = m or (1 shl i)
        return m
    }

    private fun sq(v: Double) = v * v
    private fun JsonObject.d(k: String) = this[k]!!.jsonPrimitive.double

    val buttonPresses get() = outcomes.filter { !it.press.start && !it.injected }
    val startPresses get() = outcomes.filter { it.press.start && !it.injected }
    val blocked get() = outcomes.filter { it.decision.blocked }
}
