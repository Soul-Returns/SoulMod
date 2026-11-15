package com.soulreturns.mixin;

import com.soulreturns.features.darkui.DarkUi;
import com.soulreturns.features.inventory.InventoryCleanup;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
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

    /**
     * "Hide crafting grid / off-hand slot / recipe book": every tooltip funnels through here; one
     * asked for over a hidden element of the player inventory is dropped (Soul's own are let through).
     */
    @Inject(method = "setTooltipForNextFrameInternal", at = @At("HEAD"), cancellable = true)
    private void soul$hiddenElementTooltip(Font font, List<ClientTooltipComponent> lines, int x, int y,
                                           ClientTooltipPositioner positioner, Identifier style, boolean replace,
                                           CallbackInfo ci) {
        if (InventoryCleanup.INSTANCE.hidesVanillaTooltip(x, y)) {
            ci.cancel();
        }
    }
}
