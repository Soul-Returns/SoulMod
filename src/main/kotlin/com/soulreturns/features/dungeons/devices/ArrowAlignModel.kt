package com.soulreturns.features.dungeons.devices

/**
 * Arrow Align (F7/M7 Goldor phase, section 3) as the client can know it: which way every arrow frame
 * has to point, how many clicks each still needs, and which of the player's own clicks to hold back.
 * Pure (no Minecraft types): [ArrowAlign] and the recording fixtures feed it the same calls. Ground
 * truth: `docs/research/f7-devices-2026-11-12.md` §11.
 *
 * The device is a 5x5 grid of item frames on the wall x -3 (frames in the blocks x -2, y 120..124, z 75..79).
 * Cells are indexed `(124 - y) * 5 + (79 - z)`: 0 = top left as the player sees it (facing west), named
 * `r<row>c<col>`. Frames hold an arrow (rotation 0..7, 45° per click, +1 each) or the lime `Start` / red `End`
 * wool; the block behind an arrow frame is a sea lantern exactly while the arrow is at its target, a blue
 * terracotta otherwise (the wool frames' lanterns are always lit).
 *
 * Which frames exist and where the wool is says which of nine fixed layouts the device is; each layout
 * has one target rotation per arrow. The layout is never guessed: it is the one layout every visible frame
 * fits, a layout whose targets the sea lanterns contradict for [GRACE_MS] is dropped, and where the visible
 * frames leave several layouts a frame's target is known only if they agree on it. A click on an arrow
 * frame that is already at its target (counting the player's own clicks the server has not shown yet) is
 * held back: it costs seven more clicks to come back. Without a known target the lantern alone decides
 * (lit = at the target). Nothing is held back for any other frame, when nothing is known, or once the
 * server's own state has every arrow at its target (the device is complete).
 */
class ArrowAlignModel(private val log: (String) -> Unit = {}) {
    companion object {
        const val COLS = 5
        const val ROWS = 5
        const val SIZE = COLS * ROWS

        /** Frame block x and wall (back block) x. */
        const val FRAME_X = -2
        const val BACK_X = -3
        const val Y_TOP = 124
        const val Z_LEFT = 79

        /** An own click the server has not shown after this much server traffic is no longer assumed made. */
        const val CONFIRM_MS = 700L

        /** A layout's targets contradicted by the lanterns for this long drop it (the two arrive in separate packets of one tick). */
        const val GRACE_MS = 250L

        private const val NONE = Long.MIN_VALUE

        fun index(y: Int, z: Int): Int = (Y_TOP - y) * COLS + (Z_LEFT - z)
        fun y(index: Int): Int = Y_TOP - index / COLS
        fun z(index: Int): Int = Z_LEFT - index % COLS
        /** 0 = the bottom-left-most frame as the player sees it (facing west the bottom row is y 120, its left end z 79), then along the row, then the row above. */
        fun leftness(cell: Int): Int = (4 - cell / 5) * 5 + cell % 5

        fun name(index: Int): String = "r${index / COLS + 1}c${index % COLS + 1}"

        private const val X = -1
        private const val S = -2
        private const val E = -3

        /**
         * The nine layouts (Skyblocker `ArrowAlign`, NoFrills `ArrowAlignSolver`, which agree): per cell the target rotation
         * of its arrow (1 = right, 3 = down, 5 = left, 7 = up as the player sees it), [S] the `Start` wool, [E] the `End`
         * wool, [X] no frame. The recordings used layouts 3, 6, 7, 8 and 9 (1-based); 1, 2, 4 and 5 are from the mods only.
         */
        val LAYOUTS: List<IntArray> = listOf(
            intArrayOf(1, 1, 3, X, X, 7, X, 3, X, X, S, X, 3, X, E, X, X, 3, X, 7, X, X, 1, 1, 7),
            intArrayOf(1, 1, E, 5, 5, 7, X, X, X, 7, 7, 5, X, 1, 7, X, 7, X, 7, X, X, S, X, S, X),
            intArrayOf(3, 5, 5, X, X, 3, X, 7, X, X, E, X, S, X, E, X, X, 3, X, 7, X, X, 1, 1, 7),
            intArrayOf(S, X, X, X, S, 3, X, X, X, 3, 3, X, E, X, 3, 3, X, 7, X, 3, 1, 1, 7, 5, 5),
            intArrayOf(1, 1, 1, 1, 3, 7, X, X, X, 3, 7, X, E, X, 3, 7, X, 7, X, 3, S, X, 7, 5, 5),
            intArrayOf(1, 1, 3, X, E, 7, X, 3, X, 7, 7, X, 3, X, 7, 7, X, 3, X, 7, S, X, 1, 1, 7),
            intArrayOf(S, 1, 1, 1, E, X, X, X, X, X, S, 1, 1, 1, E, X, X, X, X, X, S, 1, 1, 1, E),
            intArrayOf(X, 1, 1, 3, X, X, 7, X, 3, X, X, 7, X, 3, X, X, 7, X, 3, X, S, 7, X, 1, E),
            intArrayOf(S, 1, 3, X, X, X, X, 1, 1, E, S, 1, 7, X, X, X, X, 1, 1, E, S, 1, 7, X, X),
        )

        const val ARROW = 0
        const val START = 1
        const val END = 2
        /** Any other item in a frame: fits no layout. */
        const val OTHER = 3
    }

