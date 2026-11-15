package com.soulreturns.mixin;

import com.soulreturns.features.darkui.DarkUi;
import com.soulreturns.features.dungeons.terminals.TerminalSolver;
import com.soulreturns.features.sacks.SackMenus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {
    // Dark UI: SkyBlock's filler panes become plain background, frame included.
    // Pose is already translated to (leftPos, topPos) here, so slot.x/slot.y align as-is.
    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void soul$darkFiller(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (DarkUi.INSTANCE.isFiller(slot.getItem())) {
            g.fill(slot.x - 1, slot.y - 1, slot.x + 17, slot.y + 17, DarkUi.INSTANCE.getBACKGROUND());
            ci.cancel();
        }
    }

    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void soul$highlightSlot(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        SackMenus.onExtractSlot(g, slot);
        TerminalSolver.onExtractSlot((AbstractContainerScreen<?>) (Object) this, g, slot);
    }

    // Dark UI: the title and "Inventory" labels.
    @ModifyArg(
            method = "extractLabels",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
            index = 4)
    private int soul$darkLabel(int color) {
        return DarkUi.INSTANCE.label(color);
    }
}
