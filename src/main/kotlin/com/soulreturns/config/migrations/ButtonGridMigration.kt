package com.soulreturns.config.migrations

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Version 6: inventory buttons stop storing free `x`/`y` (vanilla GUI px relative to the 176×166
 * player inventory, clamped on screen) and store the id of a spot on the button grid instead
 * (`G:n:m` rings, `L|R:c:m` screen columns, `T|B:r:n` screen rows; see `InventoryButtonSpots`).
 *
 * Old positions are frame-relative and the screen size is unknown here, so each is placed on a
 * reference screen (1920×1080 at GUI scale 3, where both rings, both screen columns per side and
 * both screen rows exist) and moved to the nearest spot. A button whose center lies within ring 2's
 * outer bounds takes the nearest free ring spot first; one further out takes the nearest free spot
 * on the screen edge it was beyond (left/right columns, top/bottom rows), then any other edge spot,
 * then the rings. Collisions within one button list go to the next nearest free spot, earlier
 * buttons first. No button is dropped: with every spot taken it gets an id outside the grid, which
 * shows it as hidden so the player can move it.
 *
 * A snapshot like the other steps: its geometry is frozen here so later grid changes cannot change
 * what this step does to a version-5 file.
 */
internal object ButtonGridMigration {
    private const val SCREEN_W = 640
    private const val SCREEN_H = 360
    private const val SIZE = 18
    private const val PITCH_X = 20
    private const val PITCH_Y = 19
    private const val EDGE = 3
    private const val INV_X = (SCREEN_W - 176) / 2
    private const val INV_Y = (SCREEN_H - 166) / 2
    private const val X0 = INV_X - 3 - SIZE
    private const val Y0 = INV_Y - 3 - SIZE

    // Ring 2's outer bounds.
    private const val RING_L = X0 - PITCH_X
    private const val RING_R = X0 + 11 * PITCH_X + SIZE
    private const val RING_T = Y0 - PITCH_Y
    private const val RING_B = Y0 + 11 * PITCH_Y + SIZE

    /** Version 5 default of `InventoryButton.x` / `y`, for buttons that never stored them. */
    private const val OLD_X = -1
    private const val OLD_Y = -26

    private class Cell(val id: String, val x: Int, val y: Int)

    private val rings: List<Cell> = buildList {
        for (r in 1..2) {
            val lo = 1 - r
            val hi = 9 + r
            for (n in lo..hi) for (m in lo..hi) {
                if (n == lo || n == hi || m == lo || m == hi) add(Cell("G:$n:$m", X0 + n * PITCH_X, Y0 + m * PITCH_Y))
            }
        }
    }

    private val edges: Map<Char, List<Cell>> = run {
        val rows = (-Y0 / PITCH_Y)..((SCREEN_H - SIZE - Y0) / PITCH_Y)
        val columnXs = listOf(EDGE, EDGE + PITCH_X, SCREEN_W - EDGE - SIZE, SCREEN_W - EDGE - SIZE - PITCH_X)
        fun column(side: Char, c: Int, x: Int) = rows.map { m -> Cell("$side:$c:$m", x, Y0 + m * PITCH_Y) }
        fun row(side: Char, r: Int, y: Int) =
            ((-X0 + PITCH_X - 1) / PITCH_X - 1..(SCREEN_W - SIZE - X0) / PITCH_X)
                .map { n -> n to X0 + n * PITCH_X }
                .filter { (_, x) -> x >= 0 && x + SIZE <= SCREEN_W && columnXs.none { cx -> x < cx + SIZE + 2 && cx - 2 < x + SIZE } }
                .map { (n, x) -> Cell("$side:$r:$n", x, y) }
        mapOf(
            'L' to column('L', 0, EDGE) + column('L', 1, EDGE + PITCH_X),
            'R' to column('R', 0, SCREEN_W - EDGE - SIZE) + column('R', 1, SCREEN_W - EDGE - SIZE - PITCH_X),
            'T' to row('T', 0, EDGE) + row('T', 1, EDGE + PITCH_Y),
            'B' to row('B', 0, SCREEN_H - EDGE - SIZE) + row('B', 1, SCREEN_H - EDGE - SIZE - PITCH_Y),
        )
    }

    /** Rewrites `inventoryButtons.buttons` and every `profiles[].buttons`; drops `snapToGrid` (every button is on the grid now). */
    fun migrate(root: Map<String, JsonElement>): Map<String, JsonElement> {
        val settings = root["inventoryButtons"] as? JsonObject ?: return root
        var next: Map<String, JsonElement> = settings - "snapToGrid"
        (settings["buttons"] as? JsonArray)?.let { next = next + ("buttons" to placeAll(it)) }
        (settings["profiles"] as? JsonArray)?.let { profiles ->
            next = next + ("profiles" to JsonArray(profiles.map { entry ->
                val profile = entry as? JsonObject ?: return@map entry
                val buttons = profile["buttons"] as? JsonArray ?: return@map entry
                JsonObject(profile + ("buttons" to placeAll(buttons)))
            }))
        }
        return root + ("inventoryButtons" to JsonObject(next))
    }

    private fun placeAll(buttons: JsonArray): JsonArray {
        // A button that already has a spot and no position (a file re-run through this step) keeps it.
        fun placed(button: JsonObject): String? =
            (button["spot"] as? JsonPrimitive)?.takeIf { it.isString && "x" !in button && "y" !in button }?.content
        val taken = buttons.mapNotNullTo(HashSet()) { (it as? JsonObject)?.let(::placed) }
        var overflow = 0
        return JsonArray(buttons.map { entry ->
            val button = entry as? JsonObject ?: return@map entry
            if (placed(button) != null) return@map entry
            val x = (button["x"] as? JsonPrimitive)?.intOrNull ?: OLD_X
            val y = (button["y"] as? JsonPrimitive)?.intOrNull ?: OLD_Y
            val id = nearest(x, y).firstOrNull { it.id !in taken }?.id ?: "G:-2:${overflow++}"
            taken.add(id)
            JsonObject(button - "x" - "y" + ("spot" to JsonPrimitive(id)))
        })
    }

    /** Candidate spots for an old frame-relative position, best first. */
    private fun nearest(x: Int, y: Int): List<Cell> {
        // Old positions were clamped on screen; place them where the player saw them.
        val ax = (INV_X + x).coerceIn(0, SCREEN_W - SIZE)
        val ay = (INV_Y + y).coerceIn(0, SCREEN_H - SIZE)
        val cx = ax + SIZE / 2
        val cy = ay + SIZE / 2
        fun sorted(cells: List<Cell>) = cells.sortedBy { (it.x - ax) * (it.x - ax) + (it.y - ay) * (it.y - ay) }
        val allEdges = edges.values.flatten()
        if (cx in RING_L..RING_R && cy in RING_T..RING_B) return sorted(rings) + sorted(allEdges)
        val beyond = mapOf('L' to RING_L - cx, 'R' to cx - RING_R, 'T' to RING_T - cy, 'B' to cy - RING_B)
        val side = beyond.maxBy { it.value }.key
        val own = edges.getValue(side)
        return sorted(own) + sorted(allEdges - own.toSet()) + sorted(rings)
    }
}
