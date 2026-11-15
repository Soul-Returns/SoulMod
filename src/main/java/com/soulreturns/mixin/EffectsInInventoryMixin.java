package com.soulreturns.mixin;

import com.soulreturns.config.ConfigManagerKt;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides the potion-effect list beside the inventory (the "Hide effects in inventory" toggle).
 * There is no Fabric hook for this surface, hence the mixin. {@code canSeeEffects} is forced to
 * {@code false} too so layout code that shifts the screen for the effect list agrees with us.
 */
@Mixin(EffectsInInventory.class)
public class EffectsInInventoryMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void soul$hideEffects(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
        if (ConfigManagerKt.getCfg().getGeneral().getEffectsInventoryHidden()) {
            ci.cancel();
        }
    }

    @Inject(method = "canSeeEffects", at = @At("HEAD"), cancellable = true)
    private void soul$hideCanSeeEffects(CallbackInfoReturnable<Boolean> cir) {
        if (ConfigManagerKt.getCfg().getGeneral().getEffectsInventoryHidden()) {
            cir.setReturnValue(false);
        }
    }
}
