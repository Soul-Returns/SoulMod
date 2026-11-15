package com.soulreturns.mixin;

import com.soulreturns.features.dev.PacketTap;
import com.soulreturns.features.dungeons.boss.EntityAdds;
import com.soulreturns.features.dungeons.leap.LeapTiming;
import com.soulreturns.platform.ServerPings;
import com.soulreturns.util.ServerTraffic;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows every received and sent packet to the {@link PacketTap} sinks (packet log, dev bridge) and stamps when the
 * server last sent anything ({@link ServerTraffic}); {@link ServerPings} counts the server's pings here, where a
 * bundle is still one packet, and {@link EntityAdds} gets the entity adds inside bundles, which vanilla unpacks on the
 * client thread only. Observe only; never cancels or changes a packet.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void soul$logInbound(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        ServerTraffic.lastInboundMs = System.currentTimeMillis();
        if (packet instanceof ClientboundOpenScreenPacket) LeapTiming.onOpenPacket();
        Connection self = (Connection) (Object) this;
        if (self.getReceiving() == PacketFlow.CLIENTBOUND) {
            ServerPings.onPacket(packet);
            if (packet instanceof BundlePacket<?> bundle) EntityAdds.onBundle(bundle);
        }
        if (PacketTap.getActive()) PacketTap.inbound(self, packet);
    }

    @Inject(method = "doSendPacket", at = @At("HEAD"))
    private void soul$logOutbound(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (PacketTap.getActive()) PacketTap.outbound((Connection) (Object) this, packet);
    }
}
