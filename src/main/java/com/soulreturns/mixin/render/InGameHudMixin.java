package com.soulreturns.mixin.render;

import com.soulreturns.gui.SoulGuiHudAdapter;
import com.soulreturns.util.RenderHelper;
import com.soulreturns.util.RenderUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;
import static com.soulreturns.util.RenderHelper.pushScaledMatrix;

@Mixin(InGameHud.class)
public class InGameHudMixin {

    @Inject(method = "renderHeldItemTooltip", at = @At("HEAD"), cancellable = true)
    public void renderHeldItemTooltip(DrawContext context, CallbackInfo ci) {
        if (getCfg().render.hideHeldItemTooltip()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    public void onRender(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        RenderUtils.INSTANCE.renderAlerts(context);
        SoulGuiHudAdapter.INSTANCE.renderHud(context);
    }

    // ── Hotbar / main HUD (hotbar + XP bar + health/food/armor bars) ──────────

    @Inject(method = "renderMainHud", at = @At("HEAD"))
    private void beforeRenderMainHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        float scale = getCfg().render.hudScale.hotbarScale();
        float pivotX = MinecraftClient.getInstance().getWindow().getScaledWidth() / 2.0f;
        float pivotY = MinecraftClient.getInstance().getWindow().getScaledHeight();
        pushScaledMatrix(context, scale, pivotX, pivotY);
    }

    @Inject(method = "renderMainHud", at = @At("RETURN"))
    private void afterRenderMainHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        context.getMatrices().popMatrix();
    }

    // ── Action bar ────────────────────────────────────────────────────────────

    @Inject(method = "renderOverlayMessage", at = @At("HEAD"))
    private void beforeRenderOverlayMessage(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        float scale = getCfg().render.hudScale.actionBarScale();
        float pivotX = MinecraftClient.getInstance().getWindow().getScaledWidth() / 2.0f;
        float pivotY = MinecraftClient.getInstance().getWindow().getScaledHeight();
        pushScaledMatrix(context, scale, pivotX, pivotY);
    }

    @Inject(method = "renderOverlayMessage", at = @At("RETURN"))
    private void afterRenderOverlayMessage(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        context.getMatrices().popMatrix();
    }

    // ── Boss bar ──────────────────────────────────────────────────────────────

    @Inject(method = "renderBossBarHud", at = @At("HEAD"))
    private void beforeRenderBossBarHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        float scale = getCfg().render.hudScale.bossBarScale();
        float pivotX = MinecraftClient.getInstance().getWindow().getScaledWidth() / 2.0f;
        pushScaledMatrix(context, scale, pivotX, 0.0f);
    }

    @Inject(method = "renderBossBarHud", at = @At("RETURN"))
    private void afterRenderBossBarHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        context.getMatrices().popMatrix();
    }

    // ── Scoreboard ────────────────────────────────────────────────────────────

    @Inject(
        method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
        at = @At("HEAD")
    )
    private void beforeRenderScoreboardSidebar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        float scale = getCfg().render.hudScale.scoreboardScale();
        float pivotX = MinecraftClient.getInstance().getWindow().getScaledWidth();
        float pivotY = MinecraftClient.getInstance().getWindow().getScaledHeight() / 2.0f;
        pushScaledMatrix(context, scale, pivotX, pivotY);
    }

    @Inject(
        method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
        at = @At("RETURN")
    )
    private void afterRenderScoreboardSidebar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        context.getMatrices().popMatrix();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    // pushScaledMatrix is in RenderHelper
}
