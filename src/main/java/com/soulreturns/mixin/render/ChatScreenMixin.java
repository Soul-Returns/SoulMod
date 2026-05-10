package com.soulreturns.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.soulreturns.util.DebugLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;

/**
 * Mixin to log all player-entered chat input (including commands) when debug logging is enabled,
 * and to scale the chat screen (messages + input field) when chat scale != 1.
 */
@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Inject(method = "handleChatInput", at = @At("HEAD"))
    private void soulmod$logChatInput(String chatText, boolean addToHistory, CallbackInfo ci) {
        DebugLogger.INSTANCE.logChatInput(chatText);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void beforeRender(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        float scale = getCfg().render.hudScale.chatScale();
        Matrix3x2fStack matrices = context.pose();
        matrices.pushMatrix();
        if (scale != 1.0f) {
            float pivotY = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            matrices.translate(0.0f, pivotY);
            matrices.scale(scale, scale);
            matrices.translate(0.0f, -pivotY);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void afterRender(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        context.pose().popMatrix();
    }

    // ── Fix click hit-testing with scaled chat ────────────────────────────────
    // ChatScreen.mouseClicked uses click.x() / click.y() as raw screen coordinates
    // to find which text style was clicked. Since the chat is visually scaled around
    // pivot (0, screenHeight), we must inverse-transform those coordinates so they
    // match the unscaled positions that ChatHud's hit-test logic expects.

    @WrapOperation(
        method = "mouseClicked",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/input/MouseButtonEvent;x()D")
    )
    private double transformClickX(MouseButtonEvent click, Operation<Double> original) {
        double rawX = original.call(click);
        float scale = getCfg().render.hudScale.chatScale();
        if (scale == 1.0f) return rawX;
        return rawX / scale; // pivot x = 0  →  logical_x = rawX / scale
    }

    @WrapOperation(
        method = "mouseClicked",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/input/MouseButtonEvent;y()D")
    )
    private double transformClickY(MouseButtonEvent click, Operation<Double> original) {
        double rawY = original.call(click);
        float scale = getCfg().render.hudScale.chatScale();
        if (scale == 1.0f) return rawY;
        double screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return screenHeight + (rawY - screenHeight) / scale;
    }
}

