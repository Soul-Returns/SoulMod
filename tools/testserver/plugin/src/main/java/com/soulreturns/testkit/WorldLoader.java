package com.soulreturns.testkit;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueInput;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.entity.Player;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.scheduler.BukkitTask;

/**
 * Rebuilds dev bridge world captures in dedicated void worlds at the original coordinates:
 * {@code /hx world load|unload|list|tp}. Blocks are written straight into chunk sections (no
 * physics, no neighbour updates, no fluid ticks), block entities and entities come from the
 * captured NBT, the time is fixed, the weather clear and nothing spawns. A capture lives in
 * {@code plugins/SoulTestKit/worlds/<name>.soulworld.gz} ({@code tools/testserver/s world install}).
 */
final class WorldLoader {
    static final String SUFFIX = ".soulworld.gz";
    private static final long TICK_BUDGET_NANOS = 30_000_000L;
    private static final int MAX_TICKETS = 2048;

    private final SoulTestKit plugin;
    private final Path dir;
    private final Map<String, Built> built = new LinkedHashMap<>();
    private Job job;

    private record Built(String name, String mode, String worldName, String report, double x, double z) {}

    WorldLoader(SoulTestKit plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("worlds");
    }

    Path dir() {
        return dir;
    }

    // ---------- commands ----------

    void command(CommandSender sender, Player player, String[] rest) {
        String usage = "world <list|load <name> [entry|latest]|unload <name|all> [entry|latest]|tp <name> [entry|latest] [x y z]|compare …|selftest …>";
        if (rest.length == 0) throw new IllegalArgumentException(usage);
        switch (rest[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "load" -> {
                if (rest.length < 2) throw new IllegalArgumentException("world load <name> [entry|latest]");
                load(sender, rest[1], rest.length > 2 ? mode(rest[2]) : "entry");
            }
            case "unload" -> {
                if (rest.length < 2) throw new IllegalArgumentException("world unload <name|all> [entry|latest]");
                unload(sender, rest[1], rest.length > 2 ? mode(rest[2]) : null);
            }
            case "tp" -> {
                if (player == null) throw new IllegalArgumentException("world tp needs a player (run it as SoulTester, or hx as SoulTester world tp …)");
                tp(sender, player, rest);
            }
            case "compare" -> WorldCompare.run(plugin, sender, rest);
            case "selftest" -> WorldSelfTest.run(plugin, sender, player, rest);
            default -> throw new IllegalArgumentException(usage);
        }
    }

    private static String mode(String s) {
        String m = s.toLowerCase(Locale.ROOT);
        if (!m.equals("entry") && !m.equals("latest")) throw new IllegalArgumentException("mode is entry (as first seen) or latest (all later changes applied)");
        return m;
    }

    static String worldName(String name, String mode) {
        return "soulcap_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_") + "_" + mode;
    }

    private Path file(String name) {
        return dir.resolve(name + SUFFIX);
    }

    private void list(CommandSender sender) {
        try (Stream<Path> files = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            List<Path> found = files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
            sender.sendMessage("§e" + found.size() + " installed capture(s) in " + dir);
            for (Path p : found) {
                String name = p.getFileName().toString().replace(SUFFIX, "");
                try {
                    WorldCapture cap = WorldCapture.read(p);
                    sender.sendMessage("§f" + name + " §7" + cap.summary());
                } catch (IOException | RuntimeException e) {
                    sender.sendMessage("§c" + name + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        for (Built b : built.values()) sender.sendMessage("§aloaded §f" + b.name + " " + b.mode + " §7world " + b.worldName + ": " + b.report);
    }

    private void load(CommandSender sender, String name, String mode) {
        if (job != null) throw new IllegalArgumentException("a load is still running (" + job.name + ")");
        Path path = file(name);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("no capture '" + name + "' in " + dir + " (tools/testserver/s world install FILE)");
        String worldName = worldName(name, mode);
        sender.sendMessage("§7reading " + path.getFileName() + " …");
        long t0 = System.nanoTime();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                WorldCapture cap = WorldCapture.read(path);
                WorldCapture.Resolved resolved = cap.resolve(mode.equals("latest"));
                double readMs = (System.nanoTime() - t0) / 1e6;
                Bukkit.getScheduler().runTask(plugin, () -> start(sender, name, mode, worldName, cap, resolved, readMs));
            } catch (IOException | RuntimeException e) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§c" + name + ": " + e));
            }
        });
    }

