package com.soulreturns.config.migrations

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Version 13: the Scavenged Tools counter's default moves from "under the vanilla action bar" (centered
 * on the action bar slot, 3 px below it) to the top-right corner, 5 px in, linked to the left of the
 * Metal Detector loot panel while that shows. A stored transform that is still the old default, link
 * included, takes the new one; scale, font, weight and shadow are kept. Anything else was moved or
 * re-linked by the player and stays. Absent keys count as the old default's value (written files carry
 * every key, a hand-edited one may not).
 */
internal object ToolsCounterPlacementMigration {
    fun migrate(root: Map<String, JsonElement>): Map<String, JsonElement> {
        val mining = root["mining"] as? JsonObject ?: return root
        val detector = mining["metalDetector"] as? JsonObject ?: return root
        val t = detector["toolsCounterTransform"] as? JsonObject ?: return root
        if (!isOldDefault(t)) return root
        val fresh =
            t +
                mapOf(
                    "hAnchor" to JsonPrimitive(2),
                    "vAnchor" to JsonPrimitive(0),
                    "offsetX" to JsonPrimitive(-5f),
                    "offsetY" to JsonPrimitive(5f),
                    "autoAnchor" to JsonPrimitive(true),
                    "link" to
                        JsonObject(
                            mapOf(
                                "target" to JsonPrimitive("hud:metal_detector_tracker"),
                                "h" to JsonPrimitive(-1),
                                "v" to JsonPrimitive(0),
                                "offsetX" to JsonPrimitive(-5f),
                                "offsetY" to JsonPrimitive(0f),
                                "follow" to JsonPrimitive(true),
                                "auto" to JsonPrimitive(false),
                                "fixed" to JsonPrimitive(false),
                                "whenHidden" to JsonPrimitive(0),
                            ),
                        ),
                )
        val newDetector = JsonObject(detector + ("toolsCounterTransform" to JsonObject(fresh)))
        return root + ("mining" to JsonObject(mining + ("metalDetector" to newDetector)))
    }

    /** An absent key decodes to the class default, so that is what it is compared as. */
    private fun isOldDefault(t: JsonObject): Boolean {
        val link = t["link"] as? JsonObject ?: return false
        return num(t, "hAnchor", 0.0) == 1.0 &&
            num(t, "vAnchor", 0.0) == 2.0 &&
            num(t, "offsetX", 0.0) == 0.0 &&
            num(t, "offsetY", 0.0) == -32.0 &&
            flagIs(t, "autoAnchor", absent = true, expected = true) &&
            (link["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "vanilla:actionbar" &&
            num(link, "h", 3.0) == 1.0 &&
            num(link, "v", 0.0) == 3.0 &&
            num(link, "offsetX", 0.0) == 0.0 &&
            num(link, "offsetY", 0.0) == 3.0 &&
            flagIs(link, "follow", absent = false, expected = true) &&
            flagIs(link, "auto", absent = true, expected = false) &&
            flagIs(link, "fixed", absent = false, expected = false) &&
            num(link, "whenHidden", 0.0) == 0.0
    }

    /** The number at [key] ([absent] when the key is missing); NaN when it is not a number. */
    private fun num(o: JsonObject, key: String, absent: Double): Double {
        val p = o[key] ?: return absent
        return (p as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: Double.NaN
    }

    /** The boolean at [key] ([absent] when the key is missing) equals [expected]; false for a non-boolean. */
    private fun flagIs(o: JsonObject, key: String, absent: Boolean, expected: Boolean): Boolean {
        val p = o[key] ?: return absent == expected
        return (p as? JsonPrimitive)?.booleanOrNull == expected
    }
}
