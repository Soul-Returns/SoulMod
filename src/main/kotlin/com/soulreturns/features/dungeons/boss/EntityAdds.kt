package com.soulreturns.features.dungeons.boss

import com.soulreturns.features.dungeons.devices.GoldorPhase
import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket

/**
 * Entity adds for the F7/M7 helpers on the netty thread, stamped with their arrival tick there. Hypixel sends every
 * spawn inside a bundle (M7 recording 2026-11-13: 71137 of 71157 falling blocks, 42 of 42 dragons), and vanilla unpacks
 * a bundle on the client thread only, so `handleAddEntity`'s netty pass never sees those: `ConnectionMixin` hands the
 * bundle here before that. Lone adds come from `ClientPacketListenerMixin.handleAddEntity`'s netty pass. Observe only.
 */
object EntityAdds {
    @JvmStatic
    fun onAdd(packet: ClientboundAddEntityPacket) {
        F7Boss.onAddEntity(packet)
        GoldorPhase.onAddEntity(packet)
        DragonCountdown.onAddEntity(packet)
    }

    @JvmStatic
    fun onBundle(bundle: BundlePacket<*>) {
        for (sub in bundle.subPackets()) if (sub is ClientboundAddEntityPacket) onAdd(sub)
    }
}
