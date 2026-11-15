package com.soulreturns.testkit.modapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.hypixel.data.rank.MonthlyPackageRank;
import net.hypixel.data.rank.PackageRank;
import net.hypixel.data.rank.PlayerRank;
import net.hypixel.data.region.Environment;
import net.hypixel.data.type.GameType;
import net.hypixel.data.type.LobbyType;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.error.BuiltinErrorReason;
import net.hypixel.modapi.error.ErrorReason;
import net.hypixel.modapi.packet.ClientboundHypixelPacket;
import net.hypixel.modapi.packet.impl.clientbound.ClientboundHelloPacket;
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPartyInfoPacket;
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPingPacket;
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPlayerInfoPacket;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket;
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPlayerInfoPacket;
import net.hypixel.modapi.packet.impl.serverbound.ServerboundRegisterPacket;
import net.hypixel.modapi.serializer.PacketSerializer;
import org.junit.jupiter.api.Test;

/**
 * Encodes with the plugin's {@link HypixelWire}, decodes with Hypixel's own mod-api 1.0.2 exactly as
 * the Fabric mod's ClientboundHypixelPayload does (success boolean, then the registry's factory), and
 * the reverse for serverbound packets the client writes.
 */
class HypixelWireTest {
    private static final HypixelModAPI API = HypixelModAPI.getInstance();

    private static ClientboundHypixelPacket decode(String identifier, byte[] bytes) {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        PacketSerializer serializer = new PacketSerializer(buf);
        assertTrue(serializer.readBoolean(), "success flag");
        ClientboundHypixelPacket packet = API.getRegistry().createClientboundPacket(identifier, serializer);
        assertEquals(0, buf.readableBytes(), "trailing bytes");
        return packet;
    }

    private static byte[] encode(net.hypixel.modapi.packet.HypixelPacket packet) {
        ByteBuf buf = Unpooled.buffer();
        packet.write(new PacketSerializer(buf));
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    @Test
    void locationRoundTrip() {
        byte[] bytes = HypixelWire.location("mini94M", "SKYBLOCK", null, "dungeon", "Dungeon");
        var packet = assertInstanceOf(ClientboundLocationPacket.class, decode(HypixelWire.LOCATION, bytes));
        assertTrue(packet.isExpectedVersion());
        assertEquals("mini94M", packet.getServerName());
        assertEquals(Optional.of(GameType.SKYBLOCK), packet.getServerType());
        assertEquals(Optional.empty(), packet.getLobbyName());
        assertEquals(Optional.of("dungeon"), packet.getMode());
        assertEquals(Optional.of("Dungeon"), packet.getMap());
        // Byte-identical to what the library itself writes for the same packet.
        byte[] reference = encode(new ClientboundLocationPacket("mini94M", GameType.SKYBLOCK, null, "dungeon", "Dungeon"));
        byte[] withoutFlag = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        assertEquals(java.util.Arrays.toString(reference), java.util.Arrays.toString(withoutFlag));
    }

    @Test
    void locationLobbyAndUnicode() {
        var lobby = (ClientboundLocationPacket) decode(HypixelWire.LOCATION,
            HypixelWire.location("dynamiclobby35E", "MAIN", "mainlobby1", null, null));
        assertEquals(Optional.of(LobbyType.MAIN), lobby.getServerType());
        assertEquals(Optional.of("mainlobby1"), lobby.getLobbyName());
        assertEquals(Optional.empty(), lobby.getMode());
        var nest = (ClientboundLocationPacket) decode(HypixelWire.LOCATION,
            HypixelWire.location("mini45C", "SKYBLOCK", null, "combat_3", "Dragon's Nest ⏣ é"));
        assertEquals(Optional.of("Dragon's Nest ⏣ é"), nest.getMap());
    }

    @Test
    void helloDecodes() {
        var hello = assertInstanceOf(ClientboundHelloPacket.class,
            decode(HypixelWire.HELLO, HypixelWire.hello(HypixelWire.ENV_PRODUCTION)));
        assertEquals(Environment.PRODUCTION, hello.getEnvironment());
    }

    @Test
    void partyInfoBothVersions() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var members = List.of(new HypixelWire.PartyMember(me, HypixelWire.PartyRole.MEMBER),
            new HypixelWire.PartyMember(other, HypixelWire.PartyRole.LEADER));
        var v2 = (ClientboundPartyInfoPacket) decode(HypixelWire.PARTY_INFO, HypixelWire.partyInfo(2, true, members));
        assertTrue(v2.isExpectedVersion());
        assertTrue(v2.isInParty());
        assertEquals(Optional.of(other), v2.getLeader());
        assertEquals(Set.of(me, other), v2.getMembers());
        assertEquals(ClientboundPartyInfoPacket.PartyRole.MEMBER, v2.getMemberMap().get(me).getRole());

        var none = (ClientboundPartyInfoPacket) decode(HypixelWire.PARTY_INFO, HypixelWire.partyInfo(2, false, List.of()));
        assertFalse(none.isInParty());

        // Version 1 is the old leader + uuid list layout; mod-api 1.0.2 reads it but flags the version.
        byte[] v1Bytes = HypixelWire.partyInfo(1, true, members);
        var v1 = new ClientboundPartyInfoPacket(new PacketSerializer(Unpooled.wrappedBuffer(v1Bytes, 1, v1Bytes.length - 1)));
        assertFalse(v1.isExpectedVersion());
        byte[] reference = encode(new ClientboundPartyInfoPacket(1, true, Map.of(
            other, new ClientboundPartyInfoPacket.PartyMember(other, ClientboundPartyInfoPacket.PartyRole.LEADER))));
        byte[] ours = HypixelWire.partyInfo(1, true, List.of(new HypixelWire.PartyMember(other, HypixelWire.PartyRole.LEADER)));
        assertEquals(java.util.Arrays.toString(reference), java.util.Arrays.toString(java.util.Arrays.copyOfRange(ours, 1, ours.length)));
    }

