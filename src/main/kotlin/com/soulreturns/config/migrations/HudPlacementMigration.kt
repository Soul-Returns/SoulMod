package com.soulreturns.config.migrations

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Version 7: HUD transforms leave the fractional pin (`anchorX`/`anchorY` = a fraction of the screen,
 * `hAnchor`/`vAnchor` = which edge of the HUD grabs it, `offsetX`/`offsetY` from there, nothing
 * clamped) for the edge-anchored model (`hAnchor`/`vAnchor` = nearest screen region,
 * `offsetX`/`offsetY` = px from it, `autoAnchor`, optional `link`).
 *
 * Each stored transform is resolved once with the old math at a reference window (1920×1080, MC GUI
 * scale 2, Soul scale 2 → 960×540 Soul px, the action bar / tools lift at factor 1) to a box, kept on
 * that screen, then anchored to its nearest region. A HUD the player never moved (position equal to
 * the old default) takes the new default instead, which for Action Bar, Scavenged Tools and Sprint
 * Toggle is a link to the vanilla element they used to imitate. No other links are created. Scale,
 * font, weight and shadow stay as they were.
 *
 * Everything this step needs is snapshotted here (old defaults, the new default positions, content
 * sizes), so later code changes never change what it does to a version-6 file. The sizes are each
 * HUD's Move GUI preview at scale 1; they only matter when the nearest region differs from the edge
 * that grabbed the old pin.
 */
internal object HudPlacementMigration {
    private const val REF_W = 960.0
    private const val REF_H = 540.0

    private val POSITION_KEYS = listOf("anchorX", "anchorY", "offsetX", "offsetY", "hAnchor", "vAnchor")

    /**
     * Version 6's `HudTransform` constructor defaults: what an absent key inside a stored transform
     * decoded to (the field defaults below only applied when the whole transform was absent).
     */
    private val CLASS_DEFAULTS =
        mapOf("anchorX" to 0.5, "anchorY" to 0.5, "offsetX" to 0.0, "offsetY" to 0.0, "hAnchor" to 0.0, "vAnchor" to 0.0, "scale" to 1.0)

    private class Entry(
        val path: List<String>,
        /** The field's old default position. */
        val old: Map<String, Double>,
        /** Content size at scale 1 in Soul px. */
        val w: Double,
        val h: Double,
        /** New default position fields for a HUD that was never moved. */
        val fresh: Map<String, JsonElement>,
        /** The old `pinOffsetY` at the reference window, for (anchorY, vAnchor). */
        val lift: (Double, Int) -> Double = { _, _ -> 0.0 },
    )

    private fun old(ax: Double, ay: Double, h: Int = 0, v: Int = 0, ox: Double = 0.0, oy: Double = 0.0) =
        mapOf("anchorX" to ax, "anchorY" to ay, "offsetX" to ox, "offsetY" to oy, "hAnchor" to h.toDouble(), "vAnchor" to v.toDouble())

    private fun fresh(h: Int, v: Int, ox: Float, oy: Float, link: JsonElement = JsonNull): Map<String, JsonElement> =
        mapOf(
            "hAnchor" to JsonPrimitive(h),
            "vAnchor" to JsonPrimitive(v),
            "offsetX" to JsonPrimitive(ox),
            "offsetY" to JsonPrimitive(oy),
            "autoAnchor" to JsonPrimitive(true),
            "link" to link,
        )

    private fun link(target: String, h: Int, v: Int, ox: Float = 0f, oy: Float = 0f): JsonObject =
        JsonObject(
            mapOf(
                "target" to JsonPrimitive(target),
                "h" to JsonPrimitive(h),
                "v" to JsonPrimitive(v),
                "offsetX" to JsonPrimitive(ox),
                "offsetY" to JsonPrimitive(oy),
                "follow" to JsonPrimitive(true),
                "auto" to JsonPrimitive(false),
                "fixed" to JsonPrimitive(false),
                "whenHidden" to JsonPrimitive(0),
            ),
        )

