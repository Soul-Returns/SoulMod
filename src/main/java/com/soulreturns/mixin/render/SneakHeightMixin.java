package com.soulreturns.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;

/**
 * Restores 1.8 sneaking eye height (1.54 blocks) instead of modern height (~1.27 blocks).
 * Used for compatibility with 1.8.9 servers.
 */
@Mixin(LivingEntity.class)
public abstract class SneakHeightMixin {

    @ModifyReturnValue(method = "getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;", at = @At("RETURN"))
    private EntityDimensions modifyDimensions(EntityDimensions original, Pose pose) {
        // Only apply to players
        //noinspection ConstantValue
        if (!((Object) this instanceof Player)) return original;
        if (!getCfg().render.oldSneakHeight()) return original;

        if (pose == Pose.CROUCHING) {
            return original.withEyeHeight(1.54F);
        }

        return original;
    }
}

