package com.soulreturns.devbridge.mixin;

import com.soulreturns.devbridge.Gate;
import com.soulreturns.devbridge.Ticks;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server tick clock: counts the per-tick ping after vanilla handled it (vanilla sends the pong, not this). */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
    @Inject(method = "handlePing", at = @At("RETURN"))
    private void soulBridge$ping(ClientboundPingPacket packet, CallbackInfo ci) {
        if (Gate.on) Ticks.onPing(packet.getId());
    }
}
