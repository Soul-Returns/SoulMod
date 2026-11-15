package com.soulreturns.features.dungeons.devices

/**
 * Lights (F7/M7 Goldor phase, section 2) as the client can know it: which levers to flip next, and which of
 * the player's own lever clicks to hold back. Pure (no Minecraft types): [Lights] and the recording
 * fixtures feed it the same calls. Ground truth: `docs/research/f7-devices-2026-11-12.md` §10.
 *
 * The grid is 5 columns x 4 rows of `lever[face=wall,facing=north]` at x 58..62, y 133..136, z 142, one
 * `redstone_lamp` behind each at z 143. Cells are indexed `(136 - y) * 5 + (62 - x)`: 0 = top left as the
 * player sees it (facing south), 19 = bottom right, named `r<row>c<col>`.
 *
 * A flip lights the lever's own lamp and its orthogonal neighbours; a lamp lit twice stays lit. Any lever
 * may be flipped (other covers than the usual six are legitimate, the user says). The only click held back
 * is one on a lever that is already flicked while some lamp is dark: it would turn lights off.
 *
 * Every lamp lit is not the same as complete: the server completes the device (`completed a device!` and
 * the pling, same tick) when the last lamp lights in Goldor's phase, but a grid lit in Storm's phase stays
 * incomplete, with its levers on, until someone touches a lever in Goldor's phase (M7 party recordings,
 * 2026-11-12: 5 of 5). So the device is [done] only with a completion line within [LINE_MS] of a change of
 * the lit grid; lit without one is [preFinished]: any lever not on yet completes it.
 *
 * The suggestion is the fewest levers that still light every dark lamp, among the levers not flicked yet
 * and counting the player's own clicks the server has not shown; the six levers every recorded run used
 * win a tie, so a fresh device suggests exactly them.
 */
class LightsModel(private val log: (String) -> Unit = {}) {
    companion object {
        const val COLS = 5
        const val ROWS = 4
        const val SIZE = COLS * ROWS
        const val ALL = (1 shl SIZE) - 1

        /** The first lever's column x (the right edge as the player sees it) and the grid's top row y. */
        const val X_RIGHT = 62
        const val Y_TOP = 136

        /** An own click whose lever the server has not shown on after this much server traffic is no longer assumed flipped. */
        const val CONFIRM_MS = 700L

        /** A completion line this close to a change of the lit grid is this device's (chat and blocks share the server tick). */
        const val LINE_MS = 1000L

        private const val NONE = Long.MIN_VALUE

        fun index(x: Int, y: Int): Int = (Y_TOP - y) * COLS + (X_RIGHT - x)
        fun x(index: Int): Int = X_RIGHT - index % COLS
        fun y(index: Int): Int = Y_TOP - index / COLS
        fun name(index: Int): String = "r${index / COLS + 1}c${index % COLS + 1}"

        /** The levers all seven recorded runs flipped: (62,136) (58,136) (60,135) (60,134) (62,133) (58,133). */
        val SOLUTION: Int = intArrayOf(index(62, 136), index(58, 136), index(60, 135), index(60, 134), index(62, 133), index(58, 133))
            .fold(0) { m, i -> m or (1 shl i) }

        /** The lamps a flip of [i] lights: its own and the orthogonal neighbours inside the grid. */
        fun plus(i: Int): Int {
            val c = i % COLS
            val r = i / COLS
            var m = 1 shl i
            if (c > 0) m = m or (1 shl (i - 1))
            if (c < COLS - 1) m = m or (1 shl (i + 1))
            if (r > 0) m = m or (1 shl (i - COLS))
            if (r < ROWS - 1) m = m or (1 shl (i + COLS))
            return m
        }

        /**
         * The fewest levers in [available] whose lamps together cover [dark] (a fresh grid: six), the six usual
         * levers winning ties, then the lowest cell numbers. 0 when nothing is dark; also 0 when [available] cannot cover it.
         */
        fun cover(dark: Int, available: Int): Int {
            if (dark == 0) return 0
            val cells = (0 until SIZE).filter { available and (1 shl it) != 0 && plus(it) and dark != 0 }
            for (k in 1..cells.size) {
                var best = 0
                var bestScore = -1
                // Combinations of k cells in ascending order; the first with the best score wins.
                val pick = IntArray(k)
                fun go(from: Int, depth: Int, covered: Int) {
                    if (depth == k) {
                        if (covered and dark != dark) return
                        var m = 0
                        var score = 0
                        for (c in pick) {
                            m = m or (1 shl cells[c])
                            if (SOLUTION and (1 shl cells[c]) != 0) score++
                        }
                        if (score > bestScore) {
                            bestScore = score
                            best = m
                        }
                        return
                    }
                    for (c in from..cells.size - (k - depth)) {
                        pick[depth] = c
                        go(c + 1, depth + 1, covered or plus(cells[c]))
                    }
                }
                go(0, 0, 0)
                if (best != 0) return best
            }
            return 0
        }

        fun names(mask: Int): String = (0 until SIZE).filter { mask and (1 shl it) != 0 }.joinToString(" ") { name(it) }
    }

    /** Why a click was let through or held back; [blocked] = nothing is sent. */
    data class Decision(val blocked: Boolean, val reason: String)

    private var known = false
    private var levers = 0
    private var lamps = 0

