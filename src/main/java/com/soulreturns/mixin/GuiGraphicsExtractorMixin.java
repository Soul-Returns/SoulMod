package com.soulreturns.mixin;

import com.soulreturns.features.darkui.DarkUi;
import com.soulreturns.features.inventory.InventoryCleanup;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(GuiGraphicsExtractor.class)
public class GuiGraphicsExtractorMixin {
    /** Dark UI: every texture blit funnels through {@code innerBlit}; container backgrounds are swapped there. */
    @ModifyVariable(
            method = "innerBlit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIFFFFI)V",
            at = @At("HEAD"),
            argsOnly = true)
    private Identifier soul$darkTexture(Identifier texture) {
        return DarkUi.INSTANCE.texture(texture);
    }

    /** True while vanilla draws the deferred tooltip, which {@link #soul$hiddenElementTooltip} already judged when it was set. */
    @Unique
    private static boolean soul$deferred;

    /**
     * "Hide crafting grid / off-hand slot / recipe book" and the SkyBlock info display: a tooltip asked
     * for over something Soul covers in the player inventory is dropped (Soul's own are let through).
     */
    @Inject(method = "setTooltipForNextFrameInternal", at = @At("HEAD"), cancellable = true)
    private void soul$hiddenElementTooltip(Font font, List<ClientTooltipComponent> lines, int x, int y,
                                           ClientTooltipPositioner positioner, Identifier style, boolean replace,
                                           CallbackInfo ci) {
        if (InventoryCleanup.INSTANCE.hidesVanillaTooltip(x, y)) {
            ci.cancel();
        }
    }

    /**
     * The same for tooltips other mods draw directly instead of setting them for the next frame (REI's
     * click-area "View Recipes for Crafting" under the info display, from its late overlay render).
     */
    @Inject(method = "tooltip", at = @At("HEAD"), cancellable = true)
    private void soul$hiddenElementDirectTooltip(Font font, List<ClientTooltipComponent> lines, int x, int y,
                                                 ClientTooltipPositioner positioner, Identifier style, CallbackInfo ci) {
        if (!soul$deferred && InventoryCleanup.INSTANCE.hidesVanillaTooltip(x, y)) {
            ci.cancel();
        }
    }

    // Static, not per instance: the deferred tooltip draws on the extractor that set it, which need not be
    // the one running extractDeferredElements (REI calls it on its own GuiGraphics wrapper).
    @Inject(method = "extractDeferredElements", at = @At("HEAD"))
    private void soul$deferredStart(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        soul$deferred = true;
    }

    @Inject(method = "extractDeferredElements", at = @At("RETURN"))
    private void soul$deferredEnd(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        soul$deferred = false;
    }
}