    /** One frame as the client sees it: [kind] is [ARROW] / [START] / [END] / [OTHER]; [lantern] null = not readable. */
    data class Frame(val cell: Int, val kind: Int, val rot: Int, val lantern: Boolean?)

    /** Why a click was let through or held back; [blocked] = nothing is sent, [wrong] = it is an over-rotation whatever the toggle says. */
    data class Decision(val blocked: Boolean, val reason: String, val wrong: Boolean = blocked, val held: Boolean = false)

    private var known = false
    private val frames = HashMap<Int, Frame>()
    private val badSince = LongArray(LAYOUTS.size) { NONE }
    private var matching: List<Int> = emptyList()
    private var alive: List<Int> = emptyList()

    /** Own clicks let through, per cell, with their send time (the client's clock). */
    private val pending = HashMap<Int, ArrayDeque<Long>>()

    /** The rotation each cell had at the last [observe], to count which of its pending clicks the server has shown. */
    private val seenRot = HashMap<Int, Int>()

    /** At least one frame was read; the device is on screen. */
    val isKnown: Boolean get() = known

    /** The layouts every visible frame fits and the lanterns do not contradict (1-based numbers in logs). */
    val layouts: List<Int> get() = alive

    private fun pendingCount(cell: Int): Int = pending[cell]?.size ?: 0

    /** The rotation of [cell]'s arrow with the player's own unanswered clicks counted, or null when it is not an arrow frame. */
    fun effectiveRotation(cell: Int): Int? = frames[cell]?.takeIf { it.kind == ARROW }?.let { (it.rot + pendingCount(cell)) % 8 }

    /** The rotation [cell]'s arrow must have, when every layout still possible agrees on it. */
    fun target(cell: Int): Int? {
        if (alive.isEmpty()) return null
        val first = LAYOUTS[alive[0]][cell]
        if (first < 0) return null
        return if (alive.all { LAYOUTS[it][cell] == first }) first else null
    }

    /** Clicks [cell] still needs (0 = at the target), or null when its target is not known. */
    fun clicksLeft(cell: Int): Int? {
        val t = target(cell) ?: return null
        val e = effectiveRotation(cell) ?: return null
        return (t - e + 8) % 8
    }

    /**
     * One layout is left and every arrow of it is on screen at its target in the server's own state (own clicks the
     * server has not shown do not count: the device is not complete before the server says so).
     */
    val done: Boolean
        get() {
            if (alive.size != 1) return false
            val l = LAYOUTS[alive[0]]
            for (c in 0 until SIZE) {
                if (l[c] < 0) continue
                val f = frames[c] ?: return false
                if (f.kind == ARROW && f.rot != l[c]) return false
            }
            return true
        }

