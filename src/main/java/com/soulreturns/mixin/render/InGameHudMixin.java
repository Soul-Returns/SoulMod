package com.soulreturns.mixin.render;

import com.soulreturns.Soul;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(
            method = "renderHeldItemTooltip",
            at = @At("HEAD"),
            cancellable = true
    )
    public void renderHeldItemTooltip(DrawContext context, CallbackInfo ci) {
        if (Soul.configManager.getConfig().getInstance().RenderCategory.hideHeldItemTooltip) {
            ci.cancel();
        }
    }
}
