package com.soulreturns.devbridge

import com.soulreturns.platform.ServerTime
import net.minecraft.client.Minecraft

/**
 * The bridge's clocks, read-only.
 *
 * [tick] is **the** fight clock: the mod's [ServerTime] (on Hypixel the last `set_time` value plus the lone pings
 * since; elsewhere `set_time` interpolated between packets and corrected at each one). Every event and frame carries
 * it as `tick`. [ping] is the raw count of `ClientboundPingPacket`s since client start, kept as a diagnostic: it
 * counts bundled pings too (handled one by one on the client thread; about two extra around every attribute update
 * of the player's own entity, so 12-17% above the tick on Hypixel). Only the lone ones, which a
 * `Connection.channelRead0` hook such as Odin's sees, are one per tick; packet lines mark bundle sub-packets (`bundle`).
 */
object Ticks {
    @Volatile
    var pings = 0L
        private set

    @Volatile
    var lastPingAt = 0L
        private set

    private var lastId = Int.MIN_VALUE
    private val recent = LongArray(RATE_WINDOW)

    // The id pattern tells tick pings from other transactions (step histogram, repeats, last ids).
    @Volatile private var repeats = 0L
    @Volatile private var stepPlus1 = 0L
    @Volatile private var stepMinus1 = 0L
    @Volatile private var stepOther = 0L
    private val lastIds = IntArray(16)

    /** Processing thread (client), from the `handlePing` mixin. */
    @JvmStatic
    fun onPing(id: Int) {
        if (id == lastId) {
            repeats++
            return
        }
        if (lastId != Int.MIN_VALUE) {
            val step = id.toLong() - lastId
            if (step == 1L) stepPlus1++ else if (step == -1L) stepMinus1++ else stepOther++
        }
        lastIds[(pings % lastIds.size).toInt()] = id
        lastId = id
        val now = System.currentTimeMillis()
        recent[(pings % RATE_WINDOW).toInt()] = now
        pings++
        lastPingAt = now
    }

    /** The fight clock: server game time, interpolated and corrected at every `set_time`; null before the first one. Any thread. */
    @JvmStatic
    fun tick(): Long? = ServerTime.tick()

    /** Raw ping count since client start (diagnostic; null until the first ping). */
    @JvmStatic
    fun ping(): Long? = if (pings > 0) pings else null

    /** The client level's game time (client-tick extrapolated, snapped to each `set_time`); a diagnostic only, racy read. */
    fun clientGameTime(): Long? = Minecraft.getInstance().level?.levelData?.gameTime

    fun source(): String = if (tick() != null) "set_time" else "none"

    /** Pings per second over the last [RATE_WINDOW] (≈ 22 on Hypixel, against ~19.6 server ticks/s). */
    fun pingRate(): Double? {
        val n = minOf(pings, RATE_WINDOW.toLong()).toInt()
        if (n < 2 || System.currentTimeMillis() - lastPingAt > STALE_MS) return null
        val newest = recent[((pings - 1) % RATE_WINDOW).toInt()]
        val oldest = recent[((pings - n) % RATE_WINDOW).toInt()]
        return if (newest > oldest) (n - 1) * 1000.0 / (newest - oldest) else null
    }

    fun describe(): Map<String, Any?> =
        linkedMapOf(
            "source" to source(),
            "tick" to tick(),
            "tick_rate" to ServerTime.rate(),
            "last_set_time" to ServerTime.lastSetTime,
            "last_set_time_ms_ago" to if (ServerTime.lastSetTimeAtMs == 0L) null else System.currentTimeMillis() - ServerTime.lastSetTimeAtMs,
            "client_game_time" to clientGameTime(),
            "pings" to pings,
            "last_ping_ms_ago" to if (lastPingAt == 0L) null else System.currentTimeMillis() - lastPingAt,
            "ping_rate" to pingRate(),
            "ping_ids" to
                linkedMapOf(
                    "last" to (0 until minOf(pings, lastIds.size.toLong()).toInt()).map { lastIds[((pings - 1 - it).toInt() % lastIds.size + lastIds.size) % lastIds.size] },
                    "step_plus1" to stepPlus1,
                    "step_minus1" to stepMinus1,
                    "step_other" to stepOther,
                    "repeats_skipped" to repeats,
                ),
        )

    private const val RATE_WINDOW = 100
    private const val STALE_MS = 2_000L
}
