package com.soulreturns.mixin;

import com.soulreturns.features.qol.HideStuckDianaMobs;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes hidden stuck griffin-skin statues ({@link HideStuckDianaMobs}) untargetable, so the
 * crosshair pick passes through the invisible corpse to the live mob behind it. The statue is
 * already dead server-side (its removal packet was lost, not its death), so there is nothing a
 * click on it could legitimately do — this only stops it from eating attacks aimed past it.
 */
@Mixin(Player.class)
public class PlayerMixin {
    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void soul$stuckStatuesNotPickable(CallbackInfoReturnable<Boolean> cir) {
        if (HideStuckDianaMobs.isHidden((Player) (Object) this)) cir.setReturnValue(false);
    }
}
