package com.soulreturns.devbridge.mixin;

import com.soulreturns.devbridge.Gate;
import com.soulreturns.devbridge.MenuEvents;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Slot clicks, seen before the click changes the slot. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void soulBridge$click(int containerId, int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (Gate.listening) Gate.run(() -> MenuEvents.beforeClick(containerId, slot, button, input));
    }
}
