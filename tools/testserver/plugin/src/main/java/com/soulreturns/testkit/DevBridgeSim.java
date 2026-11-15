package com.soulreturns.testkit;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * What the dev bridge reads that a vanilla/Paper server does not produce: Hypixel's per-tick
 * {@code ClientboundPingPacket} (the server tick clock mods count) and other player entities
 * (fake players: version-2 uuids like Hypixel's NPCs, or version-4 like real accounts), which
 * can walk a circle to exercise position frames and tracking.
 */
final class DevBridgeSim {
    private final SoulTestKit plugin;
    private final Map<UUID, BukkitTask> ticks = new HashMap<>();
    private final Map<String, Npc> npcs = new HashMap<>();
    private int nextId = 1_900_000;
    private int pingId = 0;

    private static final class Npc {
        final int id;
        final UUID uuid;
        final String name;
        final Player viewer;
        final double cx, cy, cz;
        double radius;
        BukkitTask walk;

        Npc(int id, UUID uuid, String name, Player viewer, double cx, double cy, double cz) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
            this.viewer = viewer;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
        }
    }

    DevBridgeSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    private static void send(Player player, Packet<?> packet) {
        var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) connection.send(packet);
    }

    /** One ping per server tick, ids counting down like Hypixel's transactions. */
    boolean ticks(Player player, boolean on) {
        BukkitTask old = ticks.remove(player.getUniqueId());
        if (old != null) old.cancel();
        if (on) ticks.put(player.getUniqueId(), plugin.getServer().getScheduler().runTaskTimer(plugin, () -> send(player, new ClientboundPingPacket(--pingId)), 1L, 1L));
        return on || old != null;
    }

    static UUID uuid(boolean real) {
        var r = ThreadLocalRandom.current();
        long msb = (r.nextLong() & ~0xF000L) | ((real ? 4L : 2L) << 12);
        long lsb = (r.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    String add(Player viewer, String name, boolean real, double dx, double dz) {
        remove(name);
        var loc = viewer.getLocation();
        Npc npc = new Npc(nextId++, uuid(real), name, viewer, loc.getX() + dx, loc.getY(), loc.getZ() + dz);
        var entry = new ClientboundPlayerInfoUpdatePacket.Entry(npc.uuid, new GameProfile(npc.uuid, name), false, 0, GameType.SURVIVAL, null, false, 0, null);
        send(viewer, new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), List.of(entry)));
        send(viewer, new ClientboundAddEntityPacket(npc.id, npc.uuid, npc.cx, npc.cy, npc.cz, 0f, 0f, EntityTypes.PLAYER, 0, Vec3.ZERO, 0.0));
        npcs.put(name.toLowerCase(), npc);
        return name + " (" + (real ? "v4 uuid" : "v2 NPC uuid") + ", entity " + npc.id + ") at " + Math.round(npc.cx) + " " + Math.round(npc.cy) + " " + Math.round(npc.cz);
    }

    /** Walks a circle of [radius] around the spawn point, 20 position updates a second. */
    boolean walk(String name, double radius) {
        Npc npc = npcs.get(name.toLowerCase());
        if (npc == null) return false;
        if (npc.walk != null) npc.walk.cancel();
        npc.radius = radius;
        if (radius <= 0) return true;
        long[] t = {0};
        npc.walk = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            double a = (t[0]++) * 0.05;
            double x = npc.cx + Math.cos(a) * npc.radius, z = npc.cz + Math.sin(a) * npc.radius;
            float yaw = (float) Math.toDegrees(Math.atan2(-Math.cos(a), -Math.sin(a))) ;
            send(npc.viewer, new ClientboundEntityPositionSyncPacket(npc.id, new PositionMoveRotation(new Vec3(x, npc.cy, z), Vec3.ZERO, yaw, 0f), true));
        }, 1L, 1L);
        return true;
    }

    boolean remove(String name) {
        Npc npc = npcs.remove(name.toLowerCase());
        if (npc == null) return false;
        if (npc.walk != null) npc.walk.cancel();
        send(npc.viewer, new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(npc.id));
        send(npc.viewer, new ClientboundPlayerInfoRemovePacket(List.of(npc.uuid)));
        return true;
    }

    int removeAll() {
        int n = 0;
        for (String name : new ArrayList<>(npcs.keySet())) if (remove(name)) n++;
        return n;
    }

    List<String> list() {
        List<String> out = new ArrayList<>();
        npcs.values().forEach(n -> out.add(n.name + " id " + n.id + " uuid " + n.uuid + (n.radius > 0 ? " walking r=" + n.radius : "")));
        return out;
    }
}
