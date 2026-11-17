package com.soulreturns.features.experiments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.zip.GZIPInputStream

/**
 * A recorded Experimentation Table fixture (`tools/devbridge/exp-fixtures`, real Hypixel packets) and its
 * replay through an [ExpFlow] the way the live glue feeds it: every packet in arrival order, and a
 * settle/age step whenever the server tick changes (the client's frame between ticks).
 */
object ExpReplay {
    class Event(val t: Long, val tick: Long, val k: String, val o: JsonObject)

    fun events(name: String): List<Event> {
        val stream = ExpReplay::class.java.getResourceAsStream("/experiments/$name.ndjson.gz") ?: error("fixture $name missing")
        return GZIPInputStream(stream).bufferedReader().readLines().filter { it.isNotBlank() }.map {
            val o = Json.parseToJsonElement(it).jsonObject
            Event(o["t"]!!.jsonPrimitive.long, o["tick"]!!.jsonPrimitive.long, o["k"]!!.jsonPrimitive.content, o)
        }
    }

    private val SB_ID = Regex("(?:^|[{,])id:\"([^\"]+)\"")
    private val PET_TYPE = Regex("\\\\?\"type\\\\?\":\\\\?\"([A-Z_]+)")
    private val PET_TIER = Regex("\\\\?\"tier\\\\?\":\\\\?\"([A-Z_]+)")

    fun item(e: JsonElement?): ExpItem? {
        if (e == null || e is JsonNull) return null
        val o = e.jsonObject
        val c = o["components"]?.jsonObject
        val custom = o["custom_data"]?.jsonPrimitive?.contentOrNull
        val sbId = custom?.let { SB_ID.find(it)?.groupValues?.get(1) }
        return ExpItem(
            id = o["id"]!!.jsonPrimitive.content.removePrefix("minecraft:"),
            count = o["count"]?.jsonPrimitive?.int ?: 1,
            name = o["name"]?.jsonPrimitive?.content ?: "",
            lore = (o["lore"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            glint = c?.get("glint")?.jsonPrimitive?.boolean ?: false,
            texture = c?.get("texture")?.jsonPrimitive?.contentOrNull,
            dye = c?.get("dye")?.jsonPrimitive?.contentOrNull?.removePrefix("#")?.toInt(16),
            sbId = sbId,
            petType = if (sbId == "PET") custom.let { PET_TYPE.find(it)?.groupValues?.get(1) } else null,
            petTier = if (sbId == "PET") custom.let { PET_TIER.find(it)?.groupValues?.get(1) } else null,
        )
    }

    fun rows(o: JsonObject): Int = ((o["size"]?.jsonPrimitive?.int) ?: 54) / 9

    fun title(o: JsonObject): String = ExpItem.strip(o["title"]!!.jsonPrimitive.content)

    fun contents(o: JsonObject, size: Int): List<ExpItem?> {
        val items = o["items"]!!.jsonObject
        return List(size) { item(items[it.toString()]) }
    }

    /**
     * Feed [events] to [flow]. [onEvent] runs after each event (with the menu's model in its new state);
     * [shift] moves the recorded events' client time (not used for ordering).
     */
    fun feed(flow: ExpFlow, events: List<Event>, onEvent: (Event) -> Unit = {}) {
        var tick = Long.MIN_VALUE
        var last = 0L
        for (e in events) {
            if (e.tick != tick && tick != Long.MIN_VALUE) flow.tick(last + 1, last)
            tick = e.tick
            last = e.t
            val o = e.o
            when (e.k) {
                "open" -> flow.open(o["cid"]!!.jsonPrimitive.int, title(o), rows(o), e.t)
                "slot" -> flow.slot(o["cid"]!!.jsonPrimitive.int, o["slot"]!!.jsonPrimitive.int, item(o["item"]), e.t)
                "content" -> {
                    val m = flow.menu
                    val cid = o["cid"]!!.jsonPrimitive.int
                    flow.contents(cid, contents(o, if (m != null && m.cid == cid) m.size else 54), e.t)
                }
                "sound" -> flow.sound(o["name"]!!.jsonPrimitive.content, o["pitch"]!!.jsonPrimitive.float, e.t)
                "close" -> if (o["by"]!!.jsonPrimitive.content == "server") flow.close()
            }
            onEvent(e)
        }
        flow.tick(last + 1, last)
    }

    fun games(): List<String> = listOf("exp-6", "exp-10", "exp-17", "exp-22", "exp-26", "exp-30", "exp-36")

    @Suppress("unused")
    private fun JsonArray.strings() = map { it.jsonPrimitive.content }
}
