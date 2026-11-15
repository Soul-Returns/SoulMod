package com.soulreturns.features.dungeons.devices

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.zip.GZIPInputStream

/**
 * Replays an Arrow Align fixture (`tools/devbridge/arrows-fixtures`, a real F7 recording cut to the device)
 * through an [ArrowAlignModel] the way [ArrowAlign] does: the frames (kind, rotation, lantern) after every
 * packet, and the player's own clicks.
 *
 * The recordings hold no outbound packets; a click is known from the `rotate_item` sound the server plays at
 * the frame, one round trip after the click. [latencyMs] moves every click that far back in time, so the model
 * sees it where the client did (before the echo of this click and possibly before the echo of the one before).
 *
 * [truthRot] is the rotation the server had when it handled the click, the oracle the decisions are checked
 * against: the last rotation the recording showed for the frame plus one for every click on it since (a burst of
 * clicks handled in one server tick is answered with one rotation update).
 */
class ArrowAlignReplay(
    name: String,
    private val latencyMs: Long = 120,
    private val blockOn: Boolean = true,
) {
    data class Click(val t: Long, val tick: Long?, val cell: Int)
    data class Outcome(
        val click: Click,
        val decision: ArrowAlignModel.Decision,
        /** Rotation the server had when it handled the click. */
        val truthRot: Int?,
        /** What the model showed as the clicks left for this frame just before the click. */
        val leftBefore: Int?,
        val target: Int?,
    ) {
        /** The click found the arrow already at its target: an over-rotation, whatever the toggle says. */
        val overRotation: Boolean get() = target != null && truthRot == target
    }

    private class Event(val t: Long, val order: Int, val o: JsonObject, val click: Boolean)

    val logs = ArrayList<String>()
    val model = ArrowAlignModel { logs += it }
    val outcomes = ArrayList<Outcome>()
    val chat = ArrayList<Long?>()

    /** Layouts the model had after the frames of the first look (before the first click). */
    var layoutsAtFirstClick: List<Int> = emptyList()
        private set

    private class F(val cell: Int, var item: String?, var rot: Int?)

    private val frames = HashMap<Int, F>()
    private val lantern = HashMap<Int, Boolean>()

    /** The recording as the server had it, by cell, for the oracle. */
    private val truth = HashMap<Int, Int>()
    private val truthFrames = HashMap<Int, F>()

    /** The [truthRot] of every click, in file order, from a first pass over the recording. */
    private val truthByClick = ArrayList<Int?>()

    init {
        val stream = ArrowAlignReplay::class.java.getResourceAsStream("/devices/$name.ndjson.gz") ?: error("fixture $name missing")
        val raw = ArrayList<JsonObject>()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (line in lines) if (line.isNotBlank()) raw += Json.parseToJsonElement(line).jsonObject
        }
        // Pass 1: the recording as the server had it, in file order, to read the oracle at every click.
        for (o in raw) {
            when (o["k"]!!.jsonPrimitive.content) {
                "add" -> truthFrames[o["id"]!!.jsonPrimitive.int] = F(ArrowAlignModel.index(o["y"]!!.jsonPrimitive.int, o["z"]!!.jsonPrimitive.int), null, null)
                "data" -> truthFrames[o["id"]!!.jsonPrimitive.int]?.let { f ->
                    o["item"]?.let { f.item = it.jsonPrimitive.content }
                    o["rot"]?.let { f.rot = it.jsonPrimitive.int }
                    f.rot?.let { truth[f.cell] = it }
                }
                "remove" -> truthFrames.remove(o["id"]!!.jsonPrimitive.int)
                "click" -> {
                    val cell = ArrowAlignModel.index(o["y"]!!.jsonPrimitive.int, o["z"]!!.jsonPrimitive.int)
                    val before = truth[cell]
                    truthByClick += before
                    if (before != null) truth[cell] = (before + 1) % 8
                }
            }
        }
        val events = ArrayList<Event>()
        for ((i, o) in raw.withIndex()) {
            val click = o["k"]!!.jsonPrimitive.content == "click"
            events += Event(o["t"]!!.jsonPrimitive.long - if (click) latencyMs else 0, i, o, click)
        }
        // Arrival order (file order) within one instant; clicks moved back by the latency sort in front of what really came before them.
        events.sortWith(compareBy<Event> { it.t }.thenBy { it.order })
        val clickOrder = raw.withIndex().filter { it.value["k"]!!.jsonPrimitive.content == "click" }.map { it.index }
        var seenClick = false
        var i = 0
        while (i < events.size) {
            val e = events[i]
            if (e.click) {
                if (!seenClick) {
                    seenClick = true
                    layoutsAtFirstClick = model.layouts
                }
                val cell = ArrowAlignModel.index(e.o["y"]!!.jsonPrimitive.int, e.o["z"]!!.jsonPrimitive.int)
                val k = clickOrder.indexOf(e.order)
                val click = Click(e.t, e.o["tick"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.long, cell)
                model.check(e.t)
                val left = model.clicksLeft(cell)
                val target = model.target(cell)
                val d = model.click(e.t, cell, blockOn)
                outcomes += Outcome(click, d, truthByClick[k], left, target)
                i++
                continue
            }
            // One packet instant (same arrival time) at a time, like one client-thread pass.
            var j = i
            while (j < events.size && events[j].t == e.t && !events[j].click) {
                apply(events[j].o)
                j++
            }
            model.check(e.t)
            model.observe(e.t, snapshot())
            i = j
        }
    }

    private fun apply(o: JsonObject) {
        when (o["k"]!!.jsonPrimitive.content) {
            "add" -> frames[o["id"]!!.jsonPrimitive.int] = F(ArrowAlignModel.index(o["y"]!!.jsonPrimitive.int, o["z"]!!.jsonPrimitive.int), null, null)
            "data" -> frames[o["id"]!!.jsonPrimitive.int]?.let { f ->
                o["item"]?.let { f.item = it.jsonPrimitive.content }
                o["rot"]?.let { f.rot = it.jsonPrimitive.int }
            }
            "remove" -> frames.remove(o["id"]!!.jsonPrimitive.int)
            "back" -> lantern[ArrowAlignModel.index(o["y"]!!.jsonPrimitive.int, o["z"]!!.jsonPrimitive.int)] = o["s"]!!.jsonPrimitive.content == "sea_lantern"
            "chat" -> chat += o["tick"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.long
        }
    }

    private fun snapshot(): List<ArrowAlignModel.Frame> = frames.values.filter { it.item != null }.map {
        val kind = when (it.item) {
            "arrow" -> ArrowAlignModel.ARROW
            "lime_wool" -> ArrowAlignModel.START
            "red_wool" -> ArrowAlignModel.END
            else -> ArrowAlignModel.OTHER
        }
        ArrowAlignModel.Frame(it.cell, kind, it.rot ?: 0, lantern[it.cell])
    }

    val blocked get() = outcomes.filter { it.decision.blocked }
}
