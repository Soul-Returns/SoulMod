package com.soulreturns.mixin;

import com.soulreturns.sound.SoundReplacer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every sound the client plays passes {@code SoundEngine.play} (queued and delayed ones once due),
 * so the sound replacer and the recently-heard buffer sit here rather than on the sound packet.
 */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void soul$replace(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        SoundEngine.PlayResult result = SoundReplacer.onEnginePlay((SoundEngine) (Object) this, sound);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
