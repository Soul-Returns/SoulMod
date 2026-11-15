package com.soulreturns.features.dungeons.devices

/**
 * Simon Says (F7/M7 Goldor phase, section 1) as the client can know it: what the lamp wall showed,
 * which buttons the server expects next, and which of the player's own clicks to hold back.
 * Pure (no Minecraft types): the live glue ([SimonSays]) and the recording fixtures feed it the same
 * calls. Ground truth: `docs/research/f7-devices-2026-11-12.md`.
 *
 * Positions are grid indices `(123 - y) * 4 + (z - 92)`: 0 = top left as the player sees the wall
 * (facing east), 15 = bottom right. A lamp at (111, y, z) belongs to the button at (110, y, z).
 *
 * Time is one monotonic millisecond clock for everything (live: the client's clock; fixtures: the
 * recorded arrival time).
 *
 * Never guesses: [solution] is null (show nothing, block nothing) unless the round's whole display
 * was seen and its kind is one the recordings settle: a single start press, the round after a round
 * this client pressed to the end, a round started by someone else with nothing unusual about it, or
 * the three-press skip (three lamps, the buttons up while the third is still lit: the last two count).
 */
class SimonSaysModel(private val log: (String) -> Unit = {}) {
    companion object {
        const val SIZE = 16

        /** Start presses this close (client send times) are one burst. Recorded bursts: 2-4 ticks apart. */
        const val BURST_GAP_MS = 500L

        /** After a three-press burst, further start presses are held back until the round is decided, at most this long after the third. */
        const val FOURTH_BLOCK_MS = 2000L

        /** An allowed press whose pling has not come after this much server traffic: the state is no longer trusted. */
        const val CONFIRM_MS = 1500L

        /** A display with no lamp or grid change for this long is abandoned (the final round has no next display). */
        const val DISPLAY_STALE_MS = 4000L

        /** A start press with no display after this long no longer claims the next one. */
        const val START_CLAIM_MS = 3000L

        fun index(y: Int, z: Int): Int = (123 - y) * 4 + (z - 92)
        fun y(index: Int): Int = 123 - index / 4
        fun z(index: Int): Int = 92 + index % 4
        fun name(index: Int): String = "r${index / 4 + 1}c${index % 4 + 1}"
        fun names(list: List<Int>) = list.joinToString(" ") { name(it) }
    }

    enum class Origin { STARTS, CONTINUE, OTHER }

    /** Why a press was let through or held back; [blocked] = nothing is sent. */
    data class Decision(val blocked: Boolean, val reason: String)

    // ---- what the client sees ----
    private var baseline = false
    private var lamps = 0
    private var buttons = 0

    // ---- the current round's display ----
    private var displayActive = false
    /** Lamps seen without the display's start (arrived mid-display, lost track): ignored until the next fresh start. */
    private var broken = false
    private var origin = Origin.OTHER
    private val shown = ArrayList<Int>()
    /** The grid was up while a lamp of this display was lit (two displays running at once: the skip). */
    private var overlap = false
    private var lastChangeMs = 0L
    /** Plings heard while the round was undecided (answers to [early] presses). */
    private var undecidedPlings = 0

    // ---- the player's own start presses (sent) ----
    private val starts = ArrayList<Long>()
    /** Server error sounds (enderman teleport) heard during the burst / display. */
    private var teleports = 0
    private var startPending = false

    // ---- the input phase ----
    /** What the server expects this round, in order; null = not known. */
    var solution: List<Int>? = null
        private set
    /** Presses of [solution] this client sent (each the expected one). */
    var pressed = 0
        private set
    private val pending = ArrayDeque<Long>()
    /** Presses sent while the buttons were up but the round was not decided yet (the skip's third lamp still lit). */
    private val early = ArrayList<Pair<Long, Int>>()
    /**
     * Presses sent while the round is known but the grid not complete (or while earlier tentative ones are unanswered):
     * never blocked, not in [pressed] until a pling confirms them. FIFO of (time, index).
     */
    private val tentative = ArrayDeque<Pair<Long, Int>>()
    /** The last round this client pressed to the end: the next display must be it plus one lamp. */
    private var finished: List<Int>? = null

    /** The display that led to [solution] was the three-press skip (first lamp dropped). */
    var skip = false
        private set

    val gridUp: Boolean get() = Integer.bitCount(buttons) >= 8

    /** The button to press now, or null. */
    fun next(): Int? = solution?.getOrNull(pressed + tentative.size)

    /** The button after [next], or null. */
    fun after(): Int? = solution?.getOrNull(pressed + tentative.size + 1)

