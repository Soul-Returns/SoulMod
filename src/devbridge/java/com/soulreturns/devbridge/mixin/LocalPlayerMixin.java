package com.soulreturns.devbridge.mixin;

import com.soulreturns.devbridge.Gate;
import com.soulreturns.devbridge.MenuEvents;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Menu closes: the player's own (closeContainer) and the server's both end in clientSideCloseContainer. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Inject(method = "closeContainer", at = @At("HEAD"))
    private void soulBridge$clientClose(CallbackInfo ci) {
        if (Gate.listening) Gate.run(MenuEvents::clientClosing);
    }

    @Inject(method = "clientSideCloseContainer", at = @At("HEAD"))
    private void soulBridge$close(CallbackInfo ci) {
        if (Gate.listening) Gate.run(MenuEvents::beforeClose);
    }
}
