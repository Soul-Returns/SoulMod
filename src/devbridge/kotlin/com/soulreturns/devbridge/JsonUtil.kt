package com.soulreturns.devbridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Maps, lists and primitives to JSON; references nothing from Soul (usable while disabled). */
fun json(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Double -> if (value.isFinite()) JsonPrimitive(round(value)) else JsonNull
        is Float -> if (value.isFinite()) JsonPrimitive(round(value.toDouble())) else JsonNull
        is Number -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to json(v) })
        is Iterable<*> -> JsonArray(value.map { json(it) })
        is Array<*> -> JsonArray(value.map { json(it) })
        is DoubleArray -> JsonArray(value.map { json(it) })
        is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
        else -> JsonPrimitive(value.toString())
    }

/** Four decimals: positions to 0.1 mm, enough for every rule and half the bytes. */
private fun round(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
