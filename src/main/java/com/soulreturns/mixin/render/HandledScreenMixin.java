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

    // ── Item highlighting ─────────────────────────────────────────────────────

    /**
     * Inject after each slot is drawn to add highlighting.
     */

    @Inject(method = "drawSlot", at = @At("TAIL"))
    private void onDrawSlot(DrawContext context, Slot slot, int x, int y, CallbackInfo ci) {
        handleDrawSlot(context, slot, x, y);
    }

    private void handleDrawSlot(DrawContext context, Slot slot, int x, int y) {
        if (!getConfig().renderCategory.highlightSubCategory.itemHighlightingEnabled) return;

        ItemStack stack = slot.getStack();
        if (stack == null || stack.isEmpty()) return;

        String skyblockId = SkyblockItemUtils.INSTANCE.getSkyblockId(stack);
        if (skyblockId == null) return;

        Integer color = HighlightManager.INSTANCE.getColorForItem(skyblockId);
        if (color == null) return;

        RenderHelper.drawSlotHighlight(context, x, y, color);
    }
}

