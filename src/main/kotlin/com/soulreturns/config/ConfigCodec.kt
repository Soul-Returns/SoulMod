package com.soulreturns.config

import com.soulreturns.config.migrations.ConfigMigrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Text ↔ [SoulConfig] with the guarantees players rely on across updates (no Minecraft or file
 * access, so it is unit-tested):
 *
 * 1. Files carry `configVersion`. Older files run through [ConfigMigrations] as JSON before decoding,
 *    so renamed, moved or retyped settings keep the player's values.
 * 2. One bad value never costs the whole file: invalid enum strings and nulls fall back to their
 *    default (`coerceInputValues`), and if decoding still fails, every value that does not decode on
 *    its own is dropped ([prune]) while everything else is kept.
 * 3. The caller learns what happened ([Decoded.notes]) so it can back the original file up first.
 */
object ConfigCodec {
    const val VERSION_KEY = "configVersion"

    val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

    class Decoded(
        val config: SoulConfig,
        /** Version the file was written with; 0 = before versioning, above current = a newer mod. */
        val fileVersion: Int,
        /** Human-readable problems (dropped values, unreadable file). Empty when the file was clean. */
        val notes: List<String>,
    ) {
        val migrated: Boolean get() = fileVersion < ConfigMigrations.CURRENT
        val fromNewer: Boolean get() = fileVersion > ConfigMigrations.CURRENT
    }

    fun encode(config: SoulConfig): String {
        val tree = json.encodeToJsonElement(SoulConfig.serializer(), config) as JsonObject
        return json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(VERSION_KEY to JsonPrimitive(ConfigMigrations.CURRENT)) + tree))
    }

    fun decode(text: String): Decoded {
        val root =
            try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return Decoded(SoulConfig(), 0, listOf("config.json is not valid JSON; all settings reset"))
        val version = (root[VERSION_KEY] as? JsonPrimitive)?.intOrNull ?: 0
        val migrated = ConfigMigrations.migrate(root, version) - VERSION_KEY
        val strict = runCatching { json.decodeFromJsonElement(SoulConfig.serializer(), JsonObject(migrated)) }.getOrNull()
        if (strict != null) return Decoded(strict, version, emptyList())
        val notes = ArrayList<String>()
        val kept = prune(emptyList(), JsonObject(migrated), notes) as JsonObject
        val config = runCatching { json.decodeFromJsonElement(SoulConfig.serializer(), kept) }.getOrElse {
            notes += "settings could not be read even after dropping broken values; all settings reset"
            SoulConfig()
        }
        return Decoded(config, version, notes)
    }

    private sealed interface Step

    private data class Key(val name: String) : Step

    private data object Item : Step

    /**
     * Keeps every value that decodes when it is the only thing in the file. Works because every
     * config class has defaults for all fields; recurses into objects and arrays so one broken button
     * in a list drops that button, not the list.
     */
    private fun prune(path: List<Step>, value: JsonElement, notes: MutableList<String>): JsonElement? {
        if (path.isNotEmpty() && decodes(path, value)) return value
        val inner = ArrayList<String>()
        val result: JsonElement =
            when (value) {
                is JsonObject -> {
                    val kept = LinkedHashMap<String, JsonElement>()
                    for ((k, v) in value) prune(path + Key(k), v, inner)?.let { kept[k] = it }
                    JsonObject(kept)
                }
                is JsonArray -> JsonArray(value.mapNotNull { prune(path + Item, it, inner) })
                else -> {
                    notes += "dropped ${describe(path)} = $value (reset to default)"
                    return null
                }
            }
        if (path.isEmpty() || decodes(path, result)) {
            notes += inner
            return result
        }
        // The whole container goes; its children's notes would only repeat that.
        notes += "dropped ${describe(path)} = $value (reset to default)"
        return null
    }

    private fun decodes(path: List<Step>, value: JsonElement): Boolean {
        var wrapped = value
        for (step in path.asReversed()) {
            wrapped = if (step is Key) JsonObject(mapOf(step.name to wrapped)) else JsonArray(listOf(wrapped))
        }
        return runCatching { json.decodeFromJsonElement(SoulConfig.serializer(), wrapped) }.isSuccess
    }

    private fun describe(path: List<Step>): String =
        path.joinToString("") { if (it is Key) ".${it.name}" else "[]" }.removePrefix(".")
}