    /** Total clicks still needed by every arrow with the own clicks counted, or null when a target or the layout is not known. */
    fun totalClicksLeft(): Int? {
        if (alive.isEmpty() || done) return null
        var sum = 0
        for (f in frames.values) {
            if (f.kind != ARROW) continue
            sum += clicksLeft(f.cell) ?: return null
        }
        return sum
    }

    /** Before Goldor with the toggle on: set by the glue every tick and with every click. */
    var hold = false

    /**
     * The server said the device is complete (its chat line right after an own click). Only this ends the blocking:
     * every arrow at its target is not "complete" before Goldor (live run 2026-11-12 19:19-19:28: aligned for minutes,
     * no completion until the click in Goldor's phase).
     */
    var completed = false
        private set

    fun complete() {
        if (known && !completed) {
            completed = true
            log("device completed (the server's word)")
        }
    }

    /**
     * Every arrow is at its target and the server has not completed the device: it completes on a click, so in
     * Goldor's phase one arrow (the bottom-left-most) may take a full turn. -1 when not in that state.
     * Unverified: that a full turn of an aligned board completes it (no recording shows it).
     */
    fun fullTurnArrow(): Int =
        if (known && done && !completed) frames.values.filter { it.kind == ARROW }.minByOrNull { leftness(it.cell) }?.cell ?: -1 else -1

    /** The arrow left for after Goldor starts: the bottom-left-most one that still needs clicks (kept one click short while the rest are finished). */
    fun lastArrow(): Int = remaining().minByOrNull { leftness(it) } ?: -1

    /** The arrow cells that still need clicks, in cell order. */
    fun remaining(): List<Int> = if (done) emptyList() else frames.keys.sorted().filter { (clicksLeft(it) ?: 0) > 0 }

    fun reset(reason: String) {
        if (known) log("reset: $reason")
        known = false
        frames.clear()
        pending.clear()
        completed = false
        seenRot.clear()
        matching = emptyList()
        alive = emptyList()
        badSince.fill(NONE)
    }

    /** The frames as the client's world shows them right now; call once per tick (or per packet in a replay). */
    fun observe(now: Long, seen: List<Frame>) {
        if (seen.isEmpty()) return reset("no frames in range")
        known = true
        val before = alive
        frames.clear()
        for (f in seen) frames[f.cell] = f
        // Which pending clicks the server has shown: every +1 of a cell's rotation is one.
        val gone = ArrayList<Int>()
        for ((cell, q) in pending) {
            val f = frames[cell]
            if (f == null) {
                gone += cell
                continue
            }
            val last = seenRot[cell]
            if (last != null && f.rot != last) {
                val advance = (f.rot - last + 8) % 8
                repeat(minOf(advance, q.size)) { q.removeFirst() }
            }
            if (q.isEmpty()) gone += cell
        }
        for (cell in gone) pending.remove(cell)
        seenRot.clear()
        for (f in seen) seenRot[f.cell] = f.rot
        matching = LAYOUTS.indices.filter { fits(LAYOUTS[it]) }
        for (i in LAYOUTS.indices) {
            val bad = i in matching && contradicts(LAYOUTS[i])
            if (!bad) badSince[i] = NONE
            else if (badSince[i] == NONE) {
                badSince[i] = now
                log("lantern contradicts layout ${i + 1} (grace ${GRACE_MS} ms): ${dump()}")
            }
        }
        alive = matching.filter { badSince[it] == NONE || now - badSince[it] < GRACE_MS }
        if (alive != before) {
            log(
                when {
                    alive.size == 1 -> "layout ${alive[0] + 1}: ${frames.size} frames"
                    alive.isEmpty() -> "no layout fits ${frames.size} frames (matching ${matching.map { it + 1 }}, lantern-contradicted ${matching.filter { it !in alive }.map { it + 1 }})"
                    else -> "layouts ${alive.map { it + 1 }} fit ${frames.size} frames"
                } + " | ${dump()}",
            )
        }
    }

    private fun fits(l: IntArray): Boolean {
        for (f in frames.values) {
            val v = l[f.cell]
            if (v == X) return false
            if (v == S && f.kind != START) return false
            if (v == E && f.kind != END) return false
            if (v >= 0 && f.kind != ARROW) return false
        }
        return true
    }