    @Test
    void playerInfoAndPing() {
        var info = (ClientboundPlayerInfoPacket) decode(HypixelWire.PLAYER_INFO,
            HypixelWire.playerInfo(PlayerRank.NORMAL.getId(), PackageRank.MVP_PLUS.getId(), MonthlyPackageRank.SUPERSTAR.getId(), null));
        assertEquals(PlayerRank.NORMAL, info.getPlayerRank());
        assertEquals(PackageRank.MVP_PLUS, info.getPackageRank());
        assertEquals(MonthlyPackageRank.SUPERSTAR, info.getMonthlyPackageRank());
        assertEquals(Optional.empty(), info.getPrefix());
        for (Rank rank : Rank.values()) {
            var r = (ClientboundPlayerInfoPacket) decode(HypixelWire.PLAYER_INFO,
                HypixelWire.playerInfo(rank.playerRank, rank.packageRank, rank.monthlyRank, rank.prefix));
            assertEquals(Optional.ofNullable(rank.prefix), r.getPrefix(), rank.key());
        }
        var ping = (ClientboundPingPacket) decode(HypixelWire.PING, HypixelWire.ping("pong"));
        assertEquals("pong", ping.getResponse());
    }

    @Test
    void errorResponseMatchesLibrary() {
        PacketSerializer s = new PacketSerializer(Unpooled.wrappedBuffer(HypixelWire.error(HypixelWire.ERROR_INVALID_PACKET_VERSION)));
        assertFalse(s.readBoolean());
        assertEquals(BuiltinErrorReason.INVALID_PACKET_VERSION, ErrorReason.getById(s.readVarInt()));
    }

    @Test
    void serverboundFromLibrary() {
        byte[] register = encode(new ServerboundRegisterPacket(API.getRegistry(), Set.of(HypixelWire.LOCATION)));
        HypixelWire.Register parsed = HypixelWire.register(register);
        assertEquals(HypixelWire.REGISTER_VERSION, parsed.version());
        assertEquals(Map.of(HypixelWire.LOCATION, HypixelWire.LOCATION_VERSION), parsed.events());

        assertEquals(2, HypixelWire.requestVersion(encode(new ServerboundPartyInfoPacket())));
        assertEquals(1, HypixelWire.requestVersion(encode(new ServerboundPlayerInfoPacket())));
    }
}
