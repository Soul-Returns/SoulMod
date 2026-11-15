package com.soulreturns.features.dungeons.devices

/**
 * Sharpshooter (F7/M7 device S4), pure: no Minecraft types, so the replay tests run it on recorded server traffic.
 *
 * The device is a 3x3 grid of blue terracotta blocks (x 64/66/68, y 130/128/126, z 50) shot with arrows from the
 * pressure plate at (63, 127, 35). Exactly one of them is an emerald block at a time (the target); when an arrow
 * hits it it turns back to blue terracotta and the next one appears 0 to 10 server ticks later. A cell is never the
 * target twice in a run (7 recordings), so the cells not yet hit are the candidates for the next one. Which cell
 * comes next, how many targets a run has (7 or 8 in the recordings) and what a miss does are not shown to the
 * client, so nothing here claims them.
 *
 * A cell is "hit" when it was an emerald block at one observation and is not at the next. The hits are forgotten
 * when the plate has no weight on it (the device runs only while the plate is pressed: its power is 1 or 2 from
 * before the first target until about 20 ticks after the last).
 *
 * Highlight only: this model decides nothing about the player's input.
 */
class SharpshooterModel(private val log: (String) -> Unit = {}) {
    companion object {
        const val SIZE = 9
        const val X0 = 64
        const val Y_TOP = 130
        const val Z = 50
        private const val STEP = 2
        const val ALL = (1 shl SIZE) - 1

        fun x(i: Int) = X0 + STEP * (i % 3)
        fun y(i: Int) = Y_TOP - STEP * (i / 3)

        /** Cell of the block at ([x], [y]) in the target plane, or -1 (the panes and the gaps between are not cells). */
        fun index(x: Int, y: Int): Int {
            val dx = x - X0
            val dy = Y_TOP - y
            if (dx < 0 || dy < 0 || dx > 2 * STEP || dy > 2 * STEP || dx % STEP != 0 || dy % STEP != 0) return -1
            return dy / STEP * 3 + dx / STEP
        }

        fun name(i: Int) = "r${i / 3 + 1}c${i % 3 + 1}"
    }

    private var known = false
    private var lastEmerald = 0

    /** Cells that are an emerald block now (one in every recording; a lag burst can show two). */
    var current = 0
        private set

    /** Cells that were the target and have been hit since the plate was pressed. */
    var hit = 0
        private set

    val hits: Int get() = Integer.bitCount(hit)

    /** Cells that can still become the target: not hit, not the target now. */
    val candidates: Int get() = ALL and hit.inv() and current.inv()

    val isKnown: Boolean get() = known

    fun reset(reason: String) {
        if (known) log("reset: $reason")
        known = false
        lastEmerald = 0
        current = 0
        hit = 0
    }

    /**
     * One look at the grid: [emerald] has bit i set for every cell that is an emerald block (all nine cells must
     * be blue terracotta or emerald, the caller checks); [plate] is the plate's power, or -1 when it cannot be read.
     */
    fun observe(emerald: Int, plate: Int) {
        if (plate == 0 && hit != 0) {
            log("plate free: forgot $hits hit(s)")
            hit = 0
        }
        if (known) {
            val gone = lastEmerald and emerald.inv()
            if (gone != 0 && plate != 0) {
                hit = hit or gone
                log("hit ${cells(gone)} ($hits)")
            }
            val appeared = emerald and lastEmerald.inv()
            if (appeared != 0) {
                // A cell that is the target again is no longer "hit" (Odin's solver does the same).
                hit = hit and appeared.inv()
                log("target ${cells(appeared)}")
            }
        } else if (emerald != 0) {
            log("first look: target ${cells(emerald)}")
        }
        known = true
        lastEmerald = emerald
        current = emerald
    }

    private fun cells(mask: Int): String = (0 until SIZE).filter { mask and (1 shl it) != 0 }.joinToString(",") { name(it) }
}
