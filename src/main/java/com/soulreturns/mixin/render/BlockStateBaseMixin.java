package com.soulreturns.mixin.render;

import com.soulreturns.features.qol.HideFoliage;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The "Hide foliage" toggle's render hook (see {@link HideFoliage} for the block set):
 * {@code getRenderShape} → {@code INVISIBLE} makes the chunk mesher skip the block entirely.
 * Flipping the toggle needs a chunk rebuild — {@code HideFoliage.register}'s tick watcher
 * does that.
 *
 * Deliberately does NOT touch {@code getShape}: the outline shape drives the block-interaction
 * raycast, and letting the player target/break/place through hidden foliage risks tripping
 * server anticheat. Hitting mobs through foliage is handled separately (entity pick only) in
 * {@code LocalPlayerMixin}.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    @Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true)
    private void soul$hideFoliage(CallbackInfoReturnable<RenderShape> cir) {
        if (HideFoliage.shouldHide(((BlockBehaviour.BlockStateBase) (Object) this).getBlock())) {
            cir.setReturnValue(RenderShape.INVISIBLE);
        }
    }
}
