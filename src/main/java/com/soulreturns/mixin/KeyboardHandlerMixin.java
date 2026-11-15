package com.soulreturns.mixin;

import com.soulreturns.features.keybinds.SoulKeyConsumer;
import com.soulreturns.features.search.SearchEverywhere;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swallows key presses that complete a Soul keybind, so vanilla does not act on them too. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void soul$consumeSoulKeybind(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS && SearchEverywhere.INSTANCE.handleKey(event)) {
            ci.cancel();
            return;
        }
        if (SoulKeyConsumer.consumes(action, event)) ci.cancel();
    }
}
