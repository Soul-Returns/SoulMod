package com.soulreturns.features.dungeons.boss

import com.soulreturns.platform.ServerClock
import com.soulreturns.util.MessageDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.zip.GZIPInputStream

/**
 * Replays a Storm fixture (`tools/devbridge/storm-fixtures`, a real F7 recording cut to the boss
 * signals) through a [ServerClock] fed only by the recorded `set_time` arrivals and a [StormModel]:
 * exactly what the live glue does, minus Minecraft. [probe] sees every whole tick once all events
 * before it are in (the HUD's view of that tick).
 */
class StormReplay(
    name: String,
    timings: BossTimings = BossTimings.F7,
    private val dropPathetic: Boolean = false,
    private val probe: ((StormModel, Long) -> Unit)? = null,
) {
    val clock = ServerClock()
    val logs = ArrayList<String>()
    val model = StormModel(timings) { logs += it }
    val results = ArrayList<CrushResult>()
    /** Every boss chat line with its tick. */
    val chat = ArrayList<Pair<Long, String>>()
    val titles = ArrayList<Pair<Long, String>>()
    /** (tick, padAnchor) after every event, for grid stability checks. */
    val anchors = ArrayList<Pair<Long, Long?>>()
    val staleTicks = ArrayList<Long>()
    /** Ticks a fireball entity was added (all of them, the opening flight's too). */
    val fireballs = ArrayList<Long>()
    /** The tick the `Pathetic Maxor` line arrived (also when it was withheld from the model). */
    var patheticTick: Long? = null
    private var probed: Long? = null

    private class Retime(val line: String, val tick: Long, val nanos: Long)

    private val retimes = ArrayList<Retime>()

    init {
        val stream = StormReplay::class.java.getResourceAsStream("/storm/$name.ndjson.gz") ?: error("fixture $name missing")
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                feed(Json.parseToJsonElement(line).jsonObject)
            }
        }
    }

    private fun feed(o: JsonObject) {
        val nanos = o["t"]!!.jsonPrimitive.long * 1_000_000L
        val k = o["k"]!!.jsonPrimitive.content
        if (k == "time") {
            clock.onSetTime(o["gt"]!!.jsonPrimitive.long, nanos)
            // As the live glue: lines that anchor a grid are read again between the packets around them.
            val it = retimes.iterator()
            while (it.hasNext()) {
                val r = it.next()
                val exact = clock.between(r.nanos) ?: continue
                model.retime(r.line, r.tick, exact.toLong())
                it.remove()
            }
            return
        }
        val tick = clock.now(nanos) ?: return
        probe?.let { f ->
            var t = probed ?: tick
            while (t < tick) f(model, t++)
            probed = t
        }
        val fresh = clock.fresh(nanos)
        if (!fresh) staleTicks += tick
        val before = model.latestResult
        when (k) {
            "chat" -> {
                val s = MessageDetector.stripColorCodes(o["s"]!!.jsonPrimitive.content)
                if (s == StormModel.PATHETIC) patheticTick = tick
                chat += tick to s
                if (!(dropPathetic && s == StormModel.PATHETIC)) {
                    model.chat(s, tick)
                    if (StormModel.retimed(s.trim())) retimes += Retime(s, tick, nanos)
                }
            }
            "title" -> {
                val s = MessageDetector.stripColorCodes(o["s"]!!.jsonPrimitive.content)
                titles += tick to s
                model.title(s, tick)
            }
            "sound" -> model.sound(
                o["id"]!!.jsonPrimitive.content, o.d("x"), o.d("y"), o.d("z"), o["v"]!!.jsonPrimitive.float, tick, fresh,
            )
            "wither" -> model.wither(o["id"]!!.jsonPrimitive.int, o.d("x"), o.d("y"), o.d("z"), tick)
            "gone" -> model.witherGone(o["id"]!!.jsonPrimitive.int, tick)
            "self" -> model.player(o.d("x"), o.d("y"), o.d("z"), tick)
            "bar" -> model.bar(o["p"]!!.jsonPrimitive.float, tick)
            "falling" -> model.falling(o.d("x"), o.d("y"), o.d("z"), tick)
            "fireball" -> {
                fireballs += tick
                model.fireball(o.d("x"), o.d("y"), o.d("z"), tick)
            }
        }
        val after = model.latestResult
        if (after != null && after !== before) results += after
        anchors += tick to model.padAnchor
    }

    private fun JsonObject.d(key: String) = this[key]!!.jsonPrimitive.double

    val p: Long get() = patheticTick ?: error("no Pathetic line")

    /** The final result whose window ended at P + [end] (± [tol]); a "Late by" replaces the miss settled at the window's end. */
    fun resultFor(end: Long, tol: Long = 1): CrushResult? = results.lastOrNull { r -> r.windowEnd.let { it != null && abs(it - p - end) <= tol } }

    fun dump(): String = buildString {
        appendLine("P=${model.pathetic}")
        for (r in results) appendLine("  ${r.pillar} end P+${r.windowEnd?.minus(p)} @P+${r.tick - p}: ${r.text}")
        for (l in logs) appendLine("  log: $l")
    }

    private fun abs(v: Long) = if (v < 0) -v else v
}
