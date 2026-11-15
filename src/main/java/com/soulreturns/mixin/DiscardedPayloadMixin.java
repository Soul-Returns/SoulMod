package com.soulreturns.mixin;

import com.soulreturns.features.dev.PacketJson;
import com.soulreturns.features.dev.PacketTap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Packet debug mode: vanilla skips the bytes of a custom payload on a channel nobody registered.
 * While a packet tap sink is registered, copy them first (without moving the reader) so the dump can show them.
 */
@Mixin(DiscardedPayload.class)
public abstract class DiscardedPayloadMixin {
    @Unique
    private static final ThreadLocal<byte[]> soul$pending = new ThreadLocal<>();

    @Inject(method = "lambda$codec$1", at = @At("HEAD"))
    private static void soul$copyBytes(int maxSize, Identifier id, FriendlyByteBuf buf, CallbackInfoReturnable<DiscardedPayload> cir) {
        if (!PacketTap.getActive()) return;
        int length = Math.max(0, Math.min(buf.readableBytes(), maxSize));
        byte[] bytes = new byte[length];
        buf.getBytes(buf.readerIndex(), bytes);
        soul$pending.set(bytes);
    }

    @Inject(method = "lambda$codec$1", at = @At("RETURN"))
    private static void soul$rememberBytes(int maxSize, Identifier id, FriendlyByteBuf buf, CallbackInfoReturnable<DiscardedPayload> cir) {
        byte[] bytes = soul$pending.get();
        if (bytes == null) return;
        soul$pending.remove();
        PacketJson.rememberDiscarded(cir.getReturnValue(), bytes);
    }
}
