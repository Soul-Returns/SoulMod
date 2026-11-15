package com.soulreturns.features.dungeons.boss

import com.soulreturns.platform.ServerClock
import com.soulreturns.util.MessageDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.zip.GZIPInputStream

/**
 * One event of a P3-P5 fixture (`tools/devbridge/late-fixtures`, `phase3-fixtures`): [tick] = the live server clock at
 * its arrival, [exact] = the tick read between the `set_time` packets around it (what the live glue re-reads a second
 * later), or on fixtures with lone pings the ping clock's tick with its fraction, as the live glue reads it.
 */
class LateEvent(val k: String, val tick: Long?, val s: String, val x: Double, val y: Double, val z: Double, val nanos: Long) {
    var exact: Double? = null
}

/**
 * Reads `src/test/resources/boss/<name>.ndjson.gz` through a [ServerClock] fed by its `set_time` lines and, when the
 * fixture has them, its lone pings, as the live glue would.
 */
fun lateFixture(name: String): List<LateEvent> {
    val stream = LateEvent::class.java.getResourceAsStream("/boss/$name.ndjson.gz") ?: error("fixture $name missing")
    val clock = ServerClock()
    val out = ArrayList<LateEvent>()
    var pinged = false
    GZIPInputStream(stream).bufferedReader().useLines { lines ->
        for (line in lines) {
            if (line.isBlank()) continue
            val o = Json.parseToJsonElement(line).jsonObject
            val nanos = o["t"]!!.jsonPrimitive.long * 1_000_000L
            val k = o["k"]!!.jsonPrimitive.content
            if (k == "time") {
                clock.onSetTime(o["gt"]!!.jsonPrimitive.long, nanos)
                continue
            }
            if (k == "ping") {
                clock.onPing(nanos)
                pinged = true
                continue
            }
            fun d(key: String) = o[key]?.jsonPrimitive?.double ?: 0.0
            val s = o["s"]?.jsonPrimitive?.content?.let { MessageDetector.stripColorCodes(it).trim() } ?: ""
            out += LateEvent(k, clock.now(nanos), s, d("x"), d("y"), d("z"), nanos).also { if (clock.pinging()) it.exact = clock.exact(nanos) }
        }
    }
    if (pinged) return out
    // Two-sided interpolation, as ServerClock.between once the next packet arrived.
    val times = ArrayList<LongArray>()
    GZIPInputStream(LateEvent::class.java.getResourceAsStream("/boss/$name.ndjson.gz")!!).bufferedReader().useLines { lines ->
        for (line in lines) {
            if (!line.contains("\"k\":\"time\"")) continue
            val o = Json.parseToJsonElement(line).jsonObject
            times += longArrayOf(o["t"]!!.jsonPrimitive.long * 1_000_000L, o["gt"]!!.jsonPrimitive.long)
        }
    }
    var i = 0
    for (e in out) {
        while (i + 1 < times.size && times[i + 1][0] < e.nanos) i++
        if (i + 1 >= times.size || times[i][0] > e.nanos) continue
        val a = times[i]
        val b = times[i + 1]
        if (b[1] - a[1] !in 1..40) continue
        e.exact = a[1] + (b[1] - a[1]).toDouble() * (e.nanos - a[0]) / (b[0] - a[0])
    }
    return out
}
