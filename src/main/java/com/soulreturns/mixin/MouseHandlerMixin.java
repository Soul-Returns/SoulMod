package com.soulreturns.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.soulreturns.features.inventory.KeepCursor;
import com.soulreturns.features.search.SearchEverywhere;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets {@link KeepCursor} keep the cursor in place when one container menu replaces another. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    private double xpos;

    @Shadow
    private double ypos;

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void soul$searchEverywhereKey(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS && SearchEverywhere.INSTANCE.handleMouse(button.button())) ci.cancel();
    }

    /** Just before grabMouse overwrites the position with the window center. */
    @Inject(method = "grabMouse", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;xpos:D", opcode = Opcodes.PUTFIELD, ordinal = 0))
    private void soul$rememberCursor(CallbackInfo ci) {
        KeepCursor.onGrab(this.xpos, this.ypos);
    }

    /**
     * The position goes into the release (Wayland can only place the cursor through the pointer-lock
     * hint while it is still locked) and is set again afterwards: on X11, GLFW restores the position it
     * saved when the cursor was disabled, which is the center the grab warped to.
     */
    @WrapOperation(method = "releaseMouse", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/InputConstants;grabOrReleaseMouse(Lcom/mojang/blaze3d/platform/Window;IDD)V"))
    private void soul$keepCursor(Window window, int mode, double x, double y, Operation<Void> original) {
        double[] kept = KeepCursor.releasePosition();
        if (kept == null) {
            original.call(window, mode, x, y);
            return;
        }
        this.xpos = kept[0];
        this.ypos = kept[1];
        original.call(window, mode, kept[0], kept[1]);
        GLFW.glfwSetCursorPos(window.handle(), kept[0], kept[1]);
    }
}
