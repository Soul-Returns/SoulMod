package com.soulreturns.mixin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;
import static com.soulreturns.util.RenderHelper.pushScaledMatrix;

@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void beforeRender(GuiGraphics context, int scaledWindowWidth, Scoreboard scoreboard, Objective objective, CallbackInfo ci) {
        float scale = getCfg().render.hudScale.tabListScale();
        float pivotX = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2.0f;
        pushScaledMatrix(context, scale, pivotX, 0.0f);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void afterRender(GuiGraphics context, int scaledWindowWidth, Scoreboard scoreboard, Objective objective, CallbackInfo ci) {
        context.pose().popMatrix();
    }
}
