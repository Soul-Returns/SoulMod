package com.soulreturns.config.migrations

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs

/**
 * Version 9: the Metal Detector loot panel's default moves from the top-left (hAnchor 0, vAnchor 0,
 * offset 9.5 / 11) to the top-right (hAnchor 2, vAnchor 0, offset -5 / 5). A stored transform that is
 * still the old default, unlinked, takes the new position; scale, font, weight and shadow are kept.
 * Anything else was moved by the player and stays.
 */
internal object MetalDetectorPlacementMigration {
    fun migrate(root: Map<String, JsonElement>): Map<String, JsonElement> {
        val mining = root["mining"] as? JsonObject ?: return root
        val detector = mining["metalDetector"] as? JsonObject ?: return root
        val t = detector["hudTransform"] as? JsonObject ?: return root
        if (!isOldDefault(t)) return root
        val fresh =
            t +
                mapOf(
                    "hAnchor" to JsonPrimitive(2),
                    "vAnchor" to JsonPrimitive(0),
                    "offsetX" to JsonPrimitive(-5f),
                    "offsetY" to JsonPrimitive(5f),
                    "autoAnchor" to JsonPrimitive(true),
                    "link" to JsonNull,
                )
        val newDetector = JsonObject(detector + ("hudTransform" to JsonObject(fresh)))
        return root + ("mining" to JsonObject(mining + ("metalDetector" to newDetector)))
    }

    private fun isOldDefault(t: JsonObject): Boolean {
        fun int(key: String) = (t[key] as? JsonPrimitive)?.intOrNull ?: 0
        fun num(key: String) = (t[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val link = t["link"]
        return (link == null || link is JsonNull) &&
            int("hAnchor") == 0 &&
            int("vAnchor") == 0 &&
            abs(num("offsetX") - 9.5) < 1e-6 &&
            abs(num("offsetY") - 11.0) < 1e-6
    }
}
