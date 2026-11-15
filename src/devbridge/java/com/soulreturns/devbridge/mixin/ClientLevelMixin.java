package com.soulreturns.devbridge.mixin;

import com.soulreturns.devbridge.Gate;
import com.soulreturns.devbridge.Tracking;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** When other player entities start and stop being tracked (client thread). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "addEntity", at = @At("RETURN"))
    private void soulBridge$added(Entity entity, CallbackInfo ci) {
        if (Gate.on && entity instanceof Player player && !(entity instanceof LocalPlayer)) Gate.run(() -> Tracking.added(player));
    }

    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void soulBridge$removed(int id, Entity.RemovalReason reason, CallbackInfo ci) {
        if (!Gate.on) return;
        Entity entity = ((ClientLevel) (Object) this).getEntity(id);
        if (entity instanceof Player player && !(entity instanceof LocalPlayer)) Gate.run(() -> Tracking.removed(player));
    }
}