    fun remaining(): Int = solution?.let { it.size - pressed - tentative.size } ?: 0

    /** A lamp-wall cell to tint: [order] is its place in the round's sequence (1-based), [next] the one to press now, [certain] false = a candidate. */
    data class WallCell(val index: Int, val order: Int, val next: Boolean, val certain: Boolean)

    /**
     * What the lamp wall behind the buttons should show. While the round is decided: the next press and (with [showAfter])
     * the one after it. While the lamps are still being shown (before any button): every element of the sequence the display
     * has already proven, from the moment its lamp lit, numbered in order. Display only: never used to block.
     *
     * Proven early only for the patterns [decide] accepts, and never wrongly as certain: one own start press (a candidate
     * until [BURST_GAP_MS] after it, since presses 2 and 3 would turn the first lamp into the skip's uncounted one), the
     * skip (three own presses: the first lamp does not count, from the second lamp on), the round after one this client
     * pressed to the end (every lamp must repeat that round's, the last one is new). Someone else's display: nothing.
     */
    fun wall(now: Long, showAfter: Boolean): List<WallCell> {
        val s = solution
        if (s != null) {
            val p = pressed + tentative.size
            val out = ArrayList<WallCell>(2)
            if (p < s.size) out += WallCell(s[p], p + 1, true, true)
            if (showAfter && p + 1 < s.size) out += WallCell(s[p + 1], p + 2, false, true)
            return out
        }
        if (!baseline || !displayActive || shown.isEmpty()) return emptyList()
        var certain = true
        val cells: List<Int> = when (origin) {
            Origin.STARTS -> when {
                starts.size == 1 && !overlap && teleports == 0 -> {
                    certain = now - starts.last() > BURST_GAP_MS
                    shown
                }
                starts.size == 3 && teleports <= 1 && shown.size >= 2 -> shown.subList(1, shown.size)
                else -> return emptyList()
            }
            Origin.CONTINUE -> {
                val last = finished ?: return emptyList()
                if (overlap || teleports != 0 || shown.size > last.size + 1) return emptyList()
                for (k in 0 until minOf(shown.size, last.size)) if (shown[k] != last[k]) return emptyList()
                shown
            }
            Origin.OTHER -> return emptyList()
        }
        return cells.mapIndexedNotNull { k, i -> if (k == 0 || showAfter) WallCell(i, k + 1, k == 0, certain) else null }
    }

    /** Whether the world has a button at grid index [i] right now (the last observed mask). */
    fun buttonUp(i: Int): Boolean = buttons and (1 shl i) != 0

    private val gridComplete: Boolean get() = Integer.bitCount(buttons) == SIZE

    private fun skipPatternAtLampsOff(gridNow: Boolean): Boolean =
        origin == Origin.STARTS && starts.size == 3 && shown.size == 3 && overlap && gridNow

    /** Everything forgotten: chunk unloaded, world change, far away. */
    fun reset(reason: String) {
        if (baseline) log("reset: $reason")
        baseline = false
        lamps = 0
        buttons = 0
        displayActive = false
        broken = false
        shown.clear()
        overlap = false
        solution = null
        pressed = 0
        pending.clear()
        early.clear()
        tentative.clear()
        finished = null
        skip = false
        startPending = false
        starts.clear()
        teleports = 0
    }

    /** The lamp and button masks (bit = grid index) as the world holds them now. */
    fun observe(now: Long, lampMask: Int, buttonMask: Int) {
        if (!baseline) {
            baseline = true
            lamps = lampMask
            buttons = buttonMask
            lastChangeMs = now
            displayActive = false
            // A lamp lit = mid-display: its start is gone. Buttons up = mid-round: wait for their removal.
            broken = lampMask != 0
            if (lampMask != 0 || Integer.bitCount(buttonMask) >= 8) {
                log("first look mid-round (lamps ${names(bits(lampMask))}, ${Integer.bitCount(buttonMask)} buttons): waiting for the next display")
            }
            return
        }
        if (startPending && !displayActive && starts.isNotEmpty() && now - starts.last() > START_CLAIM_MS) startPending = false
        val wasUp = gridUp
        val upNow = Integer.bitCount(buttonMask) >= 8
        if (wasUp && !upNow) gridRemoved(now)
        val on = lampMask and lamps.inv()
        if (on != 0) {
            val list = bits(on)
            if (list.size > 1) {
                // Two new lamps in one look never happens in a clean display: the order is unknown.
                if (displayActive) invalidate(now, "${list.size} lamps lit at once") else broken = true
            } else {
                lampOn(now, list[0], upNow)
            }
        }
        if (!wasUp && upNow && displayActive && lampMask != 0) overlap = true
        if (lampMask != lamps || buttonMask != buttons) lastChangeMs = now
        lamps = lampMask
        buttons = buttonMask
        // Decided when the last lamp goes out with the grid complete; the skip is decided at that moment already (its
        // 16th button comes ~10 ticks later and the player clicks on rhythm before it). Presses before the grid is
        // complete are tentative (never blocked), see [buttonPress].
        if (displayActive && solution == null && lampMask == 0 && (Integer.bitCount(buttonMask) == SIZE || skipPatternAtLampsOff(upNow))) decide(now)
        if (displayActive && solution == null && now - lastChangeMs > DISPLAY_STALE_MS) {
            if (shown.isNotEmpty()) log("display of ${names(shown)} stalled; showing and blocking nothing")
            displayActive = false
            shown.clear()
        }
    }

