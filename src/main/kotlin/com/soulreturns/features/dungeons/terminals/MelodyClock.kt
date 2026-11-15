package com.soulreturns.features.dungeons.terminals

/**
 * Where the active melody lane's green pane is, and will be, on this client.
 *
 * Hypixel (recordings 2026-11-11/12) moves the pane one column every 10 server ticks (459-546 ms apart on
 * the client), bouncing between columns 1 and 5, and judges a lock against the column the pane has when
 * the click is processed. The client draws that column about one click-to-echo latency late, so a click
 * made now is judged against what this client will show one latency from now, less one server tick: a click
 * handled in the same tick as the step still sees the old column. Evidence (runs 2026-11-11/12): early locks
 * landing 45-173 ms after the expected step were accepted, one landing 37 ms after it was rejected (run 3),
 * one 184 ms before it was rejected (Odin run), a shown-aligned lock landing 27 ms after it was accepted.
 *
 * An accepted lock hands over at once: the next lane is the server's from that tick, its pane in the locked
 * column with a new target, and the step loop goes on. The client sees the new lane only at the next step,
 * the pane already one column on (54 of 54 lane changes, 2026-11-12), or still in the locked column when a
 * click on it in between was wrong and paused it. A click on the next lane before that step is judged against
 * the locked column and the unseen target: the player's "skip" when the target happens to be directly below
 * (recording 2026-11-12 22:42, cid 29).
 */
class MelodyClock {
    companion object {
        /** 10 server ticks. */
        const val STEP_MS = 500L

        /** Arrival jitter of steps and echoes on Hypixel (steps 459-546 ms, echoes 136-191 ms). */
        const val JITTER_MS = 100L

        /** How long a wrong lock stops the pane: 20 server ticks. */
        const val HOLD_MS = 1000L

        /** One server tick: a click is judged at the tick that handles it, before that tick's step. */
        const val TICK_MS = 50L

        /** A step this late is a pause (wrong lock: 1-1.5 s), not a late packet (run 3: one step arrived 180 ms late). */
        const val PAUSE_MS = 300L

        fun advance(col: Int, dir: Int, steps: Int): Int {
            var c = col
            var d = dir
            repeat(steps) {
                if (c + d !in 1..5) d = -d
                c += d
            }
            return c
        }

        /** The direction after one step from [col] going [dir] (it flips at the ends). */
        fun turn(col: Int, dir: Int): Int = if (col + dir !in 1..5) -dir else dir
    }

    private var live = false
    private var lane = -1
    private var col = 0
    private var dir = 0
    private var stepAt = 0L
    /** After a wrong lock: when the pause ends (client time); 0 = not paused. */
    private var heldUntil = 0L
    /** [stepAt] is a step's arrival (not the menu opening), so the gap to the next one is a step interval. */
    private var onStep = false
    private val intervals = ArrayDeque<Long>()

    /** The column shown right now and when it arrived (for logs). */
    val shown: Int get() = col
    fun stepAge(now: Long): Long = now - stepAt

    /** Feed the server board's active lane and its pane column (null = none or no active lane). */
    fun observe(activeLane: Int?, column: Int?, now: Long) {
        if (activeLane == null || column == null) {
            live = false
            return
        }
        if (activeLane != lane) {
            // The pane carries on into the next lane: one step on, or still in the locked column when paused by a
            // wrong click on the new lane before its first step showed.
            val next = lane >= 0 && dir != 0 && activeLane > lane
            val stepped = next && column == advance(col, dir, 1)
            val stayed = next && column == col
            dir = if (stepped) turn(col, dir) else if (stayed) dir else if (column == 1) 1 else if (column == 5) -1 else 0
            if (!stayed) heldUntil = 0L
            // A hand-over is shown on a step; the first lane only shows up when the menu's contents arrive.
            onStep = lane >= 0
            lane = activeLane
            live = true
            col = column
            stepAt = now
            return
        }
        live = true
        if (column == col) return
        val gap = now - stepAt
        // Late-delivered steps come in pairs (680 + 352 ms); the median of the last eight shrugs them off.
        // Neither the menu's first contents nor a pause end at a step's normal time.
        if (onStep && heldUntil == 0L && gap in 250L..1000L) {
            intervals.addLast(gap)
            if (intervals.size > 8) intervals.removeFirst()
        }
        dir = Integer.signum(column - col)
        col = column
        stepAt = now
        onStep = true
        heldUntil = 0L
    }

