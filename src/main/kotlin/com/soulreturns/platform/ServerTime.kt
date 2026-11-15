package com.soulreturns.platform

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

/**
 * The live [ServerClock] of the current connection, fed by the lone pings ([ServerPings], netty thread) and
 * `ClientPacketListenerMixin.handleSetTime`. A `set_time` packet anchors the clock at its arrival (the netty pass);
 * the client-thread pass of the same packet only anchors when the netty pass never ran. Any thread may read.
 */
object ServerTime {
    private val clock = ServerClock()

    @Volatile
    private var anchored: Any? = null

    @Volatile
    var lastSetTime: Long? = null
        private set

    @Volatile
    var lastSetTimeAtMs = 0L
        private set

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    @JvmStatic
    fun onSetTime(packet: Any, gameTime: Long, onNetty: Boolean) {
        if (!onNetty && anchored === packet) return
        anchored = if (onNetty) packet else null
        clock.onSetTime(gameTime, System.nanoTime())
        lastSetTime = gameTime
        lastSetTimeAtMs = System.currentTimeMillis()
    }

    /** A lone server ping: one server tick began (netty thread). */
    @JvmStatic
    fun onPing() = clock.onPing(System.nanoTime())

    /** True while the clock counts lone pings (Hypixel), false while it interpolates `set_time`. */
    fun pinging(): Boolean = clock.pinging()

    fun reset() {
        clock.reset()
        anchored = null
    }

    /** The server tick now; null before the first `set_time` of this connection or after 15 s without one. */
    @JvmStatic
    fun tick(): Long? = clock.now(System.nanoTime())

    /** [tick] with the running tick's fraction. */
    fun exact(): Double? = clock.exact(System.nanoTime())

    /** False while the next `set_time` is overdue (a server stall): countdowns should freeze. */
    fun fresh(): Boolean = clock.fresh(System.nanoTime())

    fun rate(): Double? = clock.rate()

    /** The tick at [atNanos] (`System.nanoTime()`) interpolated between the `set_time` packets around it; null until the next one arrived. */
    fun between(atNanos: Long): Double? = clock.between(atNanos)
}
