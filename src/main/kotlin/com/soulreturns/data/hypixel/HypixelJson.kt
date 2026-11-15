package com.soulreturns.data.hypixel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The `Json` used for everything decoded out of a Hypixel body, and the handful of accessors that
 * read a value out of a raw [JsonObject] without ever throwing.
 *
 * **Why so much of the Hypixel response stays raw.** A SkyBlock profile member is one of the largest
 * JSON objects any Minecraft mod deals with — several hundred keys, deeply nested, and Hypixel adds
 * to it without notice. Modelling all of it would be thousands of lines that break on the next
 * update. So the *structure* we navigate is typed ([SkyblockProfiles], [SkyblockProfile]) and the
 * *contents* stay [JsonObject], read through the typed views in [SkyblockProfileMember]. Nothing is
 * discarded on the way in, which is what lets a later lane read a field this one never knew about.
 *
 * Every accessor below returns null / a default rather than throwing: Hypixel omits keys for state a
 * player never reached (no dungeons, no slayers, API access switched off), and that is normal data,
 * not an error worth an exception per field.
 */
internal val hypixelJson = Json {
    ignoreUnknownKeys = true
    // Hypixel has shipped bare numeric strings for numeric fields before; leniency costs nothing
    // here and turns a whole-profile parse failure into one odd field.
    isLenient = true
    coerceInputValues = true
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

internal fun JsonObject.string(key: String): String? = prim(key)?.contentOrNull

internal fun JsonObject.double(key: String, fallback: Double = 0.0): Double =
    prim(key)?.doubleOrNull ?: fallback

/**
 * An integer field, tolerating a value Hypixel wrote as a decimal.
 *
 * [JsonPrimitive.longOrNull] returns null for `12.0`, so a plain `longOrNull` read turns a count
 * Hypixel happened to serialize as a double into a **silent zero** — indistinguishable from "this
 * player never did it". Falling through to [doubleOrNull] costs nothing and removes a whole class of
 * invisible wrong answers.
 */
internal fun JsonObject.long(key: String, fallback: Long = 0L): Long {
    val value = prim(key) ?: return fallback
    return value.longOrNull ?: value.doubleOrNull?.toLong() ?: fallback
}

internal fun JsonObject.boolean(key: String, fallback: Boolean = false): Boolean =
    prim(key)?.booleanOrNull ?: fallback

/** Walk a nested path, stopping at the first missing or non-object step. */
internal fun JsonObject.path(vararg keys: String): JsonObject? {
    var node: JsonObject? = this
    for (key in keys) node = node?.obj(key) ?: return null
    return node
}
