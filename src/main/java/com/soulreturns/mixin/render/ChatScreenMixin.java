package com.soulreturns.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.soulreturns.util.DebugLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
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

    // ── Fix click hit-testing with scaled chat ────────────────────────────────
    // ChatScreen.mouseClicked uses click.x() / click.y() as raw screen coordinates
    // to find which text style was clicked. Since the chat is visually scaled around
    // pivot (0, screenHeight), we must inverse-transform those coordinates so they
    // match the unscaled positions that ChatHud's hit-test logic expects.

    @WrapOperation(
        method = "mouseClicked",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Click;x()D")
    )
    private double transformClickX(Click click, Operation<Double> original) {
        double rawX = original.call(click);
        float scale = getConfig().renderCategory.hudScaleSubCategory.chatScale;
        if (scale == 1.0f) return rawX;
        return rawX / scale; // pivot x = 0  →  logical_x = rawX / scale
    }

    @WrapOperation(
        method = "mouseClicked",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Click;y()D")
    )
    private double transformClickY(Click click, Operation<Double> original) {
        double rawY = original.call(click);
        float scale = getConfig().renderCategory.hudScaleSubCategory.chatScale;
        if (scale == 1.0f) return rawY;
        double screenHeight = MinecraftClient.getInstance().getWindow().getScaledHeight();
        return screenHeight + (rawY - screenHeight) / scale;
    }
}

