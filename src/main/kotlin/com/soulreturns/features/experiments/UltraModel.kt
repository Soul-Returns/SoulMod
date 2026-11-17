package com.soulreturns.features.experiments

/**
 * Ultrasequencer (research §1.2, §4.3). The board (slots 9–44 as captured) shows numbers as dyes named
 * `1`, `2`, … with count = number; every round puts all numbers at new slots. Slot 49 turns glowstone
 * after the round's burst (DISPLAY) and clock at the hide (INPUT); Hypixel takes answers from the hide.
 *
 * Progress counts only the echo (`entity.item.pickup` during input; the slot shows its number again a
 * tick later, which must be the remembered slot). An incomplete or duplicate set of numbers at the hide
 * turns the solver off for that round.
 */
class UltraModel private constructor(val board: IntRange, val tier: String) : GameModel(54) {
    enum class Phase { DISPLAY, INPUT }

    /** Number visible per slot right now. */
    val live = HashMap<Int, Int>()

    /** Remembered slot per number (from the display). */
    val nums = HashMap<Int, Int>()
    var round = 0
        private set
    var phase = Phase.DISPLAY
        private set
    var k = 0
        private set
    val pending = Pending<Int>()

    /** The board pane color (`white`, `light_blue`, …): it names the round. */
    var pane = "white"
        private set
    var displayAt = 0L
        private set
    var inputAt: Long? = null
        private set
    var timer: Int? = null
        private set
    var timerAt = 0L
        private set
    var timerMax = 0
        private set

    override fun onSlot(slot: Int, old: ExpItem?, item: ExpItem?, now: Long) {
        if (item == null) return
        if (slot == TIMER_SLOT) {
            when (item.id) {
                "glowstone" -> startDisplay(now)
                "clock" -> {
                    timer = timerOf(item)
                    timerAt = now
                    if (phase == Phase.DISPLAY) hide(now)
                }
                else -> breakLayout("slot 49 is ${item.id}")
            }
            return
        }
        if (slot !in board) {
            if (!item.isBlackPane) breakLayout("slot $slot is ${item.id}")
            return
        }
        val n = numberOf(item)
        if (n > 0) {
            live[slot] = n
            if (phase == Phase.DISPLAY) {
                nums.entries.removeIf { it.value == slot }
                nums[n] = slot
                round = maxOf(round, n)
            } else if (k < round && nums[n] != slot) {
                // A tick after the echo the clicked slot shows its number again: it must be the remembered one.
                turnOff("$n showed on another slot")
                events++
            }
        } else if (item.isPane) {
            live.remove(slot)
            pane = item.id.removeSuffix("_stained_glass_pane")
        } else {
            breakLayout("board slot $slot is ${item.id}")
        }
    }

    private fun startDisplay(now: Long) {
        phase = Phase.DISPLAY
        displayAt = now
        k = 0
        pending.clear()
        timer = null
        inputAt = null
        off = null
        nums.clear()
        for ((s, n) in live) nums[n] = s
        round = live.values.maxOrNull() ?: 0
    }

    private fun hide(now: Long) {
        phase = Phase.INPUT
        inputAt = now
        timerMax = timer ?: 0
        if (round == 0 || (1..round).any { it !in nums } || nums.size != round) turnOff("numbers incomplete")
    }

    override fun onSound(name: String, pitch: Float, now: Long) {
        if (name != PICKUP || phase != Phase.INPUT || k >= round) return
        k++
        pending.shift()
    }

    override fun age(dt: Long, talking: Boolean) {
        pending.age(dt, talking)
    }

    /** Milliseconds until the numbers hide (41 + 6·(N−1) ticks after the burst). */
    fun hideEta(now: Long): Long = displayAt + (SHOW_BASE + SHOW_STEP * (round - 1).coerceAtLeast(0)) * TICK_MS - now

    fun nextNum(o: Int = 0): Int? {
        val i = k + pending.size + o
        return if (i < round) i + 1 else null
    }

    fun numberAt(slot: Int): Int? = nums.entries.firstOrNull { it.value == slot }?.key

    fun press(slot: Int, now: Long): Verdict {
        if (off != null) return Verdict.Send
        if (phase == Phase.DISPLAY) {
            return Verdict.Block("reveal", "Memorize: the numbers hide in ${String.format(java.util.Locale.ROOT, "%.1f", maxOf(0L, hideEta(now)) / 1000.0)} s")
        }
        val i = k + pending.size
        if (i >= round) return Verdict.Block("done", "All $round sent: waiting for Hypixel")
        val exp = nums[i + 1]
        if (slot == exp) {
            pending.add(slot)
            return Verdict.Send
        }
        val n = numberAt(slot)
        return Verdict.Block("wrong", if (n != null) "That is $n: ${i + 1} is next" else "Empty cell: ${i + 1} is next")
    }

    fun secondsLeft(now: Long): Int? = timer?.let { maxOf(0, kotlin.math.ceil(it - (now - timerAt) / 1000.0 - 0.001).toInt()) }

    fun resync(items: List<ExpItem?>, now: Long) = replay(items, now)

    companion object {
        const val TIMER_SLOT = 49
        const val PICKUP = "entity.item.pickup"
        const val SHOW_BASE = 41
        const val SHOW_STEP = 6
        const val TICK_MS = 50L
        private val BOARD = 9..44

        fun numberOf(item: ExpItem): Int {
            val n = item.plain.toIntOrNull() ?: return 0
            return if (n > 0 && item.count == n && !item.isPane) n else 0
        }

        /**
         * The captured layout (Metaphysical 9x4): slots 9–44 panes or numbers (each number once), the rest
         * black panes, slot 49 glowstone/clock. Other boards were never captured: null, the vanilla menu.
         */
        fun layout(items: List<ExpItem?>): IntRange? {
            if (items.size < 54 || items.take(54).any { it == null }) return null
            if (items[TIMER_SLOT]?.id !in setOf("glowstone", "clock")) return null
            val seen = HashSet<Int>()
            for (s in 0 until 54) {
                if (s == TIMER_SLOT) continue
                val it = items[s]!!
                if (s !in BOARD) {
                    if (!it.isBlackPane) return null
                    continue
                }
                val n = numberOf(it)
                if (n > 0) {
                    if (!seen.add(n)) return null
                } else if (!it.isPane) {
                    return null
                }
            }
            return BOARD
        }

        fun create(items: List<ExpItem?>, tier: String, now: Long): UltraModel? {
            val board = layout(items) ?: return null
            return UltraModel(board, tier).also { m ->
                m.displayAt = now
                for (i in 0 until 54) if (i != TIMER_SLOT) m.apply(i, items[i], now)
                m.apply(TIMER_SLOT, items[TIMER_SLOT], now)
            }
        }
    }
}
