package com.soulreturns.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;

/**
 * Restores 1.8 sneaking eye height (1.54 blocks) instead of modern height (~1.27 blocks).
 * Used for compatibility with 1.8.9 servers.
 */
@Mixin(LivingEntity.class)
public abstract class SneakHeightMixin {

    @ModifyReturnValue(method = "getDimensions(Lnet/minecraft/entity/EntityPose;)Lnet/minecraft/entity/EntityDimensions;", at = @At("RETURN"))
    private EntityDimensions modifyDimensions(EntityDimensions original, EntityPose pose) {
        // Only apply to players
        //noinspection ConstantValue
        if (!((Object) this instanceof PlayerEntity)) return original;
        if (!getCfg().render.oldSneakHeight()) return original;

        if (pose == EntityPose.CROUCHING) {
            return original.withEyeHeight(1.54F);
        }

        return original;
    }
}

