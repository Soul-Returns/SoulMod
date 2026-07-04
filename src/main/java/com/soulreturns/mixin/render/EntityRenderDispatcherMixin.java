package com.soulreturns.mixin.render;

import com.soulreturns.features.diana.HideStuckDianaMobs;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Culls entities that {@link HideStuckDianaMobs} flags as stuck griffin-skin statues — skipping
 * {@code shouldRender} skips the whole extraction (model, shadow, nametag) for the frame.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void soul$hideStuckDianaMobs(
        E entity,
        Frustum frustum,
        double camX,
        double camY,
        double camZ,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (HideStuckDianaMobs.isHidden(entity)) cir.setReturnValue(false);
    }
}
