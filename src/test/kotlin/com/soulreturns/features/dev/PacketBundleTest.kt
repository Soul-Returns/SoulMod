package com.soulreturns.features.dev

import com.soulreturns.platform.ServerPings
import net.minecraft.SharedConstants
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.server.Bootstrap
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The tap marks bundle sub-packets (a `channelRead0` hook such as Odin's never sees them); the ping counter splits the same way. */
class PacketBundleTest {
    @BeforeTest
    fun boot() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun subPacketsCarryTheirBundleAndLonePacketsNone() {
        val seen = ArrayList<Pair<Int, Long>>()
        val sink = PacketTap.Sink { _, p -> seen += (p as ClientboundPingPacket).id to PacketTap.bundle }
        val sinks = arrayOf(sink)
        PacketTap.dispatch(sinks, true, ClientboundPingPacket(-1))
        PacketTap.dispatch(sinks, true, ClientboundBundlePacket(listOf<Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>>(ClientboundPingPacket(-2), ClientboundPingPacket(-3))))
        PacketTap.dispatch(sinks, true, ClientboundPingPacket(-4))
        val b = seen[1].second
        assertEquals(listOf(-1 to 0L, -2 to b, -3 to b, -4 to 0L), seen)
        assertEquals(0L, PacketTap.bundle)
    }

    @Test
    fun pingCounterSplitsLoneAndBundledPings() {
        val alone = ServerPings.standalone
        val inBundles = ServerPings.bundled
        ServerPings.onPacket(ClientboundPingPacket(-7))
        ServerPings.onPacket(ClientboundPingPacket(0))
        ServerPings.onPacket(ClientboundBundlePacket(listOf<Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>>(ClientboundPingPacket(-8), ClientboundPingPacket(-9))))
        assertEquals(1, ServerPings.standalone - alone, "id 0 is not counted")
        assertEquals(2, ServerPings.bundled - inBundles)
    }
}
