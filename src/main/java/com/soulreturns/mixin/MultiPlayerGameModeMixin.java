package com.soulreturns.mixin;

import com.soulreturns.features.islandchests.IslandChests;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    private void soul$observeChestInput(int containerId, int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        IslandChests.onContainerInput(containerId);
    }
}