    private fun gridRemoved(now: Long) {
        val done = solution
        val completed = done != null && pressed >= done.size
        finished = if (completed) done else null
        clearRound()
        beginDisplay(now, if (completed) Origin.CONTINUE else if (startPending) Origin.STARTS else Origin.OTHER)
        log("buttons removed (${if (completed) "our round of ${done!!.size} done" else "not by our last press"}), next display: ${origin.name.lowercase()}")
    }

    private fun beginDisplay(now: Long, from: Origin) {
        displayActive = true
        broken = false
        shown.clear()
        overlap = false
        undecidedPlings = 0
        origin = from
        if (from != Origin.STARTS) teleports = 0
        lastChangeMs = now
    }

    private fun lampOn(now: Long, i: Int, gridNow: Boolean) {
        if (!displayActive) {
            if (broken) return
            if (solution != null && gridNow) return invalidate(now, "a lamp lit while the round's buttons are up")
            finished = null
            beginDisplay(now, if (startPending) Origin.STARTS else Origin.OTHER)
        }
        if (gridNow && solution == null) overlap = true
        shown += i
        lastChangeMs = now
    }

    private fun decide(now: Long) {
        val list = ArrayList(shown)
        val n = starts.size
        val result: List<Int>? = when (origin) {
            Origin.CONTINUE -> finished.let { last ->
                if (last != null && !overlap && teleports == 0 && list.size == last.size + 1 && list.subList(0, last.size) == last) list else null
            }
            Origin.STARTS -> when {
                n == 1 && !overlap && teleports == 0 -> list
                n == 3 && list.size == 3 && overlap && teleports <= 1 -> list.subList(1, 3).toList()
                else -> null
            }
            Origin.OTHER -> if (!overlap && teleports == 0) list else null
        }
        displayActive = false
        startPending = false
        if (result == null || result.isEmpty()) {
            val why = when (origin) {
                Origin.STARTS -> "$n start press${if (n == 1) "" else "es"}, overlap $overlap, $teleports error sound(s): not a known pattern"
                Origin.CONTINUE -> "not our last round plus one lamp (ours: ${finished?.let { names(it) }}), overlap $overlap"
                Origin.OTHER -> "started by someone else, overlap $overlap, $teleports error sound(s)"
            }
            log("round unknown: shown ${names(list)} ($why); showing and blocking nothing")
            clearRound()
            finished = null
            return
        }
        skip = origin == Origin.STARTS && n == 3
        solution = result
        pressed = 0
        log("round of ${result.size}: ${names(result)}" + (if (skip) " (skip: shown ${names(list)}, the first lamp does not count)" else "") + " [${origin.name.lowercase()}]")
        // Presses sent before the round was decided (clicks racing the display's end): tentative if they match, each
        // confirmed by its pling (already heard ones now); one the server ignored times out in [check].
        val before = ArrayList(early)
        early.clear()
        for ((at, p) in before) {
            val pos = tentative.size
            if (pos < result.size && result[pos] == p) {
                tentative.addLast(at to p)
            } else {
                invalidate(now, "an early press ${name(p)} was not ${result.getOrNull(pos)?.let { name(it) }}")
                return
            }
        }
        repeat(minOf(undecidedPlings, tentative.size)) {
            tentative.removeFirst()
            pressed++
        }
        undecidedPlings = 0
    }

    private fun clearRound() {
        solution = null
        pressed = 0
        pending.clear()
        early.clear()
        tentative.clear()
        skip = false
    }

    private fun invalidate(now: Long, why: String) {
        if (solution != null || displayActive) log("state lost: $why; showing and blocking nothing")
        clearRound()
        displayActive = false
        broken = true
        finished = null
        lastChangeMs = now
    }

