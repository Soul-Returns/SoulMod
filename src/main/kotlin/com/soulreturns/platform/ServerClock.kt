package com.soulreturns.platform

import kotlin.math.abs

/**
 * The server's game time as a clock, from two sources.
 *
 * Ping ticks (Hypixel): the server sends one `ping` per tick outside bundles (the lone pings Odin counts; the extra
 * pings around entity updates come inside bundles and are not counted here). While the last 20-tick `set_time`
 * interval saw one lone ping per tick (±[PING_SLACK]), the clock is the last `set_time` value plus the pings since,
 * plus the running tick's fraction from the wall time since the last ping (never past the next ping, so a stall
 * freezes it). F7 recording 2026-11-13: 7575 lone pings in 7580 ticks, 98.2% of intervals exact. `set_time` checks
 * it: a clock behind the packet's game time jumps to it (a lost ping: the tick is at least the packet's), one ahead
 * only when the next packet confirms it (a `set_time` that arrived late after a burst of 4 pings, the next interval
 * 16).
 *
 * Without pings (other servers, the test server): every `set_time` anchors it at the packet's arrival, and between
 * anchors it counts ticks from the wall time since, at the rate the last interval showed (server TPS, 14..25/s
 * accepted, else 20/s). A freeze stops it: it never runs more than [MAX_AHEAD] ticks past the last anchor, and past
 * [STALE_NANOS] without a packet it has no value at all.
 *
 * Pure: no Minecraft types, the caller passes `System.nanoTime()`; one writer (the netty thread that sees the
 * packets), any reader.
 */
class ServerClock {
    private class Anchor(val gameTime: Long, val atNanos: Long, val nanosPerTick: Long)

    @Volatile
    private var anchor: Anchor? = null

    @Volatile
    private var previous: Anchor? = null

    /** Lone pings of this connection, and the tick the latest one started (valid while [pingMode]). */
    @Volatile
    private var pings = 0L

    @Volatile
    private var pingTick = 0L

    @Volatile
    private var lastPingNanos = 0L

    @Volatile
    private var pingMode = false

    private var pingsAtAnchor = 0L
    private var aheadBy = 0L

    /** Recent pings: arrival and the tick they started, for [between]. */
    private val ringNanos = LongArray(RING)
    private val ringTick = LongArray(RING)

    @Volatile
    private var ringCount = 0L

    /** A lone server ping (id ≠ 0, not inside a bundle) reached the client at [atNanos]: the server began a tick. */
    fun onPing(atNanos: Long) {
        pings++
        lastPingNanos = atNanos
        val tick = pingTick + 1
        pingTick = tick
        val i = (ringCount % RING).toInt()
        ringNanos[i] = atNanos
        ringTick[i] = tick
        ringCount++
    }

    /** A `set_time` packet with [gameTime] reached the client at [atNanos]. */
    fun onSetTime(gameTime: Long, atNanos: Long) {
        val prev = anchor
        var per = prev?.nanosPerTick ?: NORMAL
        if (prev != null) {
            val dTicks = gameTime - prev.gameTime
            val dNanos = atNanos - prev.atNanos
            if (dTicks in 1..MAX_AHEAD * 2L) {
                val measured = dNanos / dTicks
                if (measured in MIN_PER_TICK..MAX_PER_TICK) per = (per + measured) / 2
            }
        }
        // Only a packet of the same server, a little earlier, brackets the ticks in between.
        previous = prev?.takeIf { gameTime - it.gameTime in 1..MAX_AHEAD * 2L }
        val window = pings - pingsAtAnchor
        pingsAtAnchor = pings
        val wasPing = pingMode
        pingMode = previous != null && window >= MIN_PINGS && abs(window - (gameTime - previous!!.gameTime)) <= PING_SLACK
        if (!pingMode || !wasPing) {
            pingTick = gameTime
            aheadBy = 0
        } else {
            val ahead = pingTick - gameTime
            if (ahead < 0 || ahead > MAX_AHEAD || (ahead > 0 && ahead == aheadBy)) pingTick = gameTime
            aheadBy = if (ahead > 0) ahead else 0
        }
        anchor = Anchor(gameTime, atNanos, per)
    }

