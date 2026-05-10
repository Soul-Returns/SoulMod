package com.soulreturns.mixin;

import com.soulreturns.features.farming.FarmingTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks into the client-side block-destroy path so we can drive {@link FarmingTimer} from
 * actual block breaks (instabreak via Cactus Knife etc.). {@code destroyBlock} fires once
 * per successful break — both the instant-finish path and the progressive-mining-finished
 * path funnel through this method.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"))
    private void soul$onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BlockState state = mc.level.getBlockState(pos);
        FarmingTimer.INSTANCE.onBlockBreak(state.getBlock());
    }
}
