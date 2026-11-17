package com.soulreturns.features.experiments

import kotlin.math.abs

/**
 * Chronomatron (research §1.1, §4.2). Each color is two vertically stacked cells (`<color>_stained_glass`
 * named the color, lit = `<color>_terracotta`). Round N replays the previous N−1 notes and adds one.
 *
 * DISPLAY: a note is a glass → terracotta transition of a color (both cells light together, counted
 * once); cells already lit at the round start are the last answer's echo and are ignored until they go
 * out. Input opens at the N-th display pling (Hypixel accepts answers from the last note on, 1–2 ticks
 * before the client sees the clock) or at slot 49 → clock, whichever comes first.
 *
 * INPUT: progress counts only Hypixel's echo (a pling during input); this client's own presses are
 * [pending] and only decide what the next press is judged against. An echo whose pitch names another
 * color than expected turns the solver off for the game: it never guesses.
 */
class ChronoModel private constructor(val colors: List<Color>, val tier: String) : GameModel(54) {
    data class Color(val name: String, val top: Int, val bottom: Int)

    enum class Phase { DISPLAY, INPUT }

    private val cells = HashMap<Int, Color>().apply { for (c in colors) { put(c.top, c); put(c.bottom, c) } }
    private val lit = BooleanArray(54)

    var round = 0
        private set
    var phase = Phase.DISPLAY
        private set

    /** This round's notes as displayed (the full pattern once the display ends). */
    val notes = ArrayList<String>()
    var plings = 0
        private set

    /** Answers Hypixel confirmed this round. */
    var k = 0
        private set
    val pending = Pending<String>()
    private val ignore = HashSet<String>()
    private var awaitLast = false

    /** The last note's color from its pling's pitch, before its cells light (null when the pitch is unknown). */
    var lastColor: String? = null
        private set
    private var prevSeq: List<String> = emptyList()
    var timer: Int? = null
        private set
    var timerAt = 0L
        private set
    var timerMax = 0
        private set
    var clockAt: Long? = null
        private set
    var inputAt: Long? = null
        private set

    /** What opened the turn: `pling`, `lit` or `clock` (debug line). */
    var inputHow: String? = null
        private set
    var roundAt = 0L
        private set

    /** Pitch per color: the captured Metaphysical table when the board is that board, else learned from the display. */
    private val pitchOf = HashMap<String, Float>()
    private var heardPitch: Float? = null

    init {
        if (colors.map { it.name }.toSet() == METAPHYSICAL_PITCH.keys + "Pink") pitchOf.putAll(METAPHYSICAL_PITCH)
    }

    fun litColor(name: String): Boolean = colors.firstOrNull { it.name == name }?.let { lit[it.top] || lit[it.bottom] } ?: false

    fun colorOfSlot(slot: Int): String? = cells[slot]?.name

    /** The color answer [i] (0-based) must be, or null when it is not known. */
    fun expected(i: Int): String? = if (i < notes.size) notes[i] else if (i == round - 1 && awaitLast) lastColor else null

    /** The color [o] answers after the next one to send, or null. */
    fun next(o: Int = 0): String? {
        val i = k + pending.size + o
        return if (i < round) expected(i) else null
    }

    private fun colorOfPitch(p: Float): String? = pitchOf.entries.firstOrNull { abs(it.value - p) < 0.01f }?.key

    override fun onSlot(slot: Int, old: ExpItem?, item: ExpItem?, now: Long) {
        if (item == null) return
        if (slot == ROUND_SLOT) {
            val n = if (item.id == "bookshelf") ROUND.find(item.plain)?.groupValues?.get(1)?.toIntOrNull() else null
            if (n == null) return breakLayout("slot 4 is ${item.id} '${item.plain}'")
            if (n != round) newRound(n, now)
            return
        }
        if (slot == TIMER_SLOT) {
            when (item.id) {
                "clock" -> {
                    timer = timerOf(item)
                    timerAt = now
                    if (clockAt == null) {
                        clockAt = now
                        timerMax = timer ?: 0
                    }
                    openInput("clock", now)
                }
                "glowstone" -> timer = null
                else -> breakLayout("slot 49 is ${item.id}")
            }
            return
        }
        val c = cells[slot]
        if (c == null) {
            if (!item.isBlackPane) breakLayout("slot $slot is ${item.id}")
            return
        }
        val isLit = item.id.endsWith("_terracotta")
        if (!isLit && !item.id.endsWith("_stained_glass")) return breakLayout("cell $slot is ${item.id}")
        val was = litColor(c.name)
        lit[slot] = isLit
        val now_ = litColor(c.name)
        if (!was && now_) {
            if (c.name in ignore) return
            // A display note always follows its own pling (1–2 ticks); a lit without one is an answer's echo
            // (the last answer's echo can light in the same tick as the next round's start).
            if (plings > notes.size && notes.size < round) {
                heardPitch?.let { if (c.name !in pitchOf) pitchOf[c.name] = it }
                heardPitch = null
                notes += c.name
                if (awaitLast) {
                    awaitLast = false
                    lastColor = null
                }
                if (notes.size >= round) openInput("lit", now)
            }
        } else if (was && !now_) {
            ignore.remove(c.name)
        }
    }

    private fun newRound(n: Int, now: Long) {
        if (notes.size == round && round > 0) prevSeq = notes.toList()
        round = n
        phase = Phase.DISPLAY
        notes.clear()
        plings = 0
        k = 0
        awaitLast = false
        lastColor = null
        timer = null
        clockAt = null
        inputAt = null
        inputHow = null
        roundAt = now
        heardPitch = null
        pending.clear()
        ignore.clear()
        for (c in colors) if (litColor(c.name)) ignore += c.name
    }

