package com.soulreturns.mixin;

import com.soulreturns.features.dungeons.boss.DragonCountdown;
import com.soulreturns.features.dungeons.boss.EntityAdds;
import com.soulreturns.features.dungeons.boss.F7Boss;
import com.soulreturns.features.dungeons.devices.GoldorPhase;
import com.soulreturns.features.dungeons.devices.SimonSays;
import com.soulreturns.features.dungeons.terminals.TerminalSolver;
import com.soulreturns.features.qol.ImplosionHider;
import com.soulreturns.features.mining.HollowsWeather;
import com.soulreturns.features.mining.metaldetector.MetalDetector;
import com.soulreturns.features.islandchests.IslandChests;
import com.soulreturns.features.storage.StorageOverlay;
import com.soulreturns.platform.ServerTime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link ImplosionHider} drop or hold particle and sound packets. HEAD runs first on the network
 * thread (vanilla re-schedules onto the render thread right after), so a drop happens before that.
 * {@link MetalDetector} only observes sounds, on the render-thread pass. {@link HollowsWeather} observes
 * sounds (the weather's creaking marks it active) and drops the weather's particle packets.
 * {@link ServerTime} anchors at each {@code set_time}'s arrival (the netty pass) and {@link F7Boss} stamps
 * sounds, titles, chat lines and the Storm boss bar with the server tick of their arrival there; {@link GoldorPhase}
 * stamps boss lines and Storm's death strike the same way for Goldor's phase start; {@link EntityAdds} passes lone
 * entity adds on (bundled ones, every spawn on Hypixel, come from {@code ConnectionMixin}); all only observe. {@link SimonSays}
 * observes the device's pling and error sounds on the render-thread pass, {@link TerminalSolver} the melody
 * terminal's.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleParticleEvent", at = @At("HEAD"), cancellable = true)
    private void soul$hideImplosion(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        DragonCountdown.onParticle(packet);
        if (ImplosionHider.onParticle(packet) || HollowsWeather.onParticle(packet)) ci.cancel();
    }

    @Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
    private void soul$holdImplosionSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        if (ImplosionHider.onSound(packet)) ci.cancel();
    }

    @Inject(method = "handleSoundEvent", at = @At("HEAD"))
    private void soul$observeSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        MetalDetector.onSound(packet);
        HollowsWeather.onSound(packet);
        F7Boss.onSound(packet);
        GoldorPhase.onSound(packet);
        SimonSays.onSound(packet);
        TerminalSolver.onSound(packet);
    }

    @Inject(method = "handleSetTime", at = @At("HEAD"))
    private void soul$timeArrived(ClientboundSetTimePacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) ServerTime.onSetTime(packet, packet.gameTime(), true);
    }

    @Inject(method = "handleSetTime", at = @At("RETURN"))
    private void soul$time(ClientboundSetTimePacket packet, CallbackInfo ci) {
        ServerTime.onSetTime(packet, packet.gameTime(), false);
    }

    @Inject(method = "setTitleText", at = @At("HEAD"))
    private void soul$title(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        F7Boss.onTitle(packet);
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void soul$chatArrived(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        F7Boss.onSystemChat(packet);
        GoldorPhase.onSystemChat(packet);
    }

    @Inject(method = "handleBossUpdate", at = @At("HEAD"))
    private void soul$bossBar(ClientboundBossEventPacket packet, CallbackInfo ci) {
        F7Boss.onBossUpdate(packet);
    }

    @Inject(method = "handleAddEntity", at = @At("HEAD"))
    private void soul$entityAdded(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        EntityAdds.onAdd(packet);
    }

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void soul$observeBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        MetalDetector.onBlockUpdate(packet);
    }

    @Inject(method = "handleOpenScreen", at = @At("RETURN"))
    private void soul$islandChestOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        IslandChests.onOpen(packet);
        StorageOverlay.onOpen(packet);
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void soul$islandChestContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        IslandChests.onContents(packet);
        StorageOverlay.onContents(packet);
    }

    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void soul$islandChestSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        IslandChests.onSlot(packet);
        StorageOverlay.onSlot(packet);
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"))
    private void soul$storageClosing(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        StorageOverlay.beforeClose(packet);
    }

    @Inject(method = "handleContainerClose", at = @At("RETURN"))
    private void soul$islandChestClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        IslandChests.onClose(packet);
        StorageOverlay.onClose(packet);
    }
}
