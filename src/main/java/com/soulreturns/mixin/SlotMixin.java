package com.soulreturns.mixin;

import com.soulreturns.config.ConfigManagerKt;
import com.soulreturns.config.GeneralConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "Hide off-hand slot" / "Hide crafting grid": an inactive slot is neither drawn, hovered nor
 * clicked by the screen. Client side only — the integrated server shares this class, and its
 * logic must not change. The frames are part of the inventory texture; {@code InventoryCleanup}
 * paints over them.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow @Final public Container container;
    @Shadow public int index;

    @Shadow public abstract int getContainerSlot();

    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true)
    private void soul$hideInventoryParts(CallbackInfoReturnable<Boolean> cir) {
        GeneralConfig general = ConfigManagerKt.getCfg().getGeneral();
        if (general.getOffhandSlotHidden()
                && container instanceof Inventory inventory
                && getContainerSlot() == Inventory.SLOT_OFFHAND
                && inventory.player.level().isClientSide()) {
            cir.setReturnValue(false);
            return;
        }
        // The crafting grid is identified by identity in the client player's own inventory menu:
        // a crafting table's 3x3 grid and the server's copy of this menu are never matched.
        if (general.getCraftingGridHidden()
                && index >= InventoryMenu.RESULT_SLOT && index < InventoryMenu.CRAFT_SLOT_END) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.inventoryMenu.slots.get(index) == (Object) this) {
                cir.setReturnValue(false);
            }
        }
    }
}
