package com.soulreturns.features.inventorybuttons

import kotlin.math.abs

/**
 * Where a button can sit: a fixed set of 18 px spots in vanilla GUI pixels, shared by the live
 * overlay and the editor so the two cannot disagree. Pure arithmetic, unit-tested.
 *
 * ### One lattice, anchored to the inventory
 * Cell (n, m) sits at `x = frameLeft - 3 - 18 + n·20`, `y = frameTop - 3 - 18 + m·19`: 3 px to the
 * 176×166 frame on every side, 2 px gaps between columns, 1 px between rows. Ring 1 is the border
 * of n, m ∈ 0..10, ring 2 the border of -1..11; both close exactly with their corners.
 *
 * ### Screen edges
 * Two **columns** per side are pinned [EDGE] px from the left/right screen edge at the lattice's row
 * heights, and two **rows** [EDGE] px from the top/bottom at the lattice's columns, each only where
 * it clears ring 2 by a gap. Rows need both the top and the bottom one to fit; their cells next to
 * a column are left out (corners belong to the columns).
 *
 * ### Ids
 * A button stores its spot's id, never a position, so it keeps its spot across GUI scales: ring
 * `G:n:m`, column `L:c:m` / `R:c:m` (c-th column from that edge, lattice row m), row `T:r:n` /
 * `B:r:n` (r-th row from that edge, lattice column n). An id with no spot at the current scale (the
 * row does not fit, the cell is off screen) leaves its button hidden; ids are never rewritten here.
 *
 * ### Other containers
 * Ring cells in the left/top half (n, m ≤ 5) follow the frame's top-left corner, the rest its
 * bottom-right, so a taller chest opens a gap in the side columns instead of covering it. Where a
 * shorter frame makes two cells of one ring, column or row overlap, the later one is dropped.
 */
object InventoryButtonSpots {
    const val SIZE = 18
    const val FRAME_WIDTH = 176
    const val FRAME_HEIGHT = 166
    const val PITCH_X = 20
    const val PITCH_Y = 19
    const val CLEARANCE = 3
    const val EDGE = 3
    const val RINGS = 2
    const val EDGE_LINES = 2

    private const val GAP_X = PITCH_X - SIZE
    private const val GAP_Y = PITCH_Y - SIZE

    /** Ring 1's last lattice index on both axes; ring r spans `1 - r .. LAST - 1 + r`. */
    private const val LAST = 10
    private const val HALF = LAST / 2

    /** One spot: its id and its top-left in screen GUI pixels. [ring] is 1 or 2, 0 at a screen edge. */
    class Spot(val id: String, val x: Int, val y: Int, val ring: Int) {
        fun contains(px: Double, py: Double): Boolean = px >= x && px < x + SIZE && py >= y && py < y + SIZE
    }

    /** Every spot that exists for one screen and frame, in placement order (ring 1, ring 2, columns, rows). */
    class Layout internal constructor(
        val spots: List<Spot>,
        /** Per screen row index: whether the top and bottom rows fit. */
        val rowsFit: List<Boolean>,
        /** Screen columns that exist, as `L:c` / `R:c`. */
        val columns: List<String>,
        /** False when the screen-edge spots were left out ([layout]'s `showOuter`). */
        val outerShown: Boolean = true,
    ) {
        private val byId: Map<String, Spot> = spots.associateBy { it.id }

        fun at(id: String): Spot? = byId[id]

        fun spotAt(px: Double, py: Double): Spot? = spots.firstOrNull { it.contains(px, py) }

        /**
         * True when [id] has no spot here and the player should be told: a screen-edge id with the
         * edge spots switched off is not hidden, it is switched off (it keeps its id).
         */
        fun isHidden(id: String): Boolean = byId[id] == null && (outerShown || !isOuter(id))

        /** True when [id] is a screen-edge spot that this layout leaves out on purpose. */
        fun isSwitchedOff(id: String): Boolean = !outerShown && isOuter(id)
    }

    /** Whether [id] names a screen-edge spot (kind `L`, `R`, `T` or `B`). */
    fun isOuter(id: String): Boolean = parse(id)?.kind.let { it != null && it != 'G' }

    private var cacheKey = IntArray(11) { Int.MIN_VALUE }
    private var cached: Layout? = null

