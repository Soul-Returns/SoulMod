package com.soulreturns.mixin;

import com.soulreturns.features.qol.HideFoliage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the hover outline (the white selection lines) when the crosshair targets a hidden
 * foliage block — the block stays fully targetable/breakable (vanilla raycast, see
 * {@code BlockStateBaseMixin}); only the visual box is dropped so invisible plants don't reveal
 * themselves as floating outlines. The state must be nulled before cancelling: vanilla clears it
 * at the top of the method, so a bare cancel would leak last frame's outline.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    @Inject(method = "extractBlockOutline", at = @At("HEAD"), cancellable = true)
    private void soul$hideFoliageOutline(Camera camera, LevelRenderState levelRenderState, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult blockHit)) return;
        if (blockHit.getType() != HitResult.Type.BLOCK) return;
        if (HideFoliage.shouldHide(mc.level.getBlockState(blockHit.getBlockPos()).getBlock())) {
            levelRenderState.blockOutlineRenderState = null;
            ci.cancel();
        }
    }
}
