package com.soulreturns.mixin.render;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;

/**
 * Suppresses the scene-wide lightmap flash that lightning strikes apply (via
 * {@code ClientLevel.skyFlashTime}). The {@link net.minecraft.world.entity.LightningBolt}
 * entity, thunder sound, and damage are untouched — only the white blast is hidden.
 */
@Mixin(ClientLevel.class)
public class LightningFlashMixin {

    @Inject(method = "setSkyFlashTime(I)V", at = @At("HEAD"), cancellable = true)
    private void soul$suppressSkyFlash(int time, CallbackInfo ci) {
        if (getCfg().render.hideLightningFlash()) {
            ci.cancel();
        }
    }
}
