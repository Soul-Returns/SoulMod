package com.soulreturns.platform

import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket

/**
 * Counts the server's `ping` packets (id ≠ 0) where they reach `Connection.channelRead0` (netty thread), split by
 * whether they came alone or inside a bundle. A hook there (Odin's tick timers count this way) sees a bundle as one
 * packet, so it counts only [standalone]. Lone pings are one per server tick (F7 recording 2026-11-13: 7575 in 7580
 * ticks; the bundled ones come with entity updates) and drive [ServerTime]; the counts are for the debug log.
 */
object ServerPings {
    @Volatile
    var standalone = 0L
        private set

    @Volatile
    var bundled = 0L
        private set

    /** `ConnectionMixin.channelRead0` HEAD: every inbound packet of the client's connection. */
    @JvmStatic
    fun onPacket(packet: Packet<*>) {
        if (packet is ClientboundPingPacket) {
            if (packet.id != 0) {
                standalone++
                ServerTime.onPing()
            }
        } else if (packet is BundlePacket<*>) {
            for (sub in packet.subPackets()) if (sub is ClientboundPingPacket && sub.id != 0) bundled++
        }
    }
}
