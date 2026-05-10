package com.soulreturns.mixin.render;

import com.soulreturns.features.itemhighlight.HighlightManager;
import com.soulreturns.platform.mixinbridge.RenderHelper;
import com.soulreturns.util.SkyblockItemUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;

@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {

    // ── Item highlighting ─────────────────────────────────────────────────────

    /**
     * Inject after each slot is drawn to add highlighting.
     * Note: the int parameters are mouseX/mouseY, not slot coordinates.
     * slot.x/slot.y are already absolute screen coordinates (verified from bytecode).
     */

    @Inject(method = "renderSlot", at = @At("TAIL"))
    private void onDrawSlot(GuiGraphics context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        handleDrawSlot(context, slot);
    }

    private void handleDrawSlot(GuiGraphics context, Slot slot) {
        ItemStack stack = slot.getItem();
        if (stack == null || stack.isEmpty()) return;

        String skyblockId = SkyblockItemUtils.INSTANCE.getSkyblockId(stack);
        if (skyblockId == null) return;

        Integer color = HighlightManager.INSTANCE.getColorForItem(skyblockId);
        if (color == null) return;

        RenderHelper.drawSlotHighlight(context, slot.x, slot.y, color);
    }
}

