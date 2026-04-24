package com.soulreturns.mixin.render;

import com.soulreturns.util.DebugLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.ConfigInstanceKt.getConfig;

/**
 * Mixin to log all player-entered chat input (including commands) when debug logging is enabled,
 * and to scale the chat screen (messages + input field) when chat scale != 1.
 */
@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Inject(method = "sendMessage", at = @At("HEAD"))
    private void soulmod$logChatInput(String chatText, boolean addToHistory, CallbackInfo ci) {
        DebugLogger.INSTANCE.logChatInput(chatText);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void beforeRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        float scale = getConfig().renderCategory.hudScaleSubCategory.chatScale;
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        if (scale != 1.0f) {
            float pivotY = MinecraftClient.getInstance().getWindow().getScaledHeight();
            matrices.translate(0.0f, pivotY);
            matrices.scale(scale, scale);
            matrices.translate(0.0f, -pivotY);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void afterRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        context.getMatrices().popMatrix();
    }
}
