package com.soulreturns.mixin.render;

import com.soulreturns.features.qol.HideFoliage;
import net.minecraft.client.renderer.LevelEventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mutes hidden foliage breaking: level event 2001 (block destroy, {@code data} = block-state id)
 * plays the break sound AND spawns the break particles — one cancel kills both. The client never
 * predicts these locally ({@code MultiPlayerGameMode.destroyBlock} fires no 2001), so this
 * server-echo path is the only source.
 */
@Mixin(LevelEventHandler.class)
public class LevelEventHandlerMixin {
    @Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
    private void soul$muteHiddenFoliage(int eventType, BlockPos pos, int data, CallbackInfo ci) {
        if (eventType == 2001 && HideFoliage.shouldHide(Block.stateById(data).getBlock())) {
            ci.cancel();
        }
    }
}
