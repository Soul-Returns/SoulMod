package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.util.BoundingBox;

/**
 * {@code /hx world compare <A> <B> x1 y1 z1 x2 y2 z2 [dx dy dz]}: block by block (full state), block
 * entity by block entity (NBT) and entity by entity (position, type, name, items) comparison of a
 * region of world A with the same region of world B shifted by (dx, dy, dz). A world is a Bukkit
 * world name or {@code <capture>:<entry|latest>} for a loaded capture.
 */
final class WorldCompare {
    private static final List<String> ENTITY_KEYS = List.of("CustomName", "CustomNameVisible", "equipment", "Item", "ItemRotation", "Facing", "Pose", "Small", "ShowArms",
        "NoBasePlate", "Invisible", "Marker", "Glowing");

    private WorldCompare() {}

    private static World world(String arg) {
        World w = Bukkit.getWorld(arg);
        if (w == null && arg.contains(":")) w = Bukkit.getWorld(WorldLoader.worldName(arg.substring(0, arg.indexOf(':')), arg.substring(arg.indexOf(':') + 1)));
        if (w == null) throw new IllegalArgumentException("no world " + arg + " (a world name, or <capture>:<entry|latest> once loaded)");
        return w;
    }

    static void run(SoulTestKit plugin, CommandSender sender, String[] rest) {
        if (rest.length < 9) throw new IllegalArgumentException("world compare <A> <B> x1 y1 z1 x2 y2 z2 [dx dy dz]");
        World wa = world(rest[1]);
        World wb = world(rest[2]);
        int[] v = new int[9];
        for (int i = 0; i < 9; i++) v[i] = i + 3 < rest.length ? Integer.parseInt(rest[i + 3]) : 0;
        int x1 = Math.min(v[0], v[3]), x2 = Math.max(v[0], v[3]), y1 = Math.min(v[1], v[4]), y2 = Math.max(v[1], v[4]), z1 = Math.min(v[2], v[5]), z2 = Math.max(v[2], v[5]);
        int dx = v[6], dy = v[7], dz = v[8];
        ServerLevel a = ((CraftWorld) wa).getHandle();
        ServerLevel b = ((CraftWorld) wb).getHandle();
        long t0 = System.nanoTime();
        long total = 0, mismatches = 0, nonAir = 0;
        List<String> samples = new ArrayList<>();
        BlockPos.MutableBlockPos pa = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos pb = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = y1; y <= y2; y++) {
                    BlockState sa = a.getBlockState(pa.set(x, y, z));
                    BlockState sb = b.getBlockState(pb.set(x + dx, y + dy, z + dz));
                    total++;
                    if (!sa.isAir()) nonAir++;
                    if (sa != sb) {
                        mismatches++;
                        if (samples.size() < 10) samples.add("  " + x + "," + y + "," + z + ": " + sa + " vs " + sb);
                    }
                }
            }
        }
        long beTotal = 0, beMismatches = 0;
        for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) {
            for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) {
                LevelChunk chunk = a.getChunk(cx, cz);
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    BlockPos p = be.getBlockPos();
                    if (p.getX() < x1 || p.getX() > x2 || p.getY() < y1 || p.getY() > y2 || p.getZ() < z1 || p.getZ() > z2) continue;
                    beTotal++;
                    BlockEntity other = b.getBlockEntity(new BlockPos(p.getX() + dx, p.getY() + dy, p.getZ() + dz));
                    CompoundTag ta = be.saveWithoutMetadata(a.registryAccess());
                    CompoundTag tb = other == null ? null : other.saveWithoutMetadata(b.registryAccess());
                    if (!ta.equals(tb)) {
                        beMismatches++;
                        if (samples.size() < 20) samples.add("  block entity " + p.getX() + "," + p.getY() + "," + p.getZ() + ": " + ta + " vs " + tb);
                    }
                }
            }
        }
        BoundingBox box = new BoundingBox(x1, y1, z1, x2 + 1, y2 + 1, z2 + 1);
        long entTotal = 0, entMismatches = 0;
        List<org.bukkit.entity.Entity> ea = new ArrayList<>(wa.getNearbyEntities(box, e -> !(e instanceof org.bukkit.entity.Player)));
        List<org.bukkit.entity.Entity> eb = new ArrayList<>(wb.getNearbyEntities(box.clone().shift(dx, dy, dz), e -> !(e instanceof org.bukkit.entity.Player)));
        for (org.bukkit.entity.Entity e : ea) {
            net.minecraft.world.entity.Entity na = ((CraftEntity) e).getHandle();
            if (!(na instanceof net.minecraft.world.entity.decoration.ArmorStand || na instanceof net.minecraft.world.entity.decoration.HangingEntity || na instanceof net.minecraft.world.entity.Display)) continue;
            entTotal++;
            CompoundTag ta = project(na, a);
            boolean found = false;
            for (org.bukkit.entity.Entity f : eb) {
                net.minecraft.world.entity.Entity nb = ((CraftEntity) f).getHandle();
                if (nb.getType() != na.getType() || Math.abs(nb.getX() - na.getX() - dx) > 0.01 || Math.abs(nb.getY() - na.getY() - dy) > 0.01 || Math.abs(nb.getZ() - na.getZ() - dz) > 0.01) continue;
                if (project(nb, b).equals(ta)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                entMismatches++;
                if (samples.size() < 30) samples.add(String.format(Locale.ROOT, "  entity %s at %.2f,%.2f,%.2f: %s has no match", na.getType(), na.getX(), na.getY(), na.getZ(), ta));
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        sender.sendMessage(String.format(Locale.ROOT, "§%scompare %s vs %s (+%d,%d,%d): blocks %d (%d non-air) mismatches=%d; block entities %d mismatches=%d; entities %d mismatches=%d (%.0f ms; B has %d entities in the region)",
            mismatches + beMismatches + entMismatches == 0 ? "a" : "c", rest[1], rest[2], dx, dy, dz, total, nonAir, mismatches, beTotal, beMismatches, entTotal, entMismatches, ms, eb.size()));
        for (String s : samples) sender.sendMessage("§7" + s);
        plugin.getLogger().info("world compare " + rest[1] + " vs " + rest[2] + ": blocks mismatches=" + mismatches + "/" + total + ", block entities mismatches=" + beMismatches + "/" + beTotal + ", entities mismatches=" + entMismatches + "/" + entTotal);
    }

    private static CompoundTag project(net.minecraft.world.entity.Entity e, ServerLevel level) {
        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
        e.saveWithoutId(out);
        CompoundTag full = out.buildResult();
        CompoundTag p = new CompoundTag();
        for (String key : ENTITY_KEYS) {
            Tag t = full.get(key);
            if (t != null) p.put(key, t);
        }
        return p;
    }
}
