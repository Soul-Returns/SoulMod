package com.soulreturns.testkit.modapi;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Byte format of the Hypixel Mod API (net.hypixel:mod-api 1.0.2, HypixelDev/ModAPI), written without
 * the library so the server side is independent of the client's decoder.
 *
 * <p>Every clientbound payload starts with a success boolean (the Fabric mod's
 * {@code ClientboundHypixelPayload} reads it first); on failure a VarInt error id follows instead of the
 * packet. Versioned packets then carry their VarInt version, then their fields. Strings are VarInt
 * length + UTF-8, optionals a boolean presence flag, UUIDs two big-endian longs: the vanilla
 * FriendlyByteBuf conventions the library's PacketSerializer reimplements.
 */
public final class HypixelWire {
    public static final String HELLO = "hypixel:hello";
    public static final String PING = "hypixel:ping";
    public static final String PARTY_INFO = "hypixel:party_info";
    public static final String PLAYER_INFO = "hypixel:player_info";
    public static final String REGISTER = "hypixel:register";
    public static final String LOCATION = "hyevent:location";

    /** Versions mod-api 1.0.2 expects; a clientbound packet with another version is dropped by the client. */
    public static final int LOCATION_VERSION = 1;
    public static final int PARTY_INFO_VERSION = 2;
    public static final int PLAYER_INFO_VERSION = 1;
    public static final int PING_VERSION = 1;
    public static final int REGISTER_VERSION = 1;

    /** {@code net.hypixel.data.region.Environment} ids. */
    public static final int ENV_PRODUCTION = 0;

    /** {@code BuiltinErrorReason} ids. */
    public static final int ERROR_INVALID_PACKET_VERSION = 4;

    /** {@code ClientboundPartyInfoPacket.PartyRole} ordinals. */
    public enum PartyRole { LEADER, MOD, MEMBER }

    public record PartyMember(UUID uuid, PartyRole role) {}

    private HypixelWire() {}

    public static byte[] hello(int environment) {
        return new Writer().bool(true).varInt(environment).bytes();
    }

    public static byte[] location(String serverName, String serverType, String lobbyName, String mode, String map) {
        return new Writer().bool(true).varInt(LOCATION_VERSION)
            .string(serverName).optString(serverType).optString(lobbyName).optString(mode).optString(map)
            .bytes();
    }

    /** Version 1 sends leader + member UUIDs, version 2 a member list with roles (ModAPI write()). */
    public static byte[] partyInfo(int version, boolean inParty, List<PartyMember> members) {
        Writer w = new Writer().bool(true).varInt(version);
        if (version == 1) {
            UUID leader = members.stream().filter(m -> m.role == PartyRole.LEADER).map(PartyMember::uuid).findFirst().orElse(null);
            if (!inParty || leader == null) return w.bool(false).bytes();
            w.bool(true).uuid(leader).varInt(members.size());
            members.forEach(m -> w.uuid(m.uuid));
            return w.bytes();
        }
        w.bool(inParty);
        if (!inParty) return w.bytes();
        w.varInt(members.size());
        members.forEach(m -> w.uuid(m.uuid).varInt(m.role.ordinal()));
        return w.bytes();
    }

    /** Rank ids as in net.hypixel.data.rank (PlayerRank NORMAL=1, PackageRank NONE=1, MonthlyPackageRank NONE=1). */
    public static byte[] playerInfo(int playerRank, int packageRank, int monthlyPackageRank, String prefix) {
        return new Writer().bool(true).varInt(PLAYER_INFO_VERSION)
            .varInt(playerRank).varInt(packageRank).varInt(monthlyPackageRank).optString(prefix)
            .bytes();
    }

    public static byte[] ping(String response) {
        return new Writer().bool(true).varInt(PING_VERSION).string(response).bytes();
    }

    public static byte[] error(int reason) {
        return new Writer().bool(false).varInt(reason).bytes();
    }

    /** Serverbound requests (party_info, player_info, ping) carry only their VarInt version. */
    public static int requestVersion(byte[] payload) {
        return new Reader(payload).varInt();
    }

    /** ServerboundRegisterPacket: version, then up to 5 (event identifier, event version) pairs. */
    public static Register register(byte[] payload) {
        Reader r = new Reader(payload);
        int version = r.varInt();
        int size = r.varInt();
        if (size < 0 || size > 5) throw new IllegalArgumentException("register: " + size + " identifiers (max 5)");
        Map<String, Integer> events = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) events.put(r.string(), r.varInt());
        return new Register(version, events);
    }

    public record Register(int version, Map<String, Integer> events) {}

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer bool(boolean value) {
            out.write(value ? 1 : 0);
            return this;
        }

        public Writer varInt(int value) {
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
            return this;
        }

        public Writer string(String value) {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            if (utf8.length > 32767 * 3) throw new IllegalArgumentException("string too long");
            varInt(utf8.length);
            out.writeBytes(utf8);
            return this;
        }

        public Writer optString(String value) {
            bool(value != null);
            return value == null ? this : string(value);
        }

        public Writer uuid(UUID value) {
            return longBE(value.getMostSignificantBits()).longBE(value.getLeastSignificantBits());
        }

        private Writer longBE(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (value >>> shift) & 0xFF);
            return this;
        }

        public byte[] bytes() {
            return out.toByteArray();
        }
    }

    public static final class Reader {
        private final byte[] data;
        private int pos;

        public Reader(byte[] data) {
            this.data = data;
        }

        public int varInt() {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (pos >= data.length) throw new IllegalArgumentException("truncated VarInt");
                if (shift >= 35) throw new IllegalArgumentException("VarInt too big");
                b = data[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return value;
        }

        public String string() {
            int length = varInt();
            if (length < 0 || pos + length > data.length) throw new IllegalArgumentException("bad string length " + length);
            String s = new String(data, pos, length, StandardCharsets.UTF_8);
            pos += length;
            return s;
        }
    }
}
