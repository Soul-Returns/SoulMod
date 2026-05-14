package com.soulreturns.mixin.render;

import com.mojang.blaze3d.opengl.GlDebug;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Filter known-benign OpenGL debug-callback messages produced when NanoVG (a raw-GL library)
 * runs inside Minecraft 1.21.11's RenderDevice command-encoder framework.
 *
 * Specifically the {@code "No active program"} {@code GL_INVALID_OPERATION} that fires after
 * NanoVG's {@code nvgEndFrame} calls {@code glUseProgram(0)} — when Mojang's PIP composite
 * step validates pipeline state it finds no program bound. Visuals are unaffected (Mojang
 * re-binds its own program before any actual draw) but the log otherwise spams hundreds of
 * these per second once a NanoVG panel is on screen.
 *
 * Patterns are matched against the raw message string read from the C pointer. Add to
 * {@link #SUPPRESSED_PATTERNS} if more known-benign messages appear. Flag-them-then-
 * investigate is the rule — don't blanket-suppress real GL errors.
 *
 * Target: {@code com.mojang.blaze3d.opengl.GlDebug#printDebugLog(IIIIIJJ)V} — invoked from
 * the {@link org.lwjgl.opengl.KHRDebug} debug-message callback Mojang installs at startup.
 */
@Mixin(GlDebug.class)
public class GlDebugMixin {
    private static final String[] SUPPRESSED_PATTERNS = {
        "No active program",
    };

    @Inject(method = "printDebugLog(IIIIIJJ)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void soul$filterKnownBenign(
        int source,
        int type,
        int id,
        int severity,
        int length,
        long messagePtr,
        long userParam,
        CallbackInfo ci
    ) {
        if (messagePtr == 0L || length <= 0) return;
        final String text;
        try {
            text = MemoryUtil.memUTF8(MemoryUtil.memByteBuffer(messagePtr, length));
        } catch (Throwable t) {
            return; // Reading failed — let the default handler run rather than swallow silently.
        }
        for (String pattern : SUPPRESSED_PATTERNS) {
            if (text.contains(pattern)) {
                ci.cancel();
                return;
            }
        }
    }
}
