package com.soulreturns.testkit;

import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;

/**
 * {@code /hx world selftest build [x y z]}: a recognizable structure in the sender's world for the
 * capture pipeline's end-to-end check (tools/devbridge/world-selftest): every kind of block state
 * that matters (stairs in all orientations and shapes, slabs, levers, wire, fences, doors, trap doors),
 * a player head with a texture, signs, a chest, a banner, a named armor stand with equipment and
 * an item frame. Two clusters 160 blocks apart, so one of them loads late while walking.
 */
final class WorldSelfTest {
    static final int SECOND_CLUSTER = 160;

    private WorldSelfTest() {}

    static void run(SoulTestKit plugin, CommandSender sender, Player player, String[] rest) {
        if (rest.length >= 5 && rest[1].equalsIgnoreCase("state")) {
            World w = rest.length > 5 && Bukkit.getWorld(rest[5]) != null ? Bukkit.getWorld(rest[5])
                : player != null ? player.getWorld() : Bukkit.getWorlds().get(0);
            sender.sendMessage("state " + w.getBlockAt(Integer.parseInt(rest[2]), Integer.parseInt(rest[3]), Integer.parseInt(rest[4])).getBlockData().getAsString());
            return;
        }
        if (rest.length >= 2 && rest[1].equalsIgnoreCase("noise")) {
            noise(sender, player, rest);
            return;
        }
        if (rest.length < 2 || !rest[1].equalsIgnoreCase("build")) throw new IllegalArgumentException("world selftest build [x y z] | noise x1 y1 z1 x2 y2 z2 [air%] | state x y z");
        World world;
        int ox, oy, oz;
        if (rest.length >= 5) {
            world = player != null ? player.getWorld() : Bukkit.getWorlds().get(0);
            ox = Integer.parseInt(rest[2]);
            oy = Integer.parseInt(rest[3]);
            oz = Integer.parseInt(rest[4]);
        } else {
            if (player == null) throw new IllegalArgumentException("world selftest build x y z (from the console)");
            world = player.getWorld();
            ox = player.getLocation().getBlockX();
            oy = player.getLocation().getBlockY();
            oz = player.getLocation().getBlockZ();
        }
        cluster(world, ox, oy, oz, "A");
        cluster(world, ox + SECOND_CLUSTER, oy, oz, "B");
        sender.sendMessage(String.format(Locale.ROOT, "§aselftest structure built in %s: clusters at x %d and %d, y %d..%d, z %d..%d (24x24 each); capture bounds %d,%d,%d,%d,%d,%d",
            world.getName(), ox, ox + SECOND_CLUSTER, oy, oy + 8, oz, oz + 23, ox - 4, oy - 2, oz - 4, ox + SECOND_CLUSTER + 27, oy + 12, oz + 27));
    }

    /**
     * Dense blob noise straight into chunk sections (no updates), for measuring capture cost and size at boss-room scale:
     * 4-block cells of 12 block types, {@code air%} (default 70) of the cells empty.
     */
    private static void noise(CommandSender sender, Player player, String[] rest) {
        if (rest.length < 8) throw new IllegalArgumentException("world selftest noise x1 y1 z1 x2 y2 z2 [air%]");
        World world = player != null ? player.getWorld() : Bukkit.getWorlds().get(0);
        int x1 = Integer.parseInt(rest[2]), y1 = Integer.parseInt(rest[3]), z1 = Integer.parseInt(rest[4]);
        int x2 = Integer.parseInt(rest[5]), y2 = Integer.parseInt(rest[6]), z2 = Integer.parseInt(rest[7]);
        int air = rest.length > 8 ? Integer.parseInt(rest[8]) : 70;
        net.minecraft.world.level.block.state.BlockState[] palette = Stream.of(
            "stone", "cobblestone", "stone_bricks", "andesite", "coal_block", "gold_block", "dark_prismarine", "quartz_block", "red_terracotta", "cyan_terracotta", "glass", "oak_planks")
            .map(n -> ((org.bukkit.craftbukkit.block.data.CraftBlockData) Bukkit.createBlockData("minecraft:" + n)).getState()).toArray(net.minecraft.world.level.block.state.BlockState[]::new);
        var level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
        long t0 = System.nanoTime();
        long blocks = 0;
        for (int cx = Math.min(x1, x2) >> 4; cx <= Math.max(x1, x2) >> 4; cx++) {
            for (int cz = Math.min(z1, z2) >> 4; cz <= Math.max(z1, z2) >> 4; cz++) {
                var chunk = level.getChunk(cx, cz);
                for (int sy = Math.min(y1, y2) >> 4; sy <= Math.max(y1, y2) >> 4; sy++) {
                    var section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    for (int i = 0; i < 4096; i++) {
                        int x = (cx << 4) | (i & 15), y = (sy << 4) | (i >> 8), z = (cz << 4) | ((i >> 4) & 15);
                        if (x < Math.min(x1, x2) || x > Math.max(x1, x2) || y < Math.min(y1, y2) || y > Math.max(y1, y2) || z < Math.min(z1, z2) || z > Math.max(z1, z2)) continue;
                        int h = ((x >> 2) * 73856093) ^ ((y >> 2) * 19349663) ^ ((z >> 2) * 83492791);
                        h ^= h >>> 15;
                        h *= 0x2c1b3c6d;
                        h ^= h >>> 12;
                        h &= 0x7fffffff;
                        if (h % 100 < air) continue;
                        section.setBlockState(i & 15, i >> 8, (i >> 4) & 15, palette[(h / 100) % palette.length], false);
                        blocks++;
                    }
                }
                chunk.markUnsaved();
            }
        }
        sender.sendMessage(String.format(Locale.ROOT, "§anoise: %d blocks in %.0f ms (x %d..%d, y %d..%d, z %d..%d)", blocks, (System.nanoTime() - t0) / 1e6, x1, x2, y1, y2, z1, z2));
    }

