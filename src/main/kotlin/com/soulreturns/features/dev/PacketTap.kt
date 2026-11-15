package com.soulreturns.features.dev

import com.soulreturns.util.SoulLogger
import net.minecraft.network.Connection
import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow

/**
 * In-process packet tap: [com.soulreturns.mixin.ConnectionMixin] hands every packet of the client's
 * own connection to the registered sinks ([PacketLogger], the dev bridge). Observe only: sinks must
 * not change, drop or send packets, and must not block (inbound calls run on the netty thread).
 *
 * With no sink registered the hooks cost one volatile read ([active]).
 */
object PacketTap {
    fun interface Sink {
        /** [inbound] false = about to be sent. Bundles arrive expanded, one call per sub-packet ([bundle] names its bundle). */
        fun packet(inbound: Boolean, packet: Packet<*>)
    }

    private val logger = SoulLogger("Soul/PacketTap")

    @Volatile
    private var sinks: Array<Sink> = emptyArray()

    private var bundles = 0L

    /**
     * During a sink call: the bundle the packet came in (1, 2, … per tap; 0 = it came alone). Read it on the calling
     * thread only. A hook on `Connection.channelRead0` (Odin's) sees a bundle as one packet and none of its sub-packets.
     */
    @JvmStatic
    var bundle = 0L
        private set

    @JvmStatic
    val active: Boolean
        get() = sinks.isNotEmpty()

    @Synchronized
    fun register(sink: Sink) {
        if (sink !in sinks) sinks += sink
    }

    @Synchronized
    fun unregister(sink: Sink) {
        sinks = sinks.filter { it !== sink }.toTypedArray()
        if (sinks.isEmpty()) PacketJson.forgetDiscarded()
    }

    /** Network thread: a packet arrived on [connection]. Only the client's own connection counts. */
    @JvmStatic
    fun inbound(connection: Connection, packet: Packet<*>) {
        if (connection.receiving != PacketFlow.CLIENTBOUND) return
        dispatch(sinks, true, packet)
    }

    /** Network or game thread: [connection] is about to send [packet]. */
    @JvmStatic
    fun outbound(connection: Connection, packet: Packet<*>) {
        if (connection.sending != PacketFlow.SERVERBOUND) return
        dispatch(sinks, false, packet)
    }

    internal fun dispatch(targets: Array<Sink>, inbound: Boolean, packet: Packet<*>) {
        if (packet is BundlePacket<*>) {
            bundle = ++bundles
            try {
                for (sub in packet.subPackets()) dispatch(targets, inbound, sub)
            } finally {
                bundle = 0
            }
            return
        }
        for (sink in targets) {
            try {
                sink.packet(inbound, packet)
            } catch (t: Throwable) {
                logger.warn("Packet sink ${sink.javaClass.name} failed: $t")
            }
        }
    }
}
