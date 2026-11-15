package com.soulreturns.mixin;

import com.soulreturns.features.storage.StorageOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "de.hysky.skyblocker.skyblock.storageoverlay.StorageOverlayScreen", remap = false)
public abstract class SkyblockerStorageMixin {
    @Inject(method = "enabled(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void soul$preferStorage(String title, CallbackInfoReturnable<Boolean> cir) {
        if (StorageOverlay.shouldOverrideSkyblocker(title)) cir.setReturnValue(false);
    }
}