    private val entries =
        listOf(
            Entry(listOf("sacks", "hudTransform"), old(0.99, 0.35, h = 2), 270.0, 89.0, fresh(2, 1, -9.5f, -36.5f)),
            Entry(listOf("notifications", "hud"), old(0.5, 0.12, h = 1), 216.0, 49.0, fresh(1, 0, 0f, 65f)),
            Entry(listOf("combo", "hud"), old(0.5, 0.35, h = 1, v = 1), 116.0, 60.0, fresh(1, 1, 0f, -81f)),
            Entry(
                listOf("general", "toggleSprintHudTransform"),
                old(0.5, 1.0, h = 2, v = 2, ox = -95.0, oy = -22.0),
                86.0,
                21.0,
                fresh(1, 2, -138f, -22f, link("vanilla:hotbar", -1, -1, ox = -4f)),
            ),
            Entry(
                listOf("general", "actionBarTransform"),
                old(0.5, 1.0, h = 1, v = 1),
                225.0,
                11.0,
                fresh(1, 2, 0f, -62f, link("vanilla:actionbar", 1, 1)),
                lift = { ay, v -> if (ay == 1.0 && v == 1) -67.5 else 0.0 },
            ),
            Entry(listOf("general", "alertTransform"), old(0.5, 0.38, h = 1, v = 1), 222.0, 53.0, fresh(1, 1, 0f, -65f)),
            Entry(listOf("fishing", "trackerHudTransform"), old(0.01, 0.02), 280.0, 222.0, fresh(0, 0, 9.5f, 11f)),
            Entry(listOf("dungeons", "mapHud"), old(0.5, 0.5, ox = 10.0, oy = 10.0), 128.0, 128.0, fresh(1, 1, 74f, 74f)),
            Entry(listOf("mining", "nucleus", "hudTransform"), old(0.01, 0.02), 300.0, 252.0, fresh(0, 0, 9.5f, 11f)),
            Entry(listOf("mining", "nucleus", "checklistTransform"), old(0.99, 0.66, h = 2), 193.0, 94.0, fresh(2, 2, -9.5f, -89.5f)),
            Entry(listOf("mining", "nucleus", "balTransform"), old(0.99, 0.88, h = 2), 155.0, 24.0, fresh(2, 2, -9.5f, -41f)),
            Entry(
                listOf("mining", "metalDetector", "toolsCounterTransform"),
                old(0.5, 1.0, h = 1),
                76.0,
                28.0,
                fresh(1, 2, 0f, -32f, link("vanilla:actionbar", 1, 3, oy = 3f)),
                lift = { ay, v -> if (ay == 1.0 && v == 0) -60.0 else 0.0 },
            ),
            Entry(listOf("mining", "metalDetector", "hudTransform"), old(0.01, 0.02), 280.0, 82.0, fresh(0, 0, 9.5f, 11f)),
            Entry(listOf("mining", "mineshaft", "corpsesHudTransform"), old(0.5, 0.04, h = 1), 81.0, 60.0, fresh(1, 0, 0f, 21.5f)),
            Entry(listOf("combat", "mobHealth", "hud"), old(0.5, 0.5, h = 1, oy = 30.0), 157.0, 40.0, fresh(1, 1, 0f, 50f)),
            Entry(listOf("combat", "dragons", "profitHudTransform"), old(0.99, 0.02, h = 2), 280.0, 252.0, fresh(2, 0, -9.5f, 11f)),
        )

    fun migrate(root: Map<String, JsonElement>): Map<String, JsonElement> {
        var tree = root
        for (e in entries) tree = tree.rewrite(e.path) { convert(it, e) }
        return tree
    }

    /** The transform at [path] rewritten by [change]; absent paths and non-objects are left alone. */
    private fun Map<String, JsonElement>.rewrite(path: List<String>, change: (JsonObject) -> JsonObject): Map<String, JsonElement> {
        val key = path.first()
        val child = this[key] as? JsonObject ?: return this
        val next = if (path.size == 1) change(child) else JsonObject(child.rewrite(path.drop(1), change))
        return this + (key to next)
    }

    private fun convert(t: JsonObject, e: Entry): JsonObject {
        // Already in the new shape (only new transforms carry these): nothing to convert.
        if ("autoAnchor" in t || "link" in t) return t
        // Absent or non-numeric values are what version 6 decoded them to.
        fun num(key: String): Double = (t[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: CLASS_DEFAULTS.getValue(key)
        val style = t - POSITION_KEYS.toSet()
        val unmoved = POSITION_KEYS.all { abs(num(it) - e.old.getValue(it)) < 1e-6 }
        if (unmoved) return JsonObject(style + e.fresh)

        val scale = num("scale")
        val w = e.w * scale
        val h = e.h * scale
        val ha = num("hAnchor").roundToInt().coerceIn(0, 2)
        val va = num("vAnchor").roundToInt().coerceIn(0, 2)
        val ay = num("anchorY")
        val x0 = (num("anchorX") * REF_W - ha * w / 2 + num("offsetX")).coerceIn(0.0, maxOf(0.0, REF_W - w))
        val y0 = (ay * REF_H + e.lift(ay, va) - va * h / 2 + num("offsetY")).coerceIn(0.0, maxOf(0.0, REF_H - h))
        val hn = region(x0, x0 + w, REF_W)
        val vn = region(y0, y0 + h, REF_H)
        return JsonObject(
            style +
                mapOf(
                    "hAnchor" to JsonPrimitive(hn),
                    "vAnchor" to JsonPrimitive(vn),
                    "offsetX" to JsonPrimitive(half(x0 - hn * (REF_W - w) / 2)),
                    "offsetY" to JsonPrimitive(half(y0 - vn * (REF_H - h) / 2)),
                    "autoAnchor" to JsonPrimitive(true),
                    "link" to JsonNull,
                ),
        )
    }

    /** Nearest region of a span on a screen of [len]: 0 start, 1 center, 2 end (center wins ties). */
    private fun region(a0: Double, a1: Double, len: Double): Int {
        val ds = a0
        val de = len - a1
        val dc = abs((a0 + a1) / 2 - len / 2)
        if (dc <= ds && dc <= de) return 1
        return if (ds <= de) 0 else 2
    }

    private fun half(v: Double): Float = ((v * 2).roundToInt() / 2.0).toFloat()
}
