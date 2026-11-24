package com.soulreturns.config.migrations

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Version 21: "Show left / right labels" (`dungeons.terminals.rubixLabels`) is off by default. Soul writes
 * defaults to the file, so every existing player stores `true` whether they chose it or not; this is a
 * one-time default flip decided by the owner (2026-11-24): it becomes `false` for everyone. A player who
 * wants the labels turns the setting on again afterwards (the migration runs once, per file version).
 * Files without a terminals section already get the new default.
 */
internal object RubixLabelsOffMigration {
    fun migrate(root: Map<String, JsonElement>): Map<String, JsonElement> {
        val dungeons = root["dungeons"] as? JsonObject ?: return root
        val terminals = dungeons["terminals"] as? JsonObject ?: return root
        if (!terminals.containsKey("rubixLabels")) return root
        val fresh = JsonObject(terminals + ("rubixLabels" to JsonPrimitive(false)))
        return root + ("dungeons" to JsonObject(dungeons + ("terminals" to fresh)))
    }
}
