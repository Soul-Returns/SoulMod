package com.soulreturns.mixin;

import com.soulreturns.features.dungeons.HideSoulweaverSkulls;
import com.soulreturns.features.qol.HideStuckDianaMobs;
import com.soulreturns.config.ConfigManagerKt;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Culls entities that {@link HideStuckDianaMobs} flags as stuck griffin-skin statues — skipping
 * {@code shouldRender} skips the whole extraction (model, shadow, nametag) for the frame. The same
 * hook drops the Soulweaver Gloves' Haunted Skulls ({@link HideSoulweaverSkulls}).
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void soul$hideEntities(
        E entity,
        Frustum frustum,
        double camX,
        double camY,
        double camZ,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (HideStuckDianaMobs.isHidden(entity) || HideSoulweaverSkulls.isHidden(entity)) cir.setReturnValue(false);
    }

    /** Drops only the flame draw ({@code submitFlame}); the state still says the entity burns. */
    @Redirect(
        method = "submit",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;displayFireAnimation:Z",
            opcode = Opcodes.GETFIELD
        )
    )
    private boolean soul$hideEntityFire(EntityRenderState state) {
        return state.displayFireAnimation && !ConfigManagerKt.getCfg().getGeneral().getHideEntityFire();
    }
}
