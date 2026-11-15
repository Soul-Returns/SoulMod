package com.soulreturns.features.dungeons.terminals

/**
 * Solves one open terminal from the server's board plus the player's own accepted-looking clicks.
 *
 * Predictions are purely visual: a correct click shows its cell as done right away. Each one ends
 * when the server's board shows the same (confirmed) or after [timeoutMs] of server traffic without
 * that answer (the server wins). While the server sends nothing at all (a lag spike) the timeout does
 * not run: its answers are late, not missing (run 2026-11-12: a 2.8 s spike reverted four clicks,
 * the player clicked them again and got "Wrong number!"). Items that glow on their own never show a
 * selection, so their clicks stay selected for this menu.
 */
class TerminalTracker(
    val spec: TerminalSpec,
    private val timeoutMs: Long = PREDICTION_MS,
    /** Current click-to-echo latency estimate, for melody timing. */
    private val latency: () -> Long = { LatencyEstimate.DEFAULT_MS },
    /** When the last packet from the server arrived; negative = unknown (the server counts as always talking). */
    private val lastInbound: () -> Long = { -1L },
) {
    companion object {
        /** Hypixel answers in ~150 ms; long enough for slow answers, short enough to recover from a dropped click. */
        const val PREDICTION_MS = 1000L

        /** No packet for this long = the server is silent (Hypixel's normal gaps: 40-170 ms). */
        const val SILENT_MS = 250L

        /** A completion chat line and its pling or error arrive within this of each other (M7: 0-9 ms mostly, up to 49). */
        const val ECHO_MS = 50L

        /** A melody lock's pling or error sound comes 107-198 ms after the click; this much server time without one = not judged. */
        const val LOCK_ANSWER_MS = 600L

        const val TARGET = 0
        const val DONE = 1
        const val IDLE = 2
        const val UPCOMING = 3
    }

    /** One playfield cell. [rank] orders upcoming numbers (0 = next); [clicks] is rubix's signed move. */
    data class Cell(val slot: Int, val state: Int, val rank: Int = 0, val clicks: Int = 0)

    /** [melodyLocked]: the melody lane whose lock we sent and the server has not answered yet. */
    class Solution(
        val cells: Map<Int, Cell>,
        val remaining: Int,
        val total: Int,
        val rubixTarget: Int,
        val melody: MelodyState?,
        val melodyLocked: Int? = null,
    )

    /** [at] is on the [active] clock. */
    private class Pending(val at: Long, val dir: Int)

    private val type get() = spec.type
    private var board: Board = emptyList()
    private val pending = HashMap<Int, ArrayDeque<Pending>>()
    private val sessionSelected = HashSet<Int>()
    private val identity = HashMap<Int, String>()
    private val lastRubix = HashMap<Int, Int>()
    private var rubixTarget = -1
    private var total = -1

    /** Melody only: the active lane's pane over time. */
    val clock = MelodyClock()

    /** Melody: our locks waiting for their pling or error sound, in send order (lane, send time); the highest lane accepted. */
    private class Lock(val lane: Int, val sentAt: Long)
    private val locks = ArrayDeque<Lock>()
    private var acceptedLane = -1

    /** Melody: sounds heard, applied once no completion chat line claims them ([ECHO_MS]). */
    private class Answer(val accepted: Boolean, val at: Long)
    private val answers = ArrayDeque<Answer>()
    private var lastChat = Long.MIN_VALUE / 2

    /** Completion lines that have not claimed their sound yet (each line brings exactly one). */
    private var claims = 0

    /** Milliseconds during which the server was talking, the clock predictions expire on. */
    private var active = 0L
    private var lastNow = Long.MIN_VALUE

    private fun tick(now: Long) {
        if (lastNow == Long.MIN_VALUE) {
            lastNow = now
            return
        }
        if (now <= lastNow) return
        val heard = lastInbound()
        val talkingUntil = if (heard < 0) now else minOf(now, heard + SILENT_MS)
        if (talkingUntil > lastNow) active += talkingUntil - lastNow
        lastNow = now
    }

    /** Feed the server's current board (loaded and validated). */
    fun update(next: Board, now: Long) {
        tick(now)
        for (slot in type.board) {
            val item = next.getOrNull(slot) ?: continue
            val key = item.id + "|" + item.name
            val old = identity.put(slot, key)
            if (old != null && old != key && (type === TerminalType.StartsWith || type === TerminalType.Select)) {
                // A different item: the board was regenerated, nothing of ours applies any more.
                sessionSelected.remove(slot)
                pending.remove(slot)
            }
        }
        if (type === TerminalType.Rubix) {
            for (slot in type.board) {
                val c = next.getOrNull(slot)?.let { RubixColors.index(it.id) } ?: continue
                val was = lastRubix.put(slot, c) ?: continue
                var steps = RubixColors.move(was, c)
                val queue = pending[slot] ?: continue
                while (steps != 0 && queue.isNotEmpty()) {
                    val i = queue.indexOfFirst { Integer.signum(it.dir) == Integer.signum(steps) }
                    if (i < 0) break
                    steps -= queue.removeAt(i).dir
                }
            }
        } else {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (slot, queue) = it.next()
                if (confirmed(slot, queue.first(), next)) it.remove()
            }
        }
        if (type === TerminalType.Melody) {
            val m = MelodyState.read(next)
            clock.observe(m.active, m.active?.let { m.lanes[it].moving }, now)
        }
        board = next
        expire(now)
    }

    private fun confirmed(slot: Int, p: Pending, next: Board): Boolean {
        val item = next.getOrNull(slot) ?: return false
        return when (type) {
            TerminalType.StartsWith, TerminalType.Select -> item.glintPatch || item.intrinsicGlint
            TerminalType.Panes -> (item.id == PANE_LIME) == (p.dir == 1)
            TerminalType.Order -> item.id == PANE_LIME
            // Answered once the server has moved past the lane (a rejected lock keeps its lane until the error sound).
            TerminalType.Melody -> MelodyState.read(next).active.let { it != null && it > slot / 9 - 1 }
            else -> false
        }
    }

    private fun expire(now: Long) {
        tick(now)
        while (answers.isNotEmpty() && now - answers.first().at > ECHO_MS) settle(answers.removeFirst(), now)
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val q = it.next().value
            q.removeAll { active - it.at > timeoutMs }
            if (q.isEmpty()) it.remove()
        }
        // Hypixel answers every lock it judges; none at all means it was not judged.
        while (locks.isNotEmpty()) {
            val button = (locks.first().lane + 1) * 9 + 7
            val sent = pending[button]?.lastOrNull()
            if (sent != null && active - sent.at <= LOCK_ANSWER_MS) break
            pending.remove(button)
            locks.removeFirst()
        }
    }

    private fun item(slot: Int): TermItem? = board.getOrNull(slot)

    private fun selected(slot: Int): Boolean {
        val i = item(slot) ?: return false
        if (slot in pending || slot in sessionSelected) return true
        return !i.intrinsicGlint && i.glintPatch
    }

    private fun matches(slot: Int): Boolean {
        val i = item(slot) ?: return false
        return if (type === TerminalType.StartsWith) Matchers.startsWith(spec.param, i) else Matchers.color(spec.param, i)
    }

    private fun paneOn(slot: Int): Boolean {
        val p = pending[slot]?.lastOrNull()
        return if (p != null) p.dir == 1 else item(slot)?.id == PANE_LIME
    }

    private fun orderDone(slot: Int): Boolean = slot in pending || item(slot)?.id == PANE_LIME

    /** Board cells still to click in number order. */
    private fun orderQueue(): List<Int> = type.board.filter { !orderDone(it) }.sortedBy { item(it)?.count ?: Int.MAX_VALUE }

    private fun rubixColor(slot: Int): Int {
        val base = item(slot)?.let { RubixColors.index(it.id) } ?: return -1
        val shift = pending[slot]?.sumOf { it.dir } ?: 0
        return Math.floorMod(base + shift, 5)
    }

    private fun rubixColors(): List<Int> = type.board.map { rubixColor(it) }.filter { it >= 0 }

    /** The cheapest color on the first full board, then locked for the whole menu (like Odin): never flips mid-solve. */
    private fun currentRubixTarget(): Int {
        if (rubixTarget < 0) rubixTarget = RubixColors.cheapest(rubixColors())
        return rubixTarget
    }

    private fun melody(): MelodyState {
        val m = MelodyState.read(board)
        if (m.active != null && m.lanes[m.active].button in pending) {
            // Locked by us, waiting for the server to move on.
            return m.copy(active = null, lanes = m.lanes.mapIndexed { i, l -> if (i == m.active) l.copy(active = false) else l })
        }
        return m
    }

    /** Would a click with [button] on [slot] be correct right now (including our predictions)? */
    fun isCorrect(slot: Int, button: Int, now: Long): Boolean {
        expire(now)
        if (button != LEFT && button != RIGHT) return false
        if (slot !in type.board || item(slot) == null) return false
        return when (type) {
            TerminalType.StartsWith, TerminalType.Select -> matches(slot) && !selected(slot)
            TerminalType.Panes -> !paneOn(slot)
            TerminalType.Order -> orderQueue().firstOrNull() == slot
            TerminalType.Rubix -> {
                val c = rubixColor(slot)
                val move = RubixColors.move(c, currentRubixTarget())
                move != 0 && (move > 0) == (button == LEFT)
            }
            TerminalType.Melody -> {
                val m = melody()
                val target = m.target
                if (m.active != null) {
                    target != null && m.lanes[m.active].button == slot && clock.mayAccept(target, now, latency())
                } else {
                    // Our lock is out: if accepted, the next lane is already the server's, its pane in the locked
                    // column, its target unseen until the next step. Vanilla could win that gamble: never block it.
                    val next = lockedThrough() + 1
                    next in 1 until m.lanes.size && slot == m.lanes[next].button && slot !in pending
                }
            }
        }
    }

    /** Melody: the highest lane whose lock we sent and the server did not reject. */
    private fun lockedThrough(): Int = maxOf(acceptedLane, pending.keys.maxOfOrNull { it / 9 - 1 } ?: -1)

    /**
     * A melody sound that may answer our oldest open lock: Hypixel's pling (accepted, the next lane is the
     * server's at once) or error sound (wrong, the lane pauses), 107-198 ms after the click (recordings
     * 2026-11-12). In a party the same pling and error come with every teammate's terminal, lever and device
     * and with "already activated" refusals, each with one chat line within a few ms (M7 2026-11-12 00:39:28: a
     * teammate's device pling 65 ms after our click was taken as our accept): applied after [ECHO_MS] unless a
     * line claimed it. A line claims one sound only: our pling can share the window with a teammate's (M7
     * 2026-11-13 16:50:49 two plings in one ms and one line; 16:56:02 line, their pling 6 ms later, ours 28 ms).
     * Returns whether it was taken as a candidate.
     */
    fun melodySound(accepted: Boolean, now: Long): Boolean {
        expire(now)
        if (type !== TerminalType.Melody || locks.isEmpty()) return false
        if (now - lastChat > ECHO_MS) claims = 0
        if (claims > 0) {
            claims--
            return false
        }
        answers.addLast(Answer(accepted, now))
        return true
    }

    /** A completion or refusal chat line: one melody sound within [ECHO_MS] of it is its own, not our lock's. Returns whether one was dropped. */
    fun melodyChat(now: Long): Boolean {
        if (now - lastChat > ECHO_MS) claims = 0
        lastChat = now
        val near = answers.filter { now - it.at <= ECHO_MS }.maxByOrNull { it.at }
        if (near != null) {
            answers.remove(near)
            return true
        }
        claims++
        return false
    }

    private fun settle(a: Answer, now: Long) {
        // A sound answers only a lock sent before it; one with no such lock open was someone else's.
        val lock = locks.firstOrNull()?.takeIf { it.sentAt <= a.at } ?: return
        locks.removeFirst()
        if (a.accepted) {
            acceptedLane = maxOf(acceptedLane, lock.lane)
            return
        }
        pending.remove((lock.lane + 1) * 9 + 7)
        clock.hold(a.at)
    }

    /** Why [isCorrect] said no, for the log. */
    fun explain(slot: Int, button: Int, now: Long): String {
        val i = item(slot) ?: return "empty slot"
        return when (type) {
            TerminalType.StartsWith, TerminalType.Select -> if (!matches(slot)) "no match: ${i.name}" else "already selected: ${i.name}"
            TerminalType.Panes -> "already on"
            TerminalType.Order -> {
                val next = orderQueue().firstOrNull()?.let { item(it)?.count }
                if (orderDone(slot)) "#${i.count} already done" else "#${i.count}, next is #$next"
            }
            TerminalType.Rubix -> {
                val move = RubixColors.move(rubixColor(slot), currentRubixTarget())
                if (move == 0) "already the target color" else "needs ${Math.abs(move)} ${if (move > 0) "left" else "right"}, got ${if (button == LEFT) "left" else "right"}"
            }
            TerminalType.Melody -> {
                val m = melody()
                "shown column ${clock.shown}, target ${m.target}, step age ${clock.stepAge(now)} ms, latency ${latency()} ms, active lane ${m.active}"
            }
        }
    }

    /** Record a click the player just sent that [isCorrect] approved: show it as done until the server answers. */
    fun clicked(slot: Int, button: Int, now: Long) {
        tick(now)
        val q = pending.getOrPut(slot) { ArrayDeque() }
        when (type) {
            TerminalType.Panes -> q.add(Pending(active, if (paneOn(slot)) 0 else 1))
            TerminalType.Rubix -> q.add(Pending(active, if (button == LEFT) 1 else -1))
            TerminalType.StartsWith, TerminalType.Select -> {
                q.add(Pending(active, 1))
                if (item(slot)?.intrinsicGlint == true) sessionSelected += slot
            }
            TerminalType.Melody -> {
                q.add(Pending(active, 1))
                locks.addLast(Lock(slot / 9 - 1, now))
            }
            else -> q.add(Pending(active, 1))
        }
    }

    fun solution(now: Long): Solution {
        expire(now)
        val cells = LinkedHashMap<Int, Cell>()
        var remaining = 0
        var target = -1
        var melody: MelodyState? = null
        var melodyLocked: Int? = null
        when (type) {
            TerminalType.StartsWith, TerminalType.Select -> for (s in type.board) {
                val state = if (!matches(s)) IDLE else if (selected(s)) DONE else TARGET
                if (state == TARGET) remaining++
                cells[s] = Cell(s, state)
            }
            TerminalType.Panes -> for (s in type.board) {
                val on = paneOn(s)
                if (!on) remaining++
                cells[s] = Cell(s, if (on) DONE else TARGET)
            }
            TerminalType.Order -> {
                val queue = orderQueue()
                remaining = queue.size
                for (s in type.board) {
                    val rank = queue.indexOf(s)
                    cells[s] = if (rank < 0) Cell(s, DONE) else if (rank == 0) Cell(s, TARGET) else Cell(s, UPCOMING, rank)
                }
            }
            TerminalType.Rubix -> {
                target = currentRubixTarget()
                for (s in type.board) {
                    val c = rubixColor(s)
                    val move = if (c < 0) 0 else RubixColors.move(c, target)
                    remaining += Math.abs(move)
                    cells[s] = Cell(s, if (move == 0) DONE else TARGET, clicks = move)
                }
            }
            TerminalType.Melody -> {
                val m = melody()
                melody = m
                val lanes = m.lanes.size
                val locked = m.lanes.indices.firstOrNull { m.lanes[it].button in pending && it > acceptedLane }
                melodyLocked = locked
                val doneLanes = maxOf(acceptedLane + 1, when {
                    m.active != null -> m.active
                    locked != null -> locked + 1
                    else -> 0
                })
                remaining = lanes - doneLanes
                for (l in m.lanes.indices) {
                    val lane = m.lanes[l]
                    // NOW when a click made now reaches the server while the pane is on the target.
                    val onTime = m.target != null && l == m.active && clock.nowCue(m.target, now, latency())
                    val state = if (l < doneLanes) DONE else if (onTime) TARGET else IDLE
                    cells[lane.button] = Cell(lane.button, state)
                }
            }
        }
        if (total < remaining) total = remaining
        return Solution(cells, remaining, total, target, melody, melodyLocked)
    }
}
