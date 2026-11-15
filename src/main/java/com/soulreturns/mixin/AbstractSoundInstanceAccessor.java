package com.soulreturns.mixin;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The requested volume and pitch as the instance was built (the sound packet's values). The public
 * getters multiply in the resolved variant's own values and throw before {@code resolve}, which the
 * replacer's hook runs ahead of.
 */
@Mixin(AbstractSoundInstance.class)
public interface AbstractSoundInstanceAccessor {
    @Accessor("volume")
    float soul$getVolume();

    @Accessor("pitch")
    float soul$getPitch();
}
