package com.soulreturns.mixin.render;

import com.soulreturns.features.itemhighlight.HighlightManager;
import com.soulreturns.util.RenderHelper;
import com.soulreturns.util.SkyblockItemUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.ConfigInstanceKt.getConfig;

@Mixin(HandledScreen.class)
public class HandledScreenMixin {

    /**
     * Inject after each slot is drawn to add highlighting.
     *
     * For 1.21.8 and 1.21.10, HandledScreen#drawSlot(DrawContext, Slot) is used.
     * For 1.21.11+, the signature became HandledScreen#drawSlot(DrawContext, Slot, int, int).
     * We use Stonecutter conditionals to generate the correct overload per version.
     */

    //? if <1.21.11 {
    /*@Inject(method = "drawSlot", at = @At("TAIL"))
    private void onDrawSlot(DrawContext context, Slot slot, CallbackInfo ci) {
        handleDrawSlot(context, slot, slot.x, slot.y);
    }
    *///?} else {
    @Inject(method = "drawSlot", at = @At("TAIL"))
    private void onDrawSlot(DrawContext context, Slot slot, int x, int y, CallbackInfo ci) {
        handleDrawSlot(context, slot, x, y);
    }
    //?}

    private void handleDrawSlot(DrawContext context, Slot slot, int x, int y) {
        // Check if the feature is enabled
        if (!getConfig().renderCategory.highlightSubCategory.itemHighlightingEnabled) return;

        // Get the item in this slot
        ItemStack stack = slot.getStack();
        if (stack == null || stack.isEmpty()) return;

        // Get the Skyblock ID from the item
        String skyblockId = SkyblockItemUtils.INSTANCE.getSkyblockId(stack);
        if (skyblockId == null) return;

        // Check if this item should be highlighted and get its color
        Integer color = HighlightManager.INSTANCE.getColorForItem(skyblockId);
        if (color == null) return;

        // Draw the highlight border at the slot's position
        RenderHelper.drawSlotHighlight(context, x, y, color);
    }
}
