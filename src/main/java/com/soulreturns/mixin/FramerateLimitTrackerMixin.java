package com.soulreturns.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.soulreturns.features.updater.InGameUpdatePrompt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    @Inject(method = "onInputReceived", at = @At("HEAD"))
    private void soul$updateActivity(CallbackInfo ci) {
        InGameUpdatePrompt.onInput();
    }
}
