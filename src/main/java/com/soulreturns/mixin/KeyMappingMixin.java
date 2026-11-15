package com.soulreturns.mixin;

import com.soulreturns.features.keybinds.DebugKeyConflicts;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code same} is only the duplicate check of the Controls screen (and of mods that reuse it), never
 * input handling, so a debug key that needs the Debug Modifier can stop counting as a duplicate.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
    @Inject(method = "same", at = @At("HEAD"), cancellable = true)
    private void soul$ignoreDebugKeyConflicts(KeyMapping other, CallbackInfoReturnable<Boolean> cir) {
        if (DebugKeyConflicts.ignored((KeyMapping) (Object) this, other)) cir.setReturnValue(false);
    }
}
