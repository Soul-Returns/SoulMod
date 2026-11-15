package com.soulreturns.mixin;

import com.soulreturns.util.ChatDisplayFilter;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides a chat line's <em>display</em> when a {@link ChatDisplayFilter} predicate matches (e.g. the
 * combo-overlay's "hide combo messages" option). Every add-to-chat path funnels through this private
 * {@code addMessage}, so cancelling here drops the visible entry — but Fabric's
 * {@code ClientReceiveMessageEvents} already fired in the packet listener, so other mods still see
 * the message. Deliberately display-only (the legacy mod's {@code /party list} suppression pattern).
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {
    @Inject(method = "addMessage", at = @At("HEAD"), cancellable = true)
    private void soul$hideFilteredMessages(
        Component contents,
        MessageSignature signature,
        GuiMessageSource source,
        GuiMessageTag tag,
        CallbackInfo ci
    ) {
        if (ChatDisplayFilter.shouldHide(contents)) {
            ci.cancel();
        }
    }
}