    /**
     * An arrow whose lantern is lit while the arrow is not at this layout's target. Only a lit lantern counts: a dark
     * one beside an arrow at the target proves nothing (the first live pre-device run, 2026-11-12 19:06, had every
     * arrow lantern dark after the arrows were turned to the table's targets, before Goldor's phase).
     */
    private fun contradicts(l: IntArray): Boolean {
        for (f in frames.values) {
            val v = l[f.cell]
            if (f.kind == ARROW && v >= 0 && f.lantern == true && f.rot != v) return true
        }
        return false
    }

    /** Every frame as "r1c1:arrow/rot3/lit" for the log and the dev bridge. */
    fun dump(): String = frames.values.sortedBy { it.cell }.joinToString(" ") {
        val kind = when (it.kind) { ARROW -> "arrow"; START -> "start"; END -> "end"; else -> "other" }
        "${name(it.cell)}:$kind/rot${it.rot}/${when (it.lantern) { true -> "lit"; false -> "dark"; null -> "?" }}"
    }

    /** What the solver believes, for the dev bridge `soul` provider (`arrows`). */
    fun snapshot(): Map<String, Any?> = mapOf(
        "known" to known,
        "matching" to matching.map { it + 1 },
        "layouts" to alive.map { it + 1 },
        "done" to done,
        "hold" to hold,
        "frames" to dump(),
        "pending" to pending.entries.filter { it.value.isNotEmpty() }.associate { name(it.key) to it.value.size },
        "clicksLeft" to frames.keys.sorted().mapNotNull { c -> clicksLeft(c)?.takeIf { it > 0 }?.let { name(c) to it } }.toMap(),
    )

    /** Trust check, run before every decision and every frame: an allowed click never shown by the server drops out. */
    fun check(lastInbound: Long) {
        val it = pending.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val q = e.value
            while (q.isNotEmpty() && lastInbound - q.first() > CONFIRM_MS) {
                log("click on ${name(e.key)} not shown after ${lastInbound - q.first()} ms of server traffic")
                q.removeFirst()
            }
            if (q.isEmpty()) it.remove()
        }
    }

    /** The player's own click on the frame at [cell], before anything is sent. */
    fun click(now: Long, cell: Int, blockOn: Boolean, holdLast: Boolean = false, goldorActive: Boolean = false): Decision {
        hold = holdLast
        val f = frames[cell] ?: return Decision(false, "frame not read")
        if (f.kind != ARROW) return Decision(false, "${name(cell)} is not an arrow frame")
        if (completed) return Decision(false, "device completed")
        val target = target(cell)
        val own = pendingCount(cell)
        val atTarget: Boolean
        val why: String
        if (target != null) {
            atTarget = (f.rot + own) % 8 == target
            why = "${name(cell)} already points at its target ($target)" + if (own > 0) ", $own click(s) of yours still on the way" else ""
        } else {
            atTarget = own == 0 && f.lantern == true
            why = "${name(cell)}'s lantern is lit (at its target, which is not known otherwise)"
        }
        // Goldor's phase, everything aligned, nothing completed: the bottom-left-most arrow takes a full turn.
        val fullTurn = goldorActive && atTarget && cell == fullTurnArrow()
        if (atTarget && blockOn && !fullTurn) return Decision(true, why)
        // Before Goldor: the click that would finish the device waits (never when a target or the layout is not known).
        if (holdLast && target != null && clicksLeft(cell) == 1 && totalClicksLeft() == 1) {
            return Decision(true, "${name(cell)} is the last click of the device: held until Goldor starts", wrong = false, held = true)
        }
        pending.getOrPut(cell) { ArrayDeque() }.addLast(now)
        val left = clicksLeft(cell)
        if (fullTurn) return Decision(false, "${name(cell)}: a full turn of the aligned board (it completes on a click; unverified)", wrong = false)
        return if (atTarget) Decision(false, "$why (blocking off)", wrong = true) else Decision(false, "${name(cell)}" + if (left != null) ", $left click(s) left" else ", target not known")
    }
}
