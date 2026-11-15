package com.soulreturns.config.migrations

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs

/**
 * Version 8: the Soul action bar stops linking to the vanilla action bar. Its default is vanilla's own
 * spot as a plain bottom-centered screen anchor (`general.actionBarCustomPosition` off), so a stored
 * transform that is still version 7's default (the `vanilla:actionbar` link, centered on it) loses its
 * link and stays on that default; scale, font and shadow are kept. Every other stored transform
 * (moved, detached, attached elsewhere) is the player's choice: `actionBarCustomPosition` turns on so
 * it keeps meaning exactly where it was.
 *
 * Version 7's link defaults are snapshotted here, so later changes never alter what this step does.
 */
internal object ActionBarPlacementMigration {
    private const val TARGET = "vanilla:actionbar"

    fun migrate(root: Map<String, JsonElement>): Map<String, JsonElement> {
        val general = root["general"] as? JsonObject ?: return root
        val t = general["actionBarTransform"] as? JsonObject ?: return root
        if (general["actionBarCustomPosition"] != null) return root
        val moved = JsonObject(general + ("actionBarCustomPosition" to JsonPrimitive(true)))
        if (!isOldDefault(t)) return root + ("general" to moved)
        val fresh =
            t +
                mapOf(
                    "hAnchor" to JsonPrimitive(1),
                    "vAnchor" to JsonPrimitive(2),
                    "offsetX" to JsonPrimitive(0f),
                    "offsetY" to JsonPrimitive(0f),
                    "autoAnchor" to JsonPrimitive(true),
                    "link" to JsonNull,
                )
        return root + ("general" to JsonObject(general + ("actionBarTransform" to JsonObject(fresh))))
    }

    /** Linked to the vanilla action bar slot exactly as the default did (absent keys decode to `HudLink`'s defaults). */
    private fun isOldDefault(t: JsonObject): Boolean {
        val link = t["link"] as? JsonObject ?: return false
        fun int(key: String, default: Int) = (link[key] as? JsonPrimitive)?.intOrNull ?: default
        fun num(key: String) = (link[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun flag(key: String, default: Boolean) = (link[key] as? JsonPrimitive)?.booleanOrNull ?: default
        return (link["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content == TARGET &&
            int("h", 3) == 1 &&
            int("v", 0) == 1 &&
            abs(num("offsetX")) < 1e-6 &&
            abs(num("offsetY")) < 1e-6 &&
            flag("follow", false) &&
            !flag("auto", true) &&
            !flag("fixed", false) &&
            int("whenHidden", 0) == 0
    }
}
