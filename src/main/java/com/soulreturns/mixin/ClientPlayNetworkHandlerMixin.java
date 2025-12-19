package com.soulreturns.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;

import static com.soulreturns.config.ConfigInstanceKt.getConfig;

/*
 * This is code from https://modrinth.com/mod/no-double-sneak (under the MIT License)
 * I have included it here to avoid adding another dependency just for this small fix
 * It has been slightly modified to fix warnings and to fit into this mod's config system
 * credits go to their original authors
 * */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onEntityTrackerUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/data/DataTracker;writeUpdatedEntries(Ljava/util/List;)V"))
    private void no_double_sneak$fixBug(EntityTrackerUpdateS2CPacket packet, CallbackInfo ci, @Local Entity entity) {
        if (!getConfig().fixesCategory.fixDoubleSneak) return;
        if (!entity.equals(MinecraftClient.getInstance().player)) return;
        packet.trackedValues().removeIf(entry -> entry.handler().equals(TrackedDataHandlerRegistry.ENTITY_POSE));
    }
}