    /**
     * The server said our lock was wrong (error sound): the pane stands still for [HOLD_MS], then moves again on
     * the step loop's next step (recording 2026-11-12 22:42: errors at 1902/1944/2650 ms, moves at 2992/2967/3963).
     */
    fun hold(now: Long) {
        heldUntil = now + HOLD_MS
    }

    /** When the next step is due: one period after the last, or the loop's first step after a pause. */
    private fun nextStepAt(p: Long): Long =
        if (heldUntil == 0L) stepAt + p else stepAt + p * maxOf(1L, (heldUntil - stepAt + p - 1) / p)

    /** Typical step on this server: median of the last steps, [STEP_MS] until there are any. */
    val period: Long
        get() = if (intervals.isEmpty()) STEP_MS else intervals.sorted()[intervals.size / 2]

    /** A step is long overdue: the lane is paused (a wrong lock we did not hear), so no further steps are predicted. */
    private fun paused(now: Long): Boolean = now > nextStepAt(period) + PAUSE_MS

    /** Columns the pane may show at any time between `now + from` and `now + to` ([from] <= [to]). */
    fun columnsBetween(now: Long, from: Long, to: Long): Set<Int> {
        if (!live) return emptySet()
        if (paused(now)) return setOf(col)
        val p = period
        val first = nextStepAt(p)
        fun steps(t: Long): Int = if (t < first) 0 else (1 + (t - first) / p).toInt()
        val n0 = steps(now + from)
        val n1 = steps(now + to)
        val out = HashSet<Int>()
        for (n in n0..n1) {
            if (dir == 0) {
                out += advance(col, 1, n)
                out += advance(col, -1, n)
            } else {
                out += advance(col, dir, n)
            }
        }
        return out
    }

    /**
     * The column a click made now is judged against: what this client will show [latency] ms from now.
     * Null when it cannot be told (no active lane, direction still unknown and a step falls in between).
     */
    fun columnFor(now: Long, latency: Long): Int? = columnsBetween(now, latency, latency).singleOrNull()

    /** The NOW cue: a click made now is judged on [target] (one latency ahead, less one tick). */
    fun nowCue(target: Int, now: Long, latency: Long): Boolean = live && columnFor(now, latency - TICK_MS) == target

    /**
     * Could a lock sent now be accepted? Yes when the client shows alignment now, or when the target can be
     * the server's column anywhere in the latency +- jitter window. When unsure it says yes: a wrong lock
     * only pauses the lane for about a second.
     */
    fun mayAccept(target: Int, now: Long, latency: Long): Boolean {
        if (!live) return false
        if (col == target) return true
        val judged = latency - TICK_MS
        return target in columnsBetween(now, (judged - JITTER_MS).coerceAtLeast(0), judged + JITTER_MS)
    }
}

/** Click-to-echo latency on this server, from the player's own terminal clicks of the last few minutes. */
class LatencyEstimate(private val keep: Int = 8, private val maxAgeMs: Long = 5 * 60_000L) {
    companion object {
        /** Hypixel's echoes in the 2026-11-12 runs: 136-191 ms, median ~155. */
        const val DEFAULT_MS = 150L
    }

    private class Sample(val ms: Long, val at: Long)

    private val samples = ArrayDeque<Sample>()

    fun add(ms: Long, at: Long) {
        if (ms !in 20L..1500L) return
        samples.addLast(Sample(ms, at))
        if (samples.size > keep) samples.removeFirst()
    }

    /**
     * Median of the recent samples, else [fallback] when it looks real (Hypixel's player list says 1 ms),
     * else [DEFAULT_MS]. Old samples (another server, an earlier lag spike) are dropped.
     */
    fun estimate(now: Long, fallback: Long = 0): Long {
        samples.removeAll { now - it.at > maxAgeMs }
        return when {
            samples.isNotEmpty() -> samples.map { it.ms }.sorted()[samples.size / 2]
            fallback >= 20 -> fallback
            else -> DEFAULT_MS
        }
    }
}
