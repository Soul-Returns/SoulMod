package com.soulreturns.mixin;

import com.soulreturns.config.ConfigManagerKt;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips only the first-person fire overlay; water, in-block, pumpkin and portal overlays are separate calls. */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {
    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void soul$hideFireOverlay(
        PoseStack poseStack,
        SubmitNodeCollector collector,
        TextureAtlasSprite sprite,
        CallbackInfo ci
    ) {
        if (ConfigManagerKt.getCfg().getGeneral().getHideFireOverlay()) ci.cancel();
    }
}