    /** Own clicks that were let through, with their send time (client clock) and the last server traffic when sent. */
    private val pending = LinkedHashMap<Int, Long>()

    /** The grid is readable (all 20 levers and lamps seen): shows and blocks apply. */
    val isKnown: Boolean get() = known

    /** Levers that are on, with the own clicks not yet shown by the server counted. */
    private fun leversNow(): Int = pending.keys.fold(levers) { m, i -> m or (1 shl i) }

    /** Lamps lit with every flicked lever's own and neighbours' counted (the lever's answer comes a tick before its lamps). */
    private fun lampsWith(on: Int): Int {
        var m = lamps
        for (i in 0 until SIZE) if (on and (1 shl i) != 0) m = m or plus(i)
        return m
    }

    private fun lampsNow(): Int = lampsWith(leversNow())

    /** Every lamp is lit in the server's own state (the player's own unanswered clicks do not count). */
    val allLit: Boolean get() = known && lampsWith(levers) == ALL

    /** The server completed the device: a completion line within [LINE_MS] of a change of the lit grid. */
    private var completed = false

    /** When the server's lit grid last changed (the last lamps lit, or a lever went on with every lamp lit). */
    private var litChangeAt = NONE

    /** When the last completion line came. */
    private var lineAt = NONE

    /** Complete: nothing left to light, nothing held back. */
    val done: Boolean get() = allLit && completed

    /**
     * Every lamp lit but not complete (lit in Storm's phase), no own click on the way: touching any lever that
     * is not on yet completes it.
     */
    val preFinished: Boolean get() = allLit && !completed && pending.isEmpty()

    private var suggestion = 0
    private var suggestionKey = -1L

    /** The levers to flip next as a bit mask: the fewest that light every dark lamp (see the class doc); 0 when unknown or done. */
    fun remainingMask(): Int {
        if (!known) return 0
        val on = leversNow()
        val lit = lampsNow()
        val key = (on.toLong() shl 20) or lit.toLong()
        if (key != suggestionKey) {
            suggestionKey = key
            suggestion = cover(lit.inv() and ALL, on.inv() and ALL)
        }
        return suggestion
    }

    fun remaining(): List<Int> {
        val m = remainingMask()
        return (0 until SIZE).filter { m and (1 shl it) != 0 }
    }

    fun reset(reason: String) {
        if (known) log("reset: $reason")
        known = false
        levers = 0
        lamps = 0
        pending.clear()
        suggestionKey = -1L
        completed = false
        litChangeAt = NONE
        lineAt = NONE
    }

    /**
     * The grid as the client's world shows it at [now] (all 20 levers and lamps readable); [leverMask] bit i =
     * lever i powered, [lampMask] = lamp i lit.
     */
    fun observe(leverMask: Int, lampMask: Int, now: Long) {
        val first = !known
        known = true
        // A confirmed click is just the world now.
        val own = pending.iterator()
        while (own.hasNext()) if (leverMask and (1 shl own.next().key) != 0) own.remove()
        if (first || leverMask != levers || lampMask != lamps) {
            if (first) log("grid read: levers ${names(leverMask)}, lamps ${Integer.bitCount(lampMask)}/$SIZE")
            levers = leverMask
            lamps = lampMask
            // A grid first seen lit has no change to match a line against: lit in Storm's phase or completed while away, unknown.
            if (allLit && !first) {
                litChangeAt = now
                if (!completed) log("all lamps lit (levers ${names(levers)})")
            }
        }
        settle()
    }

    /** A `completed a device!` line at [now] (any player's, any device's: [LINE_MS] decides whether it is this one). */
    fun completion(now: Long) {
        if (!known) return
        lineAt = now
        settle()
    }

    private fun settle() {
        if (completed || !allLit || litChangeAt == NONE || lineAt == NONE) return
        if (kotlin.math.abs(lineAt - litChangeAt) > LINE_MS) return
        completed = true
        log("device complete (line ${lineAt - litChangeAt} ms from the grid's last change)")
    }

    /** Trust check, run before every decision and every frame: an allowed click never shown by the server drops out. */
    fun check(lastInbound: Long) {
        val own = pending.iterator()
        while (own.hasNext()) {
            val e = own.next()
            if (lastInbound - e.value > CONFIRM_MS) {
                log("click on ${name(e.key)} not shown after ${lastInbound - e.value} ms of server traffic")
                own.remove()
            }
        }
    }

    /** The player's own click on lever [i], before anything is sent. */
    fun leverClick(now: Long, i: Int, blockOn: Boolean): Decision {
        if (!known) return Decision(false, "grid not read")
        if (done) return Decision(false, "device complete")
        if (allLit) {
            // Lit, not complete: a touch on any lever completes it (user); nothing is held back. A lever not on yet counts as flicked until shown.
            if (leversNow() and (1 shl i) == 0) pending[i] = now
            return Decision(false, "every lamp is lit: ${name(i)} completes it")
        }
        if (leversNow() and (1 shl i) != 0) {
            val why = "${name(i)} is already flicked (it would turn lights off)"
            return if (blockOn) Decision(true, why) else Decision(false, "$why (blocking off)")
        }
        pending[i] = now
        val left = Integer.bitCount(remainingMask())
        val tag = if (SOLUTION and (1 shl i) != 0) "a usual lever" else "not one of the usual six"
        return Decision(false, "${name(i)} ($tag), $left more suggested")
    }
}
