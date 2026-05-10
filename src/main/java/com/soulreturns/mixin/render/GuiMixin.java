package com.soulreturns.mixin.render;

import com.soulreturns.platform.mixinbridge.SoulGuiHudAdapter;
import com.soulreturns.platform.mixinbridge.RenderHelper;
import com.soulreturns.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;
import static com.soulreturns.platform.mixinbridge.RenderHelper.pushScaledMatrix;

@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "renderSelectedItemName", at = @At("HEAD"), cancellable = true)
    public void renderHeldItemTooltip(GuiGraphics context, CallbackInfo ci) {
        if (getCfg().render.hideHeldItemTooltip()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    public void onRender(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        RenderUtils.INSTANCE.renderAlerts(context);
        SoulGuiHudAdapter.INSTANCE.renderHud(context);
    }

    // ── Hotbar / main HUD (hotbar + XP bar + health/food/armor bars) ──────────

    @Inject(method = "renderHotbarAndDecorations", at = @At("HEAD"))
    private void beforeRenderMainHud(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        float scale = getCfg().render.hudScale.hotbarScale();
        float pivotX = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2.0f;
        float pivotY = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        pushScaledMatrix(context, scale, pivotX, pivotY);
    }

    @Inject(method = "renderHotbarAndDecorations", at = @At("RETURN"))
    private void afterRenderMainHud(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        context.pose().popMatrix();
    }

    // ── Action bar ────────────────────────────────────────────────────────────

    @Inject(method = "renderOverlayMessage", at = @At("HEAD"))
    private void beforeRenderOverlayMessage(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        float scale = getCfg().render.hudScale.actionBarScale();
        float pivotX = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2.0f;
        float pivotY = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        pushScaledMatrix(context, scale, pivotX, pivotY);
    }

    @Inject(method = "renderOverlayMessage", at = @At("RETURN"))
    private void afterRenderOverlayMessage(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        context.pose().popMatrix();
    }

    // ── Boss bar ──────────────────────────────────────────────────────────────

    @Inject(method = "renderBossOverlay", at = @At("HEAD"))
    private void beforeRenderBossBarHud(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        float scale = getCfg().render.hudScale.bossBarScale();
        float pivotX = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2.0f;
        pushScaledMatrix(context, scale, pivotX, 0.0f);
    }

    @Inject(method = "renderBossOverlay", at = @At("RETURN"))
    private void afterRenderBossBarHud(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        context.pose().popMatrix();
    }

    // ── Scoreboard ────────────────────────────────────────────────────────────

    @Inject(
        method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("HEAD")
    )
    private void beforeRenderScoreboardSidebar(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        float scale = getCfg().render.hudScale.scoreboardScale();
        float pivotX = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        float pivotY = Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2.0f;
        pushScaledMatrix(context, scale, pivotX, pivotY);
    }

    @Inject(
        method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("RETURN")
    )
    private void afterRenderScoreboardSidebar(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        context.pose().popMatrix();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    // pushScaledMatrix is in RenderHelper
}
