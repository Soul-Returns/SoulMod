package com.soulreturns.mixin.render;

import com.soulreturns.features.party.PartyManager;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the visible chat output of `/party list` while {@link PartyManager} is silently
 * consuming the response (the expect window opened by {@code PartyManager.requestRefresh}).
 *
 * Parsing happens via the normal {@code MessageHandler.GAME / CHAT} path; the mixin only
 * cancels the display step. This is more robust than Fabric's
 * {@code ClientReceiveMessageEvents.ALLOW_*} hooks because those can be bypassed by other
 * mods (SkyHanni / Skytils) that intercept chat earlier in the pipeline.
 */
@Mixin(ChatComponent.class)
public class ChatComponentAddMessageMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void soul_suppressPartyListDisplay(Component message, CallbackInfo ci) {
        if (PartyManager.shouldSuppressForDisplay(message.getString())) {
            ci.cancel();
        }
    }
}
