package com.soulreturns.mixin;

import com.soulreturns.features.diana.HideStuckDianaMobs;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses sprint particles for entities {@link HideStuckDianaMobs} flags as stuck griffin-skin
 * statues. The fake player is frozen with its sprinting flag still set, and
 * {@code Entity.baseTick()} spawns the block-crumb sprint particles every tick regardless of
 * movement — so a render-culled statue would still emit crumbs at its feet.
 * {@code canSpawnSprintParticle()} gates that single call site.
 */
@Mixin(Entity.class)
public class EntityMixin {
    @Inject(method = "canSpawnSprintParticle", at = @At("HEAD"), cancellable = true)
    private void soul$hideStuckDianaSprintParticles(CallbackInfoReturnable<Boolean> cir) {
        if (HideStuckDianaMobs.isHidden((Entity) (Object) this)) cir.setReturnValue(false);
    }
}