    /**
     * Spots for a [screenWidth]×[screenHeight] GUI-px screen around a frame at ([left], [top]); the
     * defaults are the player inventory, centered as vanilla centers it. With [showOuter] off only
     * the two rings exist. Spots overlapping [blocked] (x0, y0, x1, y1; the equipment display) are
     * left out, so their buttons count as hidden exactly like off-screen ones. The last layout is
     * kept, so calling this every frame costs nothing while the geometry stays put. Render thread only.
     */
    fun layout(
        screenWidth: Int,
        screenHeight: Int,
        left: Int = (screenWidth - FRAME_WIDTH) / 2,
        top: Int = (screenHeight - FRAME_HEIGHT) / 2,
        frameWidth: Int = FRAME_WIDTH,
        frameHeight: Int = FRAME_HEIGHT,
        showOuter: Boolean = true,
        blocked: IntArray? = null,
    ): Layout {
        val key = cacheKey
        val hit = cached
        val outer = if (showOuter) 1 else 0
        val b = blocked ?: NO_BLOCK
        if (hit != null && key[0] == screenWidth && key[1] == screenHeight && key[2] == left && key[3] == top && key[4] == frameWidth && key[5] == frameHeight && key[6] == outer &&
            key[7] == b[0] && key[8] == b[1] && key[9] == b[2] && key[10] == b[3]
        ) return hit
        val built = build(screenWidth, screenHeight, left, top, frameWidth, frameHeight, showOuter, blocked)
        cacheKey = intArrayOf(screenWidth, screenHeight, left, top, frameWidth, frameHeight, outer, b[0], b[1], b[2], b[3])
        cached = built
        return built
    }

    private val NO_BLOCK = intArrayOf(0, 0, 0, 0)

    private fun build(w: Int, h: Int, left: Int, top: Int, frameW: Int, frameH: Int, showOuter: Boolean, blocked: IntArray?): Layout {
        val dx = frameW - FRAME_WIDTH
        val dy = frameH - FRAME_HEIGHT
        fun cellX(n: Int): Int = left - CLEARANCE - SIZE + n * PITCH_X + if (n > HALF) dx else 0
        fun cellY(m: Int): Int = top - CLEARANCE - SIZE + m * PITCH_Y + if (m > HALF) dy else 0

        val out = ArrayList<Spot>()
        fun place(group: MutableList<Spot>, id: String, x: Int, y: Int, ring: Int) {
            if (x < 0 || y < 0 || x + SIZE > w || y + SIZE > h) return
            if (blocked != null && x < blocked[2] && blocked[0] < x + SIZE && y < blocked[3] && blocked[1] < y + SIZE) return
            if (group.any { abs(it.x - x) < SIZE && abs(it.y - y) < SIZE }) return
            val spot = Spot(id, x, y, ring)
            group.add(spot)
            out.add(spot)
        }

        val ring = ArrayList<Spot>()
        for (r in 1..RINGS) {
            val lo = 1 - r
            val hi = LAST - 1 + r
            for (n in lo..hi) place(ring, "G:$n:$lo", cellX(n), cellY(lo), r)
            for (n in lo..hi) place(ring, "G:$n:$hi", cellX(n), cellY(hi), r)
            for (m in lo + 1 until hi) place(ring, "G:$lo:$m", cellX(lo), cellY(m), r)
            for (m in lo + 1 until hi) place(ring, "G:$hi:$m", cellX(hi), cellY(m), r)
        }

        if (!showOuter) return Layout(out, emptyList(), emptyList(), outerShown = false)
        val outer = 1 - RINGS
        val outerLast = LAST - 1 + RINGS
        val ringL = cellX(outer)
        val ringR = cellX(outerLast) + SIZE
        val ringT = cellY(outer)
        val ringB = cellY(outerLast) + SIZE

        // Screen columns, at the lattice's row heights; only where they clear ring 2 by a gap.
        val columns = ArrayList<String>()
        val columnXs = ArrayList<Int>()
        val spanY = (h + abs(dy)) / PITCH_Y + 3
        for (side in listOf("L", "R")) {
            for (c in 0 until EDGE_LINES) {
                val x = if (side == "L") EDGE + c * PITCH_X else w - EDGE - SIZE - c * PITCH_X
                val clear = if (side == "L") x + SIZE + GAP_X <= ringL else x - GAP_X >= ringR
                if (!clear) continue
                columns.add("$side:$c")
                columnXs.add(x)
                val group = ArrayList<Spot>()
                for (m in -spanY..spanY) place(group, "$side:$c:$m", x, cellY(m), 0)
            }
        }

        // Screen rows, at the lattice's columns; only where both the top and the bottom one clear ring 2.
        val rowsFit = ArrayList<Boolean>()
        val spanX = (w + abs(dx)) / PITCH_X + 3
        for (r in 0 until EDGE_LINES) {
            val topY = EDGE + r * PITCH_Y
            val bottomY = h - EDGE - SIZE - r * PITCH_Y
            val fits = topY + SIZE + GAP_Y <= ringT && bottomY - GAP_Y >= ringB
            rowsFit.add(fits)
            if (!fits) continue
            for ((side, y) in listOf("T" to topY, "B" to bottomY)) {
                val group = ArrayList<Spot>()
                for (n in -spanX..spanX) {
                    val x = cellX(n)
                    if (columnXs.any { cx -> x < cx + SIZE + GAP_X && cx - GAP_X < x + SIZE }) continue
                    place(group, "$side:$r:$n", x, y, 0)
                }
            }
        }
        return Layout(out, rowsFit, columns)
    }

