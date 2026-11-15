package com.soulreturns.mixin;

import com.soulreturns.config.ConfigManagerKt;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "Hide recipe book": the player inventory's book reports itself closed. Every draw, input and
 * layout path asks {@code isVisible}, so the inventory also stays centered instead of shifted.
 * Only {@link InventoryMenu}: a crafting table keeps its book.
 */
@Mixin(RecipeBookComponent.class)
public class RecipeBookHideMixin<T extends RecipeBookMenu> {
    @Shadow @Final protected T menu;

    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true)
    private void soul$hideInInventory(CallbackInfoReturnable<Boolean> cir) {
        if (menu instanceof InventoryMenu && ConfigManagerKt.getCfg().getGeneral().getRecipeBookHidden()) {
            cir.setReturnValue(false);
        }
    }
}
