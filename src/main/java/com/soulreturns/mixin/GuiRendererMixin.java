package com.soulreturns.mixin;

import com.soulreturns.ui.CrispItems;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scaled GUI items are rasterized at their shown pixel size instead of resampling vanilla's atlas ({@link CrispItems}). */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {
    @Shadow @Final private GuiRenderState renderState;
    @Shadow @Final private FeatureRenderDispatcher featureRenderDispatcher;

    @Inject(method = "prepareItemElements", at = @At("HEAD"))
    private void soul$crispBegin(CallbackInfo ci) {
        CrispItems.beginFrame(renderState, featureRenderDispatcher);
    }

    @Inject(method = "submitBlitFromItemAtlas", at = @At("HEAD"), cancellable = true)
    private void soul$crispBlit(GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView, CallbackInfo ci) {
        if (CrispItems.submit(renderState, itemState)) ci.cancel();
    }

    @Inject(method = "endFrame", at = @At("TAIL"))
    private void soul$crispEnd(CallbackInfo ci) {
        CrispItems.endFrame();
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void soul$crispClose(CallbackInfo ci) {
        CrispItems.close();
    }
}