    /** `block.note_block.pling` (volume 8) near the device: an accepted press. */
    fun pling(now: Long) {
        if (tentative.isNotEmpty()) {
            tentative.removeFirst()
            pressed++
            return
        }
        if (pending.isNotEmpty()) {
            pending.removeFirst()
            return
        }
        if (solution == null && displayActive) {
            undecidedPlings++
            return
        }
        val s = solution
        if (s != null && pressed < s.size) invalidate(now, "a press we did not send was accepted (someone else is pressing)")
    }

    /** `entity.enderman.teleport` (volume 8, pitch 0) near the device: the server's error sound. */
    fun error(now: Long) {
        if (startPending || (starts.isNotEmpty() && now - starts.last() <= BURST_GAP_MS * 2)) {
            teleports++
            return
        }
        if (solution != null || displayActive) invalidate(now, "error sound")
    }

    /** Trust check, run before every decision and every frame: an allowed press never answered drops the state. */
    fun check(now: Long, lastInbound: Long) {
        val t = tentative.firstOrNull()
        if (t != null && lastInbound - t.first > CONFIRM_MS) {
            log("press(es) not taken: ${tentative.joinToString(" ") { name(it.second) }} unanswered after ${lastInbound - t.first} ms of server traffic; expecting ${solution?.getOrNull(pressed)?.let { name(it) }} again")
            tentative.clear()
        }
        val first = pending.firstOrNull() ?: return
        if (lastInbound - first > CONFIRM_MS) invalidate(now, "no answer to a press after ${lastInbound - first} ms of server traffic")
    }

    /** The player's own click on the start button, before anything is sent. */
    fun startPress(now: Long, blockOn: Boolean): Decision {
        if (blockOn && starts.size == 3 && solution == null && now - starts[2] <= FOURTH_BLOCK_MS) {
            return Decision(true, "start press 4 of a burst, ${now - starts[2]} ms after the third (it cuts the skip's display)")
        }
        if (starts.isEmpty() || now - starts.last() > BURST_GAP_MS) {
            starts.clear()
            teleports = 0
            // A fresh start: whatever ran before is replaced by the server.
            clearRound()
            finished = null
            broken = false
            if (!gridUp) {
                displayActive = false
                shown.clear()
                overlap = false
            }
        }
        starts += now
        startPending = true
        lastChangeMs = now
        return Decision(false, "start press ${starts.size}" + if (starts.size > 1) ", ${now - starts[starts.size - 2]} ms after the last" else "")
    }

    /** The player's own click on grid button [i], before anything is sent. */
    fun buttonPress(now: Long, i: Int, blockOn: Boolean): Decision {
        val s = solution
        if (s == null) {
            if (displayActive && gridUp) early += now to i
            return Decision(false, "${name(i)}: round unknown")
        }
        // The grid is not complete yet (the skip's 16th button) or earlier presses are unanswered: whether the server takes
        // this press is not known, so it is never held back. It must still be the optimistic next button.
        if (!gridComplete || tentative.isNotEmpty()) {
            val pos = pressed + tentative.size
            if (pos < s.size && s[pos] == i) {
                tentative.addLast(now to i)
                return Decision(false, "${name(i)} is press ${pos + 1}/${s.size} (tentative: ${if (gridComplete) "earlier presses unanswered" else "grid incomplete"})")
            }
            invalidate(now, "tentative press ${name(i)} went out, expected ${s.getOrNull(pos)?.let { name(it) }}")
            return Decision(false, "${name(i)}: wrong, could not be held back")
        }
        if (pressed >= s.size) {
            if (blockOn) return Decision(true, "${name(i)}: the round is done, the next display is coming")
            invalidate(now, "pressed ${name(i)} after the round was done")
            return Decision(false, "${name(i)}: round done, not blocked")
        }
        val want = s[pressed]
        if (i == want) {
            pressed++
            pending.addLast(now)
            return Decision(false, "${name(i)} is press $pressed/${s.size}")
        }
        if (blockOn) return Decision(true, "${name(i)}: next is ${name(want)} (press ${pressed + 1}/${s.size})")
        invalidate(now, "wrong press ${name(i)} sent (next was ${name(want)})")
        return Decision(false, "${name(i)}: wrong, not blocked")
    }

    private fun bits(mask: Int): List<Int> {
        val out = ArrayList<Int>(2)
        for (i in 0 until SIZE) if (mask and (1 shl i) != 0) out += i
        return out
    }
}
