package com.soulreturns.features.pets

/**
 * XP/h of one pet from its total-XP samples (tab refreshes, ~3 s apart while XP comes in; research §1.2).
 *
 * A sliding [WINDOW_MS] over the samples; the rate is the gain between the window's first and last
 * sample over their time span. After [IDLE_MS] without gain the pet is "not gaining" (rate null), and
 * the next gain starts a fresh window whose base is the old total one tab refresh ([TAB_MS]) earlier,
 * so the idle stretch never dilutes the rate. A total that goes down (another pet of the same name,
 * a corrected table) restarts the window.
 */
class PetXpRate {
    private val times = ArrayDeque<Long>()
    private val totals = ArrayDeque<Double>()
    private var lastGainAt = 0L

    fun add(now: Long, total: Double) {
        val last = totals.lastOrNull()
        if (last == null || total < last) {
            clear()
            times.addLast(now)
            totals.addLast(total)
            return
        }
        if (total == last) return
        if (now - lastGainAt > IDLE_MS) {
            clear()
            times.addLast(now - TAB_MS)
            totals.addLast(last)
        }
        times.addLast(now)
        totals.addLast(total)
        lastGainAt = now
        while (times.size > 2 && now - times[1] >= WINDOW_MS) {
            times.removeFirst()
            totals.removeFirst()
        }
    }

    /** XP per hour, or null while not gaining (no gain for [IDLE_MS], or too little data). */
    fun perHour(now: Long): Double? {
        if (times.size < 2 || now - lastGainAt > IDLE_MS) return null
        val span = times.last() - times.first()
        if (span < MIN_SPAN_MS) return null
        val gained = totals.last() - totals.first()
        return if (gained <= 0) null else gained / span * 3_600_000.0
    }

    fun gaining(now: Long): Boolean = lastGainAt > 0 && now - lastGainAt <= IDLE_MS

    fun clear() {
        times.clear()
        totals.clear()
    }

    companion object {
        const val WINDOW_MS = 300_000L
        const val IDLE_MS = 90_000L
        const val TAB_MS = 3_000L
        const val MIN_SPAN_MS = 6_000L
    }
}
