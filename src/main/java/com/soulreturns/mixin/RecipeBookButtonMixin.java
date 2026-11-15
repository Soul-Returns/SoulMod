package com.soulreturns.mixin;

import com.soulreturns.config.ConfigManagerKt;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "Hide recipe book": the player inventory never adds the green book button. */
@Mixin(AbstractRecipeBookScreen.class)
public class RecipeBookButtonMixin {
    @Inject(method = "initButton", at = @At("HEAD"), cancellable = true)
    private void soul$skipButton(CallbackInfo ci) {
        if ((Object) this instanceof InventoryScreen && ConfigManagerKt.getCfg().getGeneral().getRecipeBookHidden()) {
            ci.cancel();
        }
    }
}