    private fun openInput(how: String, now: Long) {
        if (phase == Phase.INPUT || round == 0) return
        phase = Phase.INPUT
        inputAt = now
        inputHow = how
        checkNotes()
    }

    /**
     * A note that never showed as a transition (a stall merged its lit and unlit): rebuild from the
     * last round when what was seen fits it, else stop judging. The plings count the notes reliably.
     */
    private fun checkNotes() {
        val missing = round - notes.size - if (awaitLast) 1 else 0
        if (missing <= 0) return
        val last = if (awaitLast) null else notes.lastOrNull()
        val body = if (awaitLast) notes.toList() else notes.dropLast(1)
        if (prevSeq.size == round - 1 && isSubsequence(body, prevSeq)) {
            notes.clear()
            notes += prevSeq
            if (last != null) notes += last
        } else {
            turnOff("missed $missing note${if (missing == 1) "" else "s"}")
        }
    }

    override fun onSound(name: String, pitch: Float, now: Long) {
        if (name != PLING || round == 0) return
        if (phase == Phase.DISPLAY) {
            plings++
            heardPitch = pitch
            if (plings == round) {
                lastColor = colorOfPitch(pitch)
                awaitLast = true
                openInput("pling", now)
            }
            return
        }
        if (k >= round) return
        val exp = expected(k)
        val col = colorOfPitch(pitch)
        if (exp != null && col != null && exp != col) {
            turnOff("Hypixel echoed $col, expected $exp")
            events++
            return
        }
        k++
        pending.shift()
    }

    override fun age(dt: Long, talking: Boolean) {
        pending.age(dt, talking)
    }

    /** Judge one press on [color]. Never sends: the caller sends at once on [Verdict.Send]. */
    fun press(color: String): Verdict {
        if (off != null) return Verdict.Send
        if (phase == Phase.DISPLAY) {
            val left = round - plings
            return Verdict.Block("reveal", "Watching: $left more note${if (left == 1) "" else "s"} to play")
        }
        val i = k + pending.size
        if (i >= round) return Verdict.Block("done", "All $round sent: waiting for Hypixel")
        val exp = expected(i) ?: return Verdict.Send.also { pending.add(color) }
        if (color == exp) {
            pending.add(color)
            return Verdict.Send
        }
        return Verdict.Block("wrong", "$color is not next: $exp is")
    }

    fun secondsLeft(now: Long): Int? = timer?.let { maxOf(0, kotlin.math.ceil(it - (now - timerAt) / 1000.0 - 0.001).toInt()) }

    companion object {
        const val ROUND_SLOT = 4
        const val TIMER_SLOT = 49
        const val PLING = "block.note_block.pling"
        private val ROUND = Regex("^Round: (\\d+)$")

        /** Display timing for the progress bar (ticks): first note +16, then every 18; pling 1 tick ahead. */
        const val FIRST_NOTE = 16
        const val NOTE_GAP = 18

        /** `block.note_block.pling` pitch per color in Metaphysical (research §1.1; Pink never heard). */
        val METAPHYSICAL_PITCH =
            mapOf(
                "Red" to 0.5556f, "Blue" to 0.6190f, "Lime" to 0.7460f, "Yellow" to 0.8254f, "Aqua" to 1.0f,
                "Green" to 1.2540f, "Cyan" to 1.4921f, "Orange" to 1.6667f, "Purple" to 2.0f,
            )

        /**
         * The captured layout, read from a complete board: slot 4 `Round: N`, slot 49 glowstone/clock, every
         * color on exactly two stacked cells (s, s+9), black panes elsewhere. Anything else (other tiers'
         * layouts were never captured) is null: the vanilla menu.
         */
        fun layout(items: List<ExpItem?>): List<Color>? {
            if (items.size < 54 || items.take(54).any { it == null }) return null
            if (items[ROUND_SLOT]?.id != "bookshelf" || items[ROUND_SLOT]?.plain?.let { ROUND.matches(it) } != true) return null
            if (items[TIMER_SLOT]?.id !in setOf("glowstone", "clock")) return null
            val byName = LinkedHashMap<String, MutableList<Int>>()
            for (s in 0 until 54) {
                if (s == ROUND_SLOT || s == TIMER_SLOT) continue
                val it = items[s]!!
                if (it.isBlackPane) continue
                if (!it.id.endsWith("_stained_glass") && !it.id.endsWith("_terracotta")) return null
                if (it.plain.isBlank()) return null
                byName.getOrPut(it.plain) { ArrayList() } += s
            }
            if (byName.size < 2) return null
            val out = ArrayList<Color>()
            for ((name, slots) in byName) {
                if (slots.size != 2 || slots[1] != slots[0] + 9) return null
                out += Color(name, slots[0], slots[1])
            }
            return out.sortedBy { it.top }
        }

        fun create(items: List<ExpItem?>, tier: String, now: Long): ChronoModel? {
            val colors = layout(items) ?: return null
            return ChronoModel(colors, tier).also { m ->
                m.roundAt = now
                m.replay(items, now)
            }
        }

        private fun isSubsequence(a: List<String>, b: List<String>): Boolean {
            var j = 0
            for (x in a) {
                while (j < b.size && b[j] != x) j++
                if (j == b.size) return false
                j++
            }
            return true
        }
    }

    /** Continue this game in a reopened container: its burst is applied as transitions. */
    fun resync(items: List<ExpItem?>, now: Long) = replay(items, now)
}