    private void start(CommandSender sender, String name, String mode, String worldName, WorldCapture cap, WorldCapture.Resolved resolved, double readMs) {
        try {
            if (Bukkit.getWorld(worldName) != null) unloadWorld(worldName);
            World world = create(worldName);
            ServerLevel level = ((CraftWorld) world).getHandle();
            job = new Job(sender, name, mode, worldName, world, level, cap, resolved, readMs);
            job.timer = Bukkit.getScheduler().runTaskTimer(plugin, job::tick, 1L, 1L);
        } catch (RuntimeException e) {
            sender.sendMessage("§c" + name + ": " + e);
            plugin.getLogger().warning("world load failed: " + e);
            try {
                unloadWorld(worldName);
            } catch (RuntimeException ignored) {
                // best effort: a half-created world must not block the next load
            }
        }
    }

    private World create(String worldName) {
        deleteStale(worldName);
        WorldCreator creator = new WorldCreator(worldName).environment(World.Environment.NORMAL).generator(new Void()).biomeProvider(new Plains());
        creator.generateStructures(false);
        World world = Bukkit.createWorld(creator);
        if (world == null) throw new IllegalStateException("could not create world " + worldName);
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setStorm(false);
        world.setThundering(false);
        world.setFullTime(6000);
        for (String rule : List.of("advance_time false", "advance_weather false", "spawn_mobs false", "spawn_monsters false", "random_tick_speed 0",
            "fire_spread_radius_around_player 0", "mob_griefing false", "fall_damage false")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "execute in " + world.getKey() + " run gamerule " + rule);
        }
        return world;
    }

    private void unload(CommandSender sender, String name, String mode) {
        List<Built> targets = new ArrayList<>();
        for (Built b : built.values()) if ((name.equalsIgnoreCase("all") || b.name.equalsIgnoreCase(name)) && (mode == null || b.mode.equals(mode))) targets.add(b);
        if (targets.isEmpty()) throw new IllegalArgumentException("nothing loaded matches " + name);
        for (Built b : targets) {
            unloadWorld(b.worldName);
            sender.sendMessage("§aunloaded " + b.name + " " + b.mode + " (world folder deleted)");
        }
    }

    private void unloadWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            Location out = Bukkit.getWorlds().get(0).getSpawnLocation();
            for (Player p : new ArrayList<>(world.getPlayers())) p.teleport(out);
            if (!Bukkit.unloadWorld(world, false)) throw new IllegalStateException("could not unload " + worldName);
            deleteTree(world.getWorldFolder().toPath());
        }
        built.remove(worldName);
    }

    private void tp(CommandSender sender, Player player, String[] rest) {
        if (rest.length < 2) throw new IllegalArgumentException("world tp <name> [entry|latest] [x y z]");
        int next = 2;
        String mode = null;
        if (rest.length > next && (rest[next].equalsIgnoreCase("entry") || rest[next].equalsIgnoreCase("latest"))) mode = rest[next++].toLowerCase(Locale.ROOT);
        Built target = null;
        for (Built b : built.values()) if (b.name.equalsIgnoreCase(rest[1]) && (mode == null || b.mode.equals(mode))) target = b;
        if (target == null) throw new IllegalArgumentException(rest[1] + (mode == null ? "" : " " + mode) + " is not loaded (hx world load " + rest[1] + ")");
        World world = Bukkit.getWorld(target.worldName);
        if (world == null) throw new IllegalArgumentException("world " + target.worldName + " is gone");
        Location to;
        if (rest.length >= next + 3) {
            to = new Location(world, Double.parseDouble(rest[next]), Double.parseDouble(rest[next + 1]), Double.parseDouble(rest[next + 2]), player.getYaw(), player.getPitch());
        } else {
            // the middle of the captured area, on top of whatever is highest there
            to = new Location(world, target.x, world.getHighestBlockYAt((int) Math.floor(target.x), (int) Math.floor(target.z)) + 1, target.z, player.getYaw(), player.getPitch());
        }
        player.setAllowFlight(true);
        player.teleport(to);
        sender.sendMessage("§a" + player.getName() + " → " + target.worldName + " at " + String.format(Locale.ROOT, "%.1f %.1f %.1f", to.getX(), to.getY(), to.getZ()));
    }

    // ---------- the void world ----------

    private static final class Void extends ChunkGenerator {}

    private static final class Plains extends BiomeProvider {
        @Override
        public Biome getBiome(WorldInfo info, int x, int y, int z) {
            return Biome.PLAINS;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo info) {
            return List.of(Biome.PLAINS);
        }
    }

    /** A leftover world folder (Paper 26 keeps them under <level>/dimensions/<namespace>/<name>) would bring old blocks back. */
    private static void deleteStale(String worldName) {
        Path root = Bukkit.getWorldContainer().toPath();
        try (Stream<Path> walk = Files.walk(root, 4)) {
            for (Path p : walk.filter(p -> Files.isDirectory(p) && p.getFileName() != null && p.getFileName().toString().equals(worldName)).toList()) deleteTree(p);
        } catch (IOException e) {
            throw new IllegalStateException("could not scan for stale world folders: " + e.getMessage());
        }
    }

    static void deleteTree(Path folder) {
        if (!Files.exists(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new IllegalStateException("could not delete " + folder + ": " + e.getMessage());
        }
    }

    // ---------- the load job: bounded work per tick ----------

    private final class Job {
        final CommandSender sender;
        final String name, mode, worldName;
        final World world;
        final ServerLevel level;
        final WorldCapture cap;
        final WorldCapture.Resolved resolved;
        final double readMs;
        final long started = System.nanoTime();
        final List<Map.Entry<Long, List<WorldCapture.Section>>> columns;
        final Map<Long, List<WorldCapture.BlockEntityRec>> beByColumn = new java.util.HashMap<>();
        final Map<Long, List<WorldCapture.EntityRec>> entByColumn = new java.util.HashMap<>();
        final BlockState[] states;
        BukkitTask timer;
        int next;
        long blocks, blockEntities, entities, badStates, badBlockEntities, badEntities, skippedBlockEntities;
        int ticks;
        Set<ChunkPos> relit;

        Job(CommandSender sender, String name, String mode, String worldName, World world, ServerLevel level, WorldCapture cap, WorldCapture.Resolved resolved, double readMs) {
            this.sender = sender;
            this.name = name;
            this.mode = mode;
            this.worldName = worldName;
            this.world = world;
            this.level = level;
            this.cap = cap;
            this.resolved = resolved;
            this.readMs = readMs;
            TreeMap<Long, List<WorldCapture.Section>> cols = cap.columns(resolved.sections());
            this.columns = new ArrayList<>(cols.entrySet());
            int max = cap.states.keySet().stream().mapToInt(i -> i).max().orElse(-1);
            this.states = new BlockState[max + 1];
            for (WorldCapture.BlockEntityRec r : resolved.blockEntities()) beByColumn.computeIfAbsent(col(r.x() >> 4, r.z() >> 4), k -> new ArrayList<>()).add(r);
            for (WorldCapture.EntityRec r : resolved.entities()) entByColumn.computeIfAbsent(col((int) Math.floor(r.x()) >> 4, (int) Math.floor(r.z()) >> 4), k -> new ArrayList<>()).add(r);
            JsonObject header = cap.header;
            sender.sendMessage("§7read in " + String.format(Locale.ROOT, "%.0f", readMs) + " ms: " + cols.size() + " columns, " + resolved.sections().size() + " sections"
                + (mode.equals("latest") ? ", " + resolved.changesApplied() + " block changes applied" : "") + (header.has("label") && !header.get("label").isJsonNull() ? ", label " + header.get("label").getAsString() : ""));
        }

        private static long col(int cx, int cz) {
            return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        }

        BlockState state(int id) {
            BlockState s = states[id];
            if (s == null) {
                String text = cap.states.get(id);
                try {
                    s = ((CraftBlockData) Bukkit.createBlockData(text)).getState();
                } catch (IllegalArgumentException e) {
                    badStates++;
                    plugin.getLogger().warning("unknown block state in capture: " + text);
                    s = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                }
                states[id] = s;
            }
            return s;
        }

        void tick() {
            long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
            ticks++;
            try {
                while (next < columns.size() && System.nanoTime() < deadline) column(columns.get(next++));
            } catch (RuntimeException e) {
                fail(e);
                return;
            }
            if (next >= columns.size()) finish();
        }

        void column(Map.Entry<Long, List<WorldCapture.Section>> entry) {
            int cx = (int) (entry.getKey() >> 32);
            int cz = entry.getKey().intValue();
            LevelChunk chunk = level.getChunk(cx, cz);
            BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            for (WorldCapture.Section s : entry.getValue()) {
                int index = chunk.getSectionIndexFromSectionY(s.sy);
                if (index < 0 || index >= chunk.getSections().length) continue;
                LevelChunkSection section = chunk.getSection(index);
                if (s.uniform() && state(s.palette[0]) == air) continue;
                int[] ids = s.blocks();
                for (int i = 0; i < 4096; i++) {
                    BlockState st = state(ids[i]);
                    if (st == air) continue;
                    section.setBlockState(i & 15, i >> 8, (i >> 4) & 15, st, false);
                    blocks++;
                }
            }
            Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
            long key = col(cx, cz);
            for (WorldCapture.BlockEntityRec r : beByColumn.getOrDefault(key, List.of())) {
                BlockPos pos = new BlockPos(r.x(), r.y(), r.z());
                try {
                    // Hypixel sends block entity data for positions that are air in the chunk (heads placed later): not an error
                    CompoundTag tag = WorldCapture.nbt(r.nbt());
                    Optional<net.minecraft.world.level.block.entity.BlockEntityType<?>> beType = BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(Identifier.parse(tag.getStringOr("id", "")));
                    if (beType.isEmpty() || !beType.get().isValid(chunk.getBlockState(pos))) {
                        skippedBlockEntities++;
                        continue;
                    }
                    BlockEntity be = BlockEntity.loadStatic(pos, chunk.getBlockState(pos), tag, level.registryAccess());
                    if (be == null) {
                        badBlockEntities++;
                    } else {
                        chunk.setBlockEntity(be);
                        blockEntities++;
                    }
                } catch (RuntimeException e) {
                    badBlockEntities++;
                    plugin.getLogger().warning("block entity at " + pos + ": " + e);
                }
            }
            for (WorldCapture.EntityRec r : entByColumn.getOrDefault(key, List.of())) spawn(r);
            chunk.markUnsaved();
            if (columns.size() <= MAX_TICKETS) world.addPluginChunkTicket(cx, cz, plugin);
        }

        /** An inert display copy: loaded from the captured NBT with a fresh UUID, no gravity, invulnerable, slots locked. */
        void spawn(WorldCapture.EntityRec r) {
            try {
                Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(r.type()));
                if (type.isEmpty()) {
                    badEntities++;
                    return;
                }
                CompoundTag tag = WorldCapture.nbt(r.nbt());
                tag.putIntArray("UUID", UUIDUtil.uuidToIntArray(UUID.randomUUID()));
                tag.putBoolean("Invulnerable", true);
                tag.putBoolean("NoGravity", true);
                tag.putBoolean("Silent", true);
                if (r.type().equals("minecraft:armor_stand")) tag.putInt("DisabledSlots", 4144959);
                if (r.type().endsWith("item_frame")) tag.putBoolean("Fixed", true);
                Optional<Entity> made = EntityType.create(type.get(), TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), level, EntitySpawnReason.LOAD);
                if (made.isEmpty()) {
                    badEntities++;
                    return;
                }
                Entity e = made.get();
                e.snapTo(r.x(), r.y(), r.z(), r.yaw(), r.pitch());
                if (level.addFreshEntity(e)) entities++;
                else badEntities++;
            } catch (RuntimeException e) {
                badEntities++;
                plugin.getLogger().warning("entity " + r.type() + ": " + e);
            }
        }

        void finish() {
            timer.cancel();
            double buildMs = (System.nanoTime() - started) / 1e6;
            List<ChunkPos> positions = new ArrayList<>();
            for (var c : columns) positions.add(new ChunkPos((int) (c.getKey() >> 32), c.getKey().intValue()));
            long lightStart = System.nanoTime();
            try {
                level.getChunkSource().getLightEngine().starlight$serverRelightChunks(positions, p -> {}, n -> {});
            } catch (RuntimeException e) {
                plugin.getLogger().warning("relight failed: " + e);
            }
            double lightMs = (System.nanoTime() - lightStart) / 1e6;
            JsonObject b = cap.header.has("bounds") ? cap.header.getAsJsonObject("bounds") : null;
            String report = String.format(Locale.ROOT, "%d columns, %d blocks, %d block entities (%d failed, %d without a matching block), %d entities (%d failed)%s; read %.0f ms, build %.0f ms in %d ticks, relight queued in %.0f ms; spawn %s",
                columns.size(), blocks, blockEntities, badBlockEntities, skippedBlockEntities, entities, badEntities, badStates > 0 ? ", " + badStates + " unknown states" : "", readMs, buildMs, ticks, lightMs, b == null ? "?" : b.get("stored"));
            double cx = 0, cz = 0;
            if (b != null && b.has("stored")) {
                var stored = b.getAsJsonArray("stored");
                cx = (stored.get(0).getAsInt() + stored.get(3).getAsInt() + 1) / 2.0;
                cz = (stored.get(2).getAsInt() + stored.get(5).getAsInt() + 1) / 2.0;
            }
            built.put(worldName, new Built(name, mode, worldName, report, cx, cz));
            job = null;
            sender.sendMessage("§aloaded " + name + " " + mode + " into world " + worldName + ": " + report);
            sender.sendMessage("§7visit with §f/hx world tp " + name + " " + mode + " x y z");
            plugin.getLogger().info("world load " + name + " " + mode + ": " + report);
        }

        void fail(RuntimeException e) {
            timer.cancel();
            job = null;
            sender.sendMessage("§cload of " + name + " failed: " + e);
            plugin.getLogger().warning("world load failed: " + e);
            e.printStackTrace();
        }
    }

    void disable() {
        if (job != null && job.timer != null) job.timer.cancel();
    }

    /** Loaded world names for tab completion. */
    List<String> loadedNames() {
        Set<String> out = new HashSet<>();
        for (Built b : built.values()) out.add(b.name);
        return new ArrayList<>(out);
    }

    List<String> installedNames() {
        try (Stream<Path> files = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(SUFFIX)).map(n -> n.substring(0, n.length() - SUFFIX.length())).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
