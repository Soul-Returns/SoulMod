package com.soulreturns.mixin;

import com.soulreturns.config.ConfigManagerKt;
import com.soulreturns.features.darkui.DarkUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public class InventoryScreenMixin {
    /** "Hide crafting grid": the inventory's only label is the "Crafting" title, so skip it. */
    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true)
    private void soul$hideCraftingLabel(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
        if (ConfigManagerKt.getCfg().getGeneral().getCraftingGridHidden()) {
            ci.cancel();
        }
    }

    /** Dark UI: lighten the "Crafting" label. */
    @ModifyArg(
            method = "extractLabels",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
            index = 4)
    private int soul$darkLabel(int color) {
        return DarkUi.INSTANCE.label(color);
    }
}