    /** A parsed id: kind `G`, `L`, `R`, `T` or `B` and its two numbers; null for anything else. */
    class Id(val kind: Char, val a: Int, val b: Int)

    fun parse(id: String): Id? {
        val parts = id.split(':')
        if (parts.size != 3 || parts[0].length != 1 || parts[0][0] !in "GLRTB") return null
        val a = parts[1].toIntOrNull() ?: return null
        val b = parts[2].toIntOrNull() ?: return null
        return Id(parts[0][0], a, b)
    }

    private fun ringOf(id: Id): Int = maxOf(-minOf(id.a, id.b), maxOf(id.a, id.b) - LAST, 0) + 1

    /**
     * Where a copy of the button on [id] goes (Duplicate; spec `mocks/inventory-buttons-editor/`
     * `nextToSpot`): right next to it along the run it sits on. Horizontal runs (a ring's top or
     * bottom row, the `T`/`B` screen rows) continue to the right, vertical ones (a ring's sides, the
     * `L`/`R` screen columns) downwards. Ring corners: top-left and bottom-left continue their row,
     * top-right continues the right side, bottom-right has no run. Taken or missing spots in that
     * direction are skipped; past the run's end the free spot nearest the original wins (placement
     * order breaks ties). An original without a spot here takes the first free spot. Null when
     * nothing is [free].
     */
    fun nextTo(layout: Layout, id: String, free: (String) -> Boolean): String? {
        val from = layout.at(id)
        val p = parse(id)
        if (from == null || p == null) return layout.spots.firstOrNull { free(it.id) }?.id
        val all = layout.spots.mapNotNull { s -> parse(s.id)?.let { s to it } }
        var run: List<Pair<Spot, Id>> = emptyList()
        if (p.kind == 'G') {
            val r = ringOf(p)
            val lo = 1 - r
            val hi = LAST - 1 + r
            val top = p.b == lo
            val bottom = p.b == hi
            val left = p.a == lo
            val right = p.a == hi
            val ring = all.filter { it.second.kind == 'G' && ringOf(it.second) == r }
            if ((top || bottom) && !right) {
                run = ring.filter { it.second.b == p.b && it.second.a > p.a }.sortedBy { it.second.a }
            } else if (((left || right) && !top && !bottom) || (right && top)) {
                run = ring.filter { it.second.a == p.a && it.second.b > p.b }.sortedBy { it.second.b }
            }
        } else {
            run = all.filter { it.second.kind == p.kind && it.second.a == p.a && it.second.b > p.b }.sortedBy { it.second.b }
        }
        run.firstOrNull { free(it.first.id) }?.let { return it.first.id }
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (s in layout.spots) {
            if (!free(s.id)) continue
            val d = (s.x - from.x) * (s.x - from.x) + (s.y - from.y) * (s.y - from.y)
            if (d < bestD) {
                bestD = d
                best = s.id
            }
        }
        return best
    }

    /** Where [id] is, in words, for the editor: "Ring 1, top", "Left screen edge, column 2". */
    fun describe(id: String): String {
        val parsed = parse(id) ?: return "Unknown spot"
        val kind = parsed.kind
        if (kind == 'G') {
            val r = maxOf(-minOf(parsed.a, parsed.b), maxOf(parsed.a, parsed.b) - LAST, 0) + 1
            val lo = 1 - r
            val hi = LAST - 1 + r
            val vertical = if (parsed.b == lo) "top" else if (parsed.b == hi) "bottom" else null
            val horizontal = if (parsed.a == lo) "left" else if (parsed.a == hi) "right" else null
            val where = if (vertical != null && horizontal != null) "$vertical-$horizontal corner" else vertical ?: horizontal ?: "inside"
            return "Ring $r, $where"
        }
        val side = when (kind) {
            'L' -> "Left"
            'R' -> "Right"
            'T' -> "Top"
            else -> "Bottom"
        }
        val line = if (kind == 'L' || kind == 'R') "column" else "row"
        return "$side screen edge, $line ${parsed.a + 1}"
    }
}