    /**
     * The server tick at [atNanos] read between the two packets around it (the research's plain interpolation),
     * or null until the packet after it arrived. Extrapolation ([exact]) runs a few ticks off when the server's
     * rate changes, worst right after a stall (M7: 4-6 ticks); this does not.
     */
    fun between(atNanos: Long): Double? {
        if (pingMode) return pingBetween(atNanos)
        val a = anchor ?: return null
        val p = previous ?: return null
        if (atNanos < p.atNanos || atNanos > a.atNanos || a.atNanos <= p.atNanos) return null
        return p.gameTime + (a.gameTime - p.gameTime).toDouble() * (atNanos - p.atNanos) / (a.atNanos - p.atNanos)
    }

    /** The server tick at [atNanos], or null before the first packet, after [reset], or when none arrived for [STALE_NANOS]. */
    fun now(atNanos: Long): Long? = exact(atNanos)?.toLong()

    /** [now] with the fraction of the running tick, for smooth countdowns. */
    fun exact(atNanos: Long): Double? {
        val a = anchor ?: return null
        if (pingMode) {
            if (atNanos - lastPingNanos > STALE_NANOS) return null
            val since = (atNanos - lastPingNanos).coerceAtLeast(0)
            return pingTick + minOf(since.toDouble() / a.nanosPerTick, MAX_FRACTION)
        }
        val elapsed = atNanos - a.atNanos
        if (elapsed > STALE_NANOS) return null
        if (elapsed <= 0) return a.gameTime.toDouble()
        return a.gameTime + minOf(elapsed.toDouble() / a.nanosPerTick, MAX_AHEAD.toDouble())
    }

    /**
     * Whether the clock is running on time at [atNanos]: the next packet is not overdue by more than
     * [OVERDUE_NANOS]. False during a server stall, when [now] holds at the next packet's value.
     */
    fun fresh(atNanos: Long): Boolean {
        val a = anchor ?: return false
        if (pingMode) return atNanos - lastPingNanos <= OVERDUE_NANOS
        return atNanos - a.atNanos <= a.nanosPerTick * MAX_AHEAD + OVERDUE_NANOS
    }

    /** Whether the clock counts lone pings now (else it interpolates `set_time`). */
    fun pinging(): Boolean = pingMode

    /** The ping tick running at [atNanos] with its fraction, from the recent pings; null when they no longer reach back. */
    private fun pingBetween(atNanos: Long): Double? {
        val n = ringCount
        val a = anchor ?: return null
        var k = n - 1
        while (k >= 0 && k >= n - RING) {
            val i = (k % RING).toInt()
            if (ringNanos[i] <= atNanos) {
                val next = if (k + 1 < n) ringNanos[((k + 1) % RING).toInt()] else Long.MAX_VALUE
                val span = minOf(next - ringNanos[i], a.nanosPerTick).coerceAtLeast(1)
                return ringTick[i] + minOf((atNanos - ringNanos[i]).toDouble() / span, MAX_FRACTION)
            }
            k--
        }
        return null
    }

    /** A new connection: the old server's time says nothing about the next. */
    fun reset() {
        anchor = null
        previous = null
        pingMode = false
        pingsAtAnchor = pings
        aheadBy = 0
        ringCount = 0
    }

    /** Ticks per second the clock currently runs at (null before the first packet). */
    fun rate(): Double? = anchor?.let { 1e9 / it.nanosPerTick }

    companion object {
        const val NORMAL = 50_000_000L
        const val MIN_PER_TICK = 40_000_000L
        const val MAX_PER_TICK = 71_000_000L
        const val MAX_AHEAD = 20
        const val STALE_NANOS = 15_000_000_000L
        const val OVERDUE_NANOS = 350_000_000L
        const val MIN_PINGS = 10L
        const val PING_SLACK = 4L
        const val MAX_FRACTION = 0.999
        const val RING = 128
    }
}