    private static void set(World w, int x, int y, int z, String data) {
        w.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(data), false);
    }

    private static void cluster(World w, int ox, int oy, int oz, String tag) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) set(w, ox + x, oy, oz + z, (x + z) % 5 == 0 ? "minecraft:cracked_stone_bricks" : "minecraft:stone_bricks");
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 8; y++) set(w, ox + x, oy + y, oz + z, "minecraft:air");
        String[] facings = {"north", "east", "south", "west"};
        String[] shapes = {"straight", "inner_left", "inner_right", "outer_left", "outer_right"};
        int i = 0;
        for (String shape : shapes) {
            for (String half : List.of("bottom", "top")) {
                for (String f : facings) set(w, ox + (i % 20), oy + 1 + (i / 20), oz + 1, "minecraft:oak_stairs[facing=" + f + ",half=" + half + ",shape=" + shape + "]");
                i += 4;
            }
        }
        i = 0;
        for (String type : List.of("bottom", "top", "double")) for (String wl : List.of("false", "true")) set(w, ox + 1 + 2 * i++, oy + 1, oz + 4, "minecraft:spruce_slab[type=" + type + ",waterlogged=" + wl + "]");
        i = 0;
        for (String face : List.of("floor", "wall", "ceiling")) {
            for (String f : facings) {
                for (String powered : List.of("false", "true")) {
                    set(w, ox + i % 24, oy + 2, oz + 6 + i / 24, "minecraft:lever[face=" + face + ",facing=" + f + ",powered=" + powered + "]");
                    i++;
                }
            }
        }
        set(w, ox + 2, oy + 1, oz + 9, "minecraft:repeater[delay=3,facing=west,locked=false,powered=true]");
        set(w, ox + 4, oy + 1, oz + 9, "minecraft:comparator[facing=south,mode=subtract,powered=false]");
        set(w, ox + 6, oy + 1, oz + 9, "minecraft:redstone_wire[east=side,north=up,power=7,south=none,west=side]");
        for (int k = 0; k < 5; k++) set(w, ox + 8 + k, oy + 1, oz + 9, "minecraft:oak_fence[east=" + (k < 4) + ",north=false,south=false,waterlogged=false,west=" + (k > 0) + "]");
        set(w, ox + 14, oy + 1, oz + 9, "minecraft:glass_pane[east=true,north=true,south=false,waterlogged=false,west=false]");
        set(w, ox + 16, oy + 1, oz + 9, "minecraft:cobblestone_wall[east=low,north=tall,south=none,up=true,waterlogged=false,west=none]");
        set(w, ox + 2, oy + 1, oz + 11, "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]");
        set(w, ox + 2, oy + 2, oz + 11, "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]");
        set(w, ox + 4, oy + 1, oz + 11, "minecraft:oak_door[facing=north,half=lower,hinge=right,open=true,powered=false]");
        set(w, ox + 4, oy + 2, oz + 11, "minecraft:oak_door[facing=north,half=upper,hinge=right,open=true,powered=false]");
        set(w, ox + 6, oy + 1, oz + 11, "minecraft:jungle_trapdoor[facing=west,half=top,open=false,powered=false,waterlogged=false]");
        set(w, ox + 8, oy + 1, oz + 11, "minecraft:jungle_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=true]");
        set(w, ox + 10, oy + 1, oz + 11, "minecraft:water[level=3]");
        set(w, ox + 12, oy + 1, oz + 11, "minecraft:wall_torch[facing=east]");
        set(w, ox + 14, oy + 1, oz + 11, "minecraft:lantern[hanging=true,waterlogged=false]");
        set(w, ox + 16, oy + 1, oz + 11, "minecraft:chest[facing=east,type=left,waterlogged=false]");
        set(w, ox + 16, oy + 1, oz + 12, "minecraft:chest[facing=east,type=right,waterlogged=false]");
        set(w, ox + 18, oy + 1, oz + 11, "minecraft:white_banner[rotation=5]");
        set(w, ox + 20, oy + 1, oz + 11, "minecraft:coal_block");
        set(w, ox + 21, oy + 1, oz + 11, "minecraft:cave_air");
        set(w, ox + 1, oy + 1, oz + 14, "minecraft:oak_wall_sign[facing=south,waterlogged=false]");
        set(w, ox + 3, oy + 1, oz + 14, "minecraft:oak_sign[rotation=3,waterlogged=false]");
        set(w, ox + 5, oy + 1, oz + 14, "minecraft:player_head[rotation=4,powered=false]");
        set(w, ox + 7, oy + 1, oz + 14, "minecraft:player_wall_head[facing=west]");
        set(w, ox + 9, oy + 1, oz + 14, "minecraft:command_block[conditional=false,facing=up]");

        if (w.getBlockAt(ox + 1, oy + 1, oz + 14).getState() instanceof Sign sign) {
            sign.getSide(Side.FRONT).line(0, Component.text("Soul selftest " + tag));
            sign.getSide(Side.FRONT).line(1, Component.text("second line"));
            sign.getSide(Side.BACK).line(0, Component.text("back"));
            sign.update(true, false);
        }
        if (w.getBlockAt(ox + 3, oy + 1, oz + 14).getState() instanceof Sign sign) {
            sign.getSide(Side.FRONT).line(2, Component.text("standing"));
            sign.setWaxed(true);
            sign.update(true, false);
        }
        String texture = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}}}").getBytes());
        for (int hx : new int[] {5, 7}) {
            if (w.getBlockAt(ox + hx, oy + 1, oz + 14).getState() instanceof Skull skull) {
                var profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("soul-selftest-" + tag).getBytes()), "SoulHead");
                profile.setProperty(new ProfileProperty("textures", texture));
                skull.setPlayerProfile(profile);
                skull.update(true, false);
            }
        }

        // the chunks are loaded by now; a rebuild must not stack a second set of stands and frames
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(org.bukkit.util.BoundingBox.of(new org.bukkit.util.Vector(ox - 2, oy - 2, oz - 2), new org.bukkit.util.Vector(ox + 26, oy + 12, oz + 26)), x -> !(x instanceof Player))) e.remove();
        Location stand = new Location(w, ox + 12.5, oy + 1, oz + 14.5, 45f, 0f);
        w.spawn(stand, ArmorStand.class, as -> {
            as.customName(Component.text("Selftest Stand " + tag));
            as.setCustomNameVisible(true);
            as.setArms(true);
            as.setSmall(true);
            as.setGravity(false);
            as.setBasePlate(false);
            as.setItem(EquipmentSlot.HEAD, new ItemStack(Material.DIAMOND_HELMET));
            as.setItem(EquipmentSlot.HAND, new ItemStack(Material.GOLDEN_SWORD));
            as.setHeadPose(new EulerAngle(0.3, 0.6, 0.0));
        });
        Location frame = new Location(w, ox + 14, oy + 2, oz + 14);
        w.getBlockAt(frame).setType(Material.AIR, false);
        w.getBlockAt(ox + 14, oy + 2, oz + 15).setBlockData(Bukkit.createBlockData("minecraft:obsidian"), false);
        w.spawn(frame, ItemFrame.class, f -> {
            f.setFacingDirection(BlockFace.NORTH, true);
            f.setItem(new ItemStack(Material.DIAMOND_SWORD));
            f.setRotation(Rotation.CLOCKWISE_45);
        });
    }
}
