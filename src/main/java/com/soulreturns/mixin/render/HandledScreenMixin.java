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
     * Inject after each slot is drawn to add highlighting
     *
     * Target: HandledScreen.drawSlot(DrawContext, Slot)
     * Injection point: TAIL (after the slot has been drawn)
     */
    @Inject(
        method = "drawSlot",
        at = @At("TAIL")
    )
    private void onDrawSlot(DrawContext context, Slot slot, CallbackInfo ci) {
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

        // Draw the highlight border
        RenderHelper.drawSlotHighlight(context, slot.x, slot.y, color);
    }
}
