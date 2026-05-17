package com.soulreturns.mixin.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;

@Mixin(EffectsInInventory.class)
public class EffectsInInventoryMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void hideInventoryEffects(GuiGraphics context, int mouseX, int mouseY, CallbackInfo ci) {
        if (getCfg().render.hideEffectsInInventory()) {
            ci.cancel();
        }
    }
}
