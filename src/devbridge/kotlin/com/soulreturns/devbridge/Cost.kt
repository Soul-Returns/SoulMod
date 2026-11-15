package com.soulreturns.devbridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What the bridge costs the game, measured: nanoseconds the bridge spent on the game's own threads
 * (the client thread: tick sampler, event building; the network thread: packet hooks), summed per
 * client tick. [endTick] closes a tick; over any 20 ticks the average above [budgetMs] makes the
 * bridge shed the packet type that cost the most (see [Bridge.shed]) instead of lagging the game.
 * Work on the bridge's own threads (the packet worker, the writers) is not counted here; the packet
 * worker's busy time is reported next to it.
 *
 * Pure bookkeeping: no Minecraft classes, one atomic add per measured span.
 */
class Cost(
    /** The average ms per tick over [WINDOW] ticks that triggers shedding. */
    @Volatile var budgetMs: Double = DEFAULT_BUDGET_MS,
    private val onShed: (type: String, avgMs: Double, typeMs: Double) -> Unit = { _, _, _ -> },
) {
    private val clientNanos = AtomicLong()
    private val nettyNanos = AtomicLong()
    private val byType = ConcurrentHashMap<String, AtomicLong>()

    /** The client thread, set by the first [endTick]; spans measured elsewhere count as network-thread time. */
    @Volatile
    private var clientThread: Thread? = null

    private val ring = DoubleArray(RING)
    private var ringPos = 0
    private var filled = 0
    private var sinceCheck = 0

    @Volatile var ticks = 0L
        private set
    @Volatile var maxMs = 0.0
        private set
    @Volatile var overBudgetTicks = 0L
        private set
    @Volatile var avgMs = 0.0
        private set
    @Volatile var clientAvgMs = 0.0
        private set
    @Volatile var nettyAvgMs = 0.0
        private set
    @Volatile var lastShedTick = -1L
        private set

    /** Adds a measured span from the calling thread; [type] attributes it to a packet type (or event kind) for shedding. */
    fun add(nanos: Long, type: String? = null) {
        if (Thread.currentThread() === clientThread) clientNanos.addAndGet(nanos) else nettyNanos.addAndGet(nanos)
        if (type != null) byType.computeIfAbsent(type) { AtomicLong() }.addAndGet(nanos)
    }

    /** Attributes worker-thread time to [type] so a type that is cheap on the game threads but swamps the worker can be shed too. */
    fun addWorker(nanos: Long, type: String) {
        byType.computeIfAbsent(type) { AtomicLong() }.addAndGet(nanos)
    }

    /** End of a client tick (called first thing in the tick handler, on the client thread). */
    fun endTick() {
        if (clientThread == null) clientThread = Thread.currentThread()
        val c = clientNanos.getAndSet(0) / 1e6
        val n = nettyNanos.getAndSet(0) / 1e6
        val total = c + n
        ticks++
        ring[ringPos] = total
        ringPos = (ringPos + 1) % RING
        if (filled < RING) filled++
        if (total > maxMs) maxMs = total
        if (total > budgetMs) overBudgetTicks++
        avgMs += (total - avgMs) * SMOOTH
        clientAvgMs += (c - clientAvgMs) * SMOOTH
        nettyAvgMs += (n - nettyAvgMs) * SMOOTH
        if (++sinceCheck < WINDOW) return
        sinceCheck = 0
        var sum = 0.0
        for (i in 1..WINDOW) sum += ring[(ringPos - i + RING) % RING]
        val windowAvg = sum / WINDOW
        if (windowAvg > budgetMs && filled >= WINDOW) {
            val top = byType.entries.maxByOrNull { it.value.get() }
            if (top != null && top.value.get() > 0) {
                lastShedTick = ticks
                onShed(top.key, windowAvg, top.value.get() / 1e6 / WINDOW)
                byType.remove(top.key)
            }
        }
        byType.clear()
    }

    /** `{budget_ms, ticks, avg_ms, max_ms, …}` for `status`. */
    fun describe(): Map<String, Any?> =
        linkedMapOf(
            "budget_ms" to budgetMs,
            "ticks" to ticks,
            "avg_ms" to round(avgMs),
            "max_ms" to round(maxMs),
            "client_thread_avg_ms" to round(clientAvgMs),
            "network_thread_avg_ms" to round(nettyAvgMs),
            "ticks_over_budget" to overBudgetTicks,
            "last_shed_tick" to lastShedTick.takeIf { it >= 0 },
        )

    fun reset() {
        clientNanos.set(0)
        nettyNanos.set(0)
        byType.clear()
        ringPos = 0
        filled = 0
        sinceCheck = 0
        ticks = 0
        maxMs = 0.0
        overBudgetTicks = 0
        avgMs = 0.0
        clientAvgMs = 0.0
        nettyAvgMs = 0.0
        lastShedTick = -1
    }

    private fun round(v: Double) = Math.round(v * 1000) / 1000.0

    companion object {
        const val DEFAULT_BUDGET_MS = 2.0

        /** Ticks per shedding decision and ring size (a second of game time). */
        const val WINDOW = 20
        private const val RING = 200
        private const val SMOOTH = 0.02
    }
}
