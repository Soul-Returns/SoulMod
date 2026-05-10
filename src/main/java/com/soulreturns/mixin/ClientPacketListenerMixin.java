package com.soulreturns.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;

/*
 * This is code from https://modrinth.com/mod/no-double-sneak (under the MIT License)
 * I have included it here to avoid adding another dependency just for this small fix
 * It has been slightly modified to fix warnings and to fit into this mod's config system
 * credits go to their original authors
 * */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleSetEntityData", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/syncher/SynchedEntityData;assignValues(Ljava/util/List;)V"))
    private void no_double_sneak$fixBug(ClientboundSetEntityDataPacket packet, CallbackInfo ci, @Local Entity entity) {
        if (!getCfg().general.fixes.fixDoubleSneak()) return;
        if (!entity.equals(Minecraft.getInstance().player)) return;
        packet.packedItems().removeIf(entry -> entry.serializer().equals(EntityDataSerializers.POSE));
    }
}