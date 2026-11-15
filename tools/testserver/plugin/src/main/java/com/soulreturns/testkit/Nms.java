package com.soulreturns.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import io.papermc.paper.adventure.PaperAdventure;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.kyori.adventure.text.Component;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The few things the Bukkit API cannot do; Paper 26.x runs Mojang names, so these are direct calls. */
public final class Nms {
    private Nms() {}

    private static void send(Player player, Packet<?> packet) {
        var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) connection.send(packet);
    }

    /**
     * Custom payload sent the way Hypixel does: unconditionally. Bukkit's sendPluginMessage would drop it
     * unless the client had registered the channel first, and Hypixel's hello arrives before any of that.
     */
    public static void sendPayload(Player player, String channel, byte[] data) {
        send(player, new ClientboundCustomPayloadPacket(new DiscardedPayload(Identifier.parse(channel), data)));
    }

    /** System chat, overlay=true: how Hypixel puts text over the hotbar (not the set_action_bar_text packet). */
    public static void sendSystemChat(Player player, Component text, boolean overlay) {
        send(player, new ClientboundSystemChatPacket(text, overlay));
    }

    /**
     * A whole map as Hypixel sends its dungeon map: map [id], all 128x128 [colors] (palette bytes, row-major) and
     * [markers] as {type, x, y, rot} with type 0 = frame (you), 1 = blue marker (a teammate); x/y in map units -128..127.
     */
    public static void sendMap(Player player, int id, byte[] colors, List<int[]> markers) {
        List<net.minecraft.world.level.saveddata.maps.MapDecoration> decorations = new java.util.ArrayList<>();
        for (int[] m : markers) {
            decorations.add(new net.minecraft.world.level.saveddata.maps.MapDecoration(
                m[0] == 0 ? net.minecraft.world.level.saveddata.maps.MapDecorationTypes.FRAME : net.minecraft.world.level.saveddata.maps.MapDecorationTypes.BLUE_MARKER,
                (byte) m[1], (byte) m[2], (byte) m[3], java.util.Optional.empty()));
        }
        send(player, new net.minecraft.network.protocol.game.ClientboundMapItemDataPacket(
            new net.minecraft.world.level.saveddata.maps.MapId(id), (byte) 0, false, java.util.Optional.of(decorations),
            java.util.Optional.of(new net.minecraft.world.level.saveddata.maps.MapItemSavedData.MapPatch(0, 0, 128, 128, colors))));
    }

    /** Moves an AI-less entity straight to a position; the tracker turns the change into movement packets. */
    public static void setPos(org.bukkit.entity.Entity entity, double x, double y, double z) {
        ((org.bukkit.craftbukkit.entity.CraftEntity) entity).getHandle().setPos(x, y, z);
    }

    /**
     * Makes the tracker send this entity's movement every tick instead of every 3rd (the living-entity default).
     * Reflection into the chunk map's tracked entry; returns the interval it replaced, or -1 when not found.
     */
    public static int trackEveryTick(org.bukkit.entity.Entity entity) {
        try {
            var handle = ((org.bukkit.craftbukkit.entity.CraftEntity) entity).getHandle();
            var chunkMap = ((net.minecraft.server.level.ServerLevel) handle.level()).getChunkSource().chunkMap;
            var entityMap = field(chunkMap.getClass(), "entityMap").get(chunkMap);
            Object tracked = entityMap.getClass().getMethod("get", int.class).invoke(entityMap, handle.getId());
            if (tracked == null) return -1;
            Object serverEntity = field(tracked.getClass(), "serverEntity").get(tracked);
            var interval = field(serverEntity.getClass(), "updateInterval");
            int old = interval.getInt(serverEntity);
            interval.setInt(serverEntity, 1);
            return old;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** One fake tab entry per (uuid, name, displayName); listed, survival, latency 0, sorted by name. */
    public record TabEntry(UUID uuid, String name, Component displayName) {}

    public static void addTabEntries(Player player, List<TabEntry> entries) {
        var actions = EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY);
        var nms = entries.stream().map(e -> new ClientboundPlayerInfoUpdatePacket.Entry(
            e.uuid, new GameProfile(e.uuid, e.name), true, 0, GameType.SURVIVAL,
            PaperAdventure.asVanilla(e.displayName), false, 0, null)).toList();
        send(player, new ClientboundPlayerInfoUpdatePacket(actions, nms));
    }

    public static void removeTabEntries(Player player, List<UUID> ids) {
        if (!ids.isEmpty()) send(player, new ClientboundPlayerInfoRemovePacket(ids));
    }

    /** Hides or shows a real player's own tab row for the viewer (Hypixel lists only its fake rows). */
    public static void setListed(Player viewer, UUID entry, boolean listed) {
        send(viewer, ClientboundPlayerInfoUpdatePacket.updateListed(entry, listed));
    }

    /** Replaces the item's minecraft:custom_data with the given JSON object (see {@link #toNbt}). */
    public static ItemStack withCustomData(ItemStack item, JsonObject data) {
        var nms = CraftItemStack.asNMSCopy(item);
        nms.set(DataComponents.CUSTOM_DATA, CustomData.of((CompoundTag) toNbt(data)));
        return CraftItemStack.asBukkitCopy(nms);
    }

    /** custom_data SNBT of an item, or "" when it has none. */
    public static String customDataSnbt(ItemStack item) {
        CustomData data = CraftItemStack.asNMSCopy(item).get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().toString();
    }

    /**
     * JSON to NBT the way Hypixel's custom_data looks: whole numbers become int (long when they do not
     * fit, e.g. timestamp), other numbers double, booleans byte, arrays lists.
     */
    public static Tag toNbt(JsonElement json) {
        if (json instanceof JsonObject object) {
            CompoundTag tag = new CompoundTag();
            for (Map.Entry<String, JsonElement> e : object.entrySet()) tag.put(e.getKey(), toNbt(e.getValue()));
            return tag;
        }
        if (json instanceof JsonArray array) {
            ListTag list = new ListTag();
            for (JsonElement e : array) list.add(toNbt(e));
            return list;
        }
        if (json instanceof JsonPrimitive p) {
            if (p.isBoolean()) return ByteTag.valueOf(p.getAsBoolean());
            if (p.isNumber()) {
                String raw = p.getAsString();
                if (raw.matches("-?\\d+")) {
                    long value = Long.parseLong(raw);
                    return value == (int) value ? IntTag.valueOf((int) value) : LongTag.valueOf(value);
                }
                return DoubleTag.valueOf(p.getAsDouble());
            }
            return StringTag.valueOf(p.getAsString());
        }
        throw new IllegalArgumentException("null is not valid in custom_data");
    }
}
