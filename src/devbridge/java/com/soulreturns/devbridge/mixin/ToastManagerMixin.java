package com.soulreturns.devbridge.mixin;

import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The offline test client can never validate profile keys, so every join shows "Chat messages can't
 * be verified" over the top right of each screenshot. Dropped only when the harness launches with
 * {@code -Dsoul.testclient=true}; the bridge in a player's own instance leaves it alone.
 */
@Mixin(ToastManager.class)
public class ToastManagerMixin {
    private static final boolean TEST_CLIENT = Boolean.getBoolean("soul.testclient");

    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
    private void soulDevbridge$dropUnsecureServerToast(Toast toast, CallbackInfo ci) {
        if (TEST_CLIENT && toast instanceof SystemToast system
            && system.getToken() == SystemToast.SystemToastId.UNSECURE_SERVER_WARNING) {
            ci.cancel();
        }
    }
}
