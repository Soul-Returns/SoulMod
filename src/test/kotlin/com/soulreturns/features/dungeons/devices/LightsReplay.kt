package com.soulreturns.features.dungeons.devices

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.zip.GZIPInputStream

/**
 * Replays a Lights fixture (`tools/devbridge/lights-fixtures`, a real F7/M7 recording cut to the grid)
 * through a [LightsModel] the way [Lights] does: the lever and lamp masks after every packet, the
 * `completed a device!` lines, and the player's own flips.
 *
 * The F7 recordings hold no outbound packets; a flip is known from the `block.lever.click` the server plays
 * at the lever, one round trip after the click. [latencyMs] moves every flip that far back in time, so
 * the model sees it where the client did (before the lever and lamps it changed).
 */
class LightsReplay(
    name: String,
    private val latencyMs: Long = 120,
    private val blockOn: Boolean = true,
    /** Called before every recorded flip; a non-null cell is clicked 1 ms before it (an extra click). */
    private val extra: ((Click) -> Int?)? = null,
    /** Events before this tick are dropped: the client arrives (or loads the chunk) mid-way. */
    private val fromTick: Long = Long.MIN_VALUE,
    /** The recorded flips are the player's own; false = a teammate's (party runs): only [extra] clicks, at the flip's arrival. */
    private val ownFlips: Boolean = true,
) {
    data class Click(val t: Long, val tick: Long, val cell: Int)
    data class Outcome(val click: Click, val decision: LightsModel.Decision, val remainingBefore: List<Int>, val injected: Boolean)
    data class State(val preFinished: Boolean, val done: Boolean, val remaining: List<Int>)

    private class Event(val t: Long, val tick: Long, val o: JsonObject, val click: Boolean)

    val logs = ArrayList<String>()
    val model = LightsModel { logs += it }
    val outcomes = ArrayList<Outcome>()
    val chat = ArrayList<Long>()
    val plings = ArrayList<Long>()
    /** The model just before every recorded flip arrived (own or a teammate's). */
    val beforeFlips = ArrayList<State>()

    private var levers = 0
    private var lamps = 0

    init {
        val stream = LightsReplay::class.java.getResourceAsStream("/devices/$name.ndjson.gz") ?: error("fixture $name missing")
        val events = ArrayList<Event>()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = Json.parseToJsonElement(line).jsonObject
                val click = o["k"]!!.jsonPrimitive.content == "flip"
                events += Event(o["t"]!!.jsonPrimitive.long - if (click && ownFlips) latencyMs else 0, o["tick"]!!.jsonPrimitive.long, o, click)
            }
        }
        events.sortWith(compareBy<Event> { it.t }.thenBy { if (it.click) 0 else 1 })
        // The device starts with every lever off and every lamp dark (three world captures), long before the fixture's first event.
        if (fromTick == Long.MIN_VALUE) model.observe(0, 0, events.firstOrNull()?.t ?: 0)
        var joined = fromTick == Long.MIN_VALUE
        var i = 0
        while (i < events.size) {
            val e = events[i]
            if (e.tick < fromTick) {
                if (e.o["k"]!!.jsonPrimitive.content == "block") apply(e)
                i++
                continue
            }
            if (!joined) {
                // The client reads the world on arrival: what the skipped packets left.
                model.observe(levers, lamps, e.t)
                joined = true
            }
            if (e.click) {
                click(e)
                i++
                continue
            }
            var j = i
            while (j < events.size && events[j].t == e.t && !events[j].click) {
                apply(events[j])
                j++
            }
            model.check(e.t)
            model.observe(levers, lamps, e.t)
            i = j
        }
    }

    private fun apply(e: Event) {
        val o = e.o
        when (o["k"]!!.jsonPrimitive.content) {
            "block" -> {
                val idx = LightsModel.index(o["x"]!!.jsonPrimitive.int, o["y"]!!.jsonPrimitive.int)
                val on = o["on"]!!.jsonPrimitive.boolean
                val bit = 1 shl idx
                if (o["s"]!!.jsonPrimitive.content == "lever") levers = if (on) levers or bit else levers and bit.inv()
                else lamps = if (on) lamps or bit else lamps and bit.inv()
            }
            "chat" -> {
                chat += e.tick
                model.completion(e.t)
            }
            "pling" -> plings += e.tick
        }
    }

    private fun click(e: Event) {
        val cell = LightsModel.index(e.o["x"]!!.jsonPrimitive.int, e.o["y"]!!.jsonPrimitive.int)
        val c = Click(e.t, e.tick, cell)
        beforeFlips += State(model.preFinished, model.done, model.remaining())
        extra?.invoke(c)?.let { send(Click(c.t - 1, c.tick, it), injected = true) }
        if (ownFlips) send(c, injected = false)
    }

    private fun send(c: Click, injected: Boolean) {
        model.check(c.t)
        val before = model.remaining()
        outcomes += Outcome(c, model.leverClick(c.t, c.cell, blockOn), before, injected)
    }

    val clicks get() = outcomes.filter { !it.injected }
    val blocked get() = outcomes.filter { it.decision.blocked }
}
