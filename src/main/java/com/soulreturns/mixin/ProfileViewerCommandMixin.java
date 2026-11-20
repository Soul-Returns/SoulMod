package com.soulreturns.mixin;

import com.soulreturns.features.profileviewer.SpvCommand;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Must precede Fabric's message hook (800) and command dispatcher (1000), not just their event phases.
@Mixin(value = ClientPacketListener.class, priority = 700)
public abstract class ProfileViewerCommandMixin {
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void soul$profileCommand(String command, CallbackInfo ci) {
        if (SpvCommand.interceptPv(command)) ci.cancel();
    }

    @Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
    private void soul$unattendedProfileCommand(String command, Screen screen, CallbackInfo ci) {
        if (SpvCommand.interceptPv(command)) ci.cancel();
    }
}
