package com.soulreturns.mixin.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.ConfigInstanceKt.getConfig;

@Mixin(ChatHud.class)
public class ChatHudMixin {

    // Targets the public render overload called by both InGameHud (unfocused overlay)
    // and ChatScreen (focused, when pressing T). Using RETURN (not TAIL) so the pop
    // fires at every return point, including any early-return guard clauses.
    @Inject(
        method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V",
        at = @At("HEAD")
    )
    private void beforeRenderChat(DrawContext context, TextRenderer textRenderer,
                                  int i1, int i2, int i3, boolean b1, boolean b2,
                                  CallbackInfo ci) {
        // When ChatScreen is open, ChatScreenMixin scales the whole screen (messages + input)
        // so we skip here to avoid double-scaling the messages.
        if (MinecraftClient.getInstance().currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) {
            context.getMatrices().pushMatrix();
            return;
        }
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

    @Inject(
        method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V",
        at = @At("RETURN")
    )
    private void afterRenderChat(DrawContext context, TextRenderer textRenderer,
                                 int i1, int i2, int i3, boolean b1, boolean b2,
                                 CallbackInfo ci) {
        context.getMatrices().popMatrix();
    }
}
