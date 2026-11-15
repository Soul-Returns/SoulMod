package com.soulreturns.devbridge.mixin;

import com.soulreturns.devbridge.Bridge;
import com.soulreturns.devbridge.Gate;
import com.soulreturns.devbridge.MenuEvents;
import com.soulreturns.devbridge.world.WorldCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes packets after vanilla applied them. The handlers first bounce from netty to the client
 * thread by throwing, so RETURN is only reached on the client thread; HEAD runs on both. Menu
 * opens, titles and boss bars run whenever the bridge is on (they feed the backlog), the rest
 * only while something is attached.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleOpenScreen", at = @At("HEAD"))
    private void soulBridge$beforeOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (Gate.listening && Minecraft.getInstance().isSameThread()) Gate.run(MenuEvents::beforeOpenScreen);
    }

    @Inject(method = "handleOpenScreen", at = @At("RETURN"))
    private void soulBridge$afterOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (Gate.on) Gate.run(() -> MenuEvents.afterOpenScreen(packet));
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void soulBridge$afterContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        if (Gate.listening) Gate.run(() -> MenuEvents.afterContents(packet));
    }

    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void soulBridge$afterSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (Gate.listening) Gate.run(() -> MenuEvents.afterSetSlot(packet));
    }

    @Inject(method = "setTitleText", at = @At("RETURN"))
    private void soulBridge$title(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        if (Gate.on) Gate.run(() -> Bridge.INSTANCE.onTitle("title", packet.text()));
    }

    @Inject(method = "setSubtitleText", at = @At("RETURN"))
    private void soulBridge$subtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        if (Gate.on) Gate.run(() -> Bridge.INSTANCE.onTitle("subtitle", packet.text()));
    }

    @Inject(method = "handleTitlesClear", at = @At("RETURN"))
    private void soulBridge$titleClear(ClientboundClearTitlesPacket packet, CallbackInfo ci) {
        if (Gate.on) Gate.run(() -> Bridge.INSTANCE.onTitle("clear", null));
    }

    @Inject(method = "setTitlesAnimation", at = @At("RETURN"))
    private void soulBridge$titleTimes(ClientboundSetTitlesAnimationPacket packet, CallbackInfo ci) {
        if (Gate.on) Gate.run(() -> Bridge.INSTANCE.onTitleTimes(packet.getFadeIn(), packet.getStay(), packet.getFadeOut()));
    }

    @Inject(method = "handleBossUpdate", at = @At("RETURN"))
    private void soulBridge$boss(ClientboundBossEventPacket packet, CallbackInfo ci) {
        if (Gate.on) Gate.run(() -> Bridge.INSTANCE.onBossUpdate(packet));
    }

    // World capture (WorldCapture): after vanilla applied the packet, only while a capture runs.
    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void soulBridge$chunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (WorldCapture.active) Gate.run(() -> WorldCapture.INSTANCE.onChunk(packet.getX(), packet.getZ()));
    }

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
    private void soulBridge$blockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (WorldCapture.active) Gate.run(() -> WorldCapture.INSTANCE.onBlockUpdate(packet));
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
    private void soulBridge$sectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (WorldCapture.active) Gate.run(() -> WorldCapture.INSTANCE.onSectionUpdate(packet));
    }

    @Inject(method = "handleBlockEntityData", at = @At("RETURN"))
    private void soulBridge$blockEntity(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        if (WorldCapture.active) Gate.run(() -> WorldCapture.INSTANCE.onBlockEntityData(packet));
    }

    @Inject(method = "handleSetEntityData", at = @At("RETURN"))
    private void soulBridge$entityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        if (WorldCapture.active) Gate.run(() -> WorldCapture.INSTANCE.onEntity(packet.id()));
    }

    @Inject(method = "handleSetEquipment", at = @At("RETURN"))
    private void soulBridge$equipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        if (WorldCapture.active) Gate.run(() -> WorldCapture.INSTANCE.onEntity(packet.getEntity()));
    }

    @Inject(method = "handleTeleportEntity", at = @At("RETURN"))
    private void soulBridge$teleport(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        if (WorldCapture.active) Gate.run(() -> WorldCapture.INSTANCE.onEntity(packet.id()));
    }

    @Inject(method = "handleEntityPositionSync", at = @At("RETURN"))
    private void soulBridge$positionSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        if (WorldCapture.active) Gate.run(() -> WorldCapture.INSTANCE.onEntity(packet.id()));
    }
}
