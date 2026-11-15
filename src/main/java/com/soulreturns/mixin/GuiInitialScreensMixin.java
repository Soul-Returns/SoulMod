package com.soulreturns.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.soulreturns.features.updater.UpdateTitlePrompt;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla builds the screens shown once loading ends (accessibility onboarding, ban notices, then
 * quick play or the title screen) as one chain; this puts the updater's window ahead of it.
 */
@Mixin(Gui.class)
public abstract class GuiInitialScreensMixin {
    @ModifyReturnValue(method = "buildInitialScreens", at = @At("RETURN"))
    private Runnable soul$updateBeforeTitle(Runnable vanilla, GameLoadCookie cookie) {
        return UpdateTitlePrompt.beforeTitle(vanilla, cookie);
    }
}
