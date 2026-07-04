package com.soulreturns.mixin;

import com.soulreturns.features.qol.HideFoliage;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets attacks hit mobs standing in hidden foliage. Vanilla's crosshair pick caps the entity
 * sweep at the block hit — grass in front of a mob makes the mob untargetable. When the vanilla
 * pick lands on a hidden-foliage block, we re-sweep for entities past the foliage (see
 * {@link HideFoliage#pickEntityThroughFoliage}) and prefer the entity if one is in reach.
 *
 * ONLY the entity result is upgraded: with no entity behind, the vanilla foliage block hit is
 * kept as-is, so block breaking/placing can never go through foliage (anticheat-safe).
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
    @Inject(
        method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void soul$pickThroughFoliage(
        Entity cameraEntity,
        double blockInteractionRange,
        double entityInteractionRange,
        float partialTicks,
        CallbackInfoReturnable<HitResult> cir
    ) {
        HitResult vanilla = cir.getReturnValue();
        if (!(vanilla instanceof BlockHitResult blockHit) || vanilla.getType() != HitResult.Type.BLOCK) return;
        if (!HideFoliage.shouldHide(cameraEntity.level().getBlockState(blockHit.getBlockPos()).getBlock())) return;
        EntityHitResult through =
            HideFoliage.pickEntityThroughFoliage(cameraEntity, blockInteractionRange, entityInteractionRange, partialTicks);
        if (through != null) cir.setReturnValue(through);
    }
}
