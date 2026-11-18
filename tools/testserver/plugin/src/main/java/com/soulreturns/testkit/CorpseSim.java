package com.soulreturns.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Glacite Mineshaft Frozen Corpses and the Organ Donor Talisman's ding, as measured on Hypixel
 * (recording 2026-11-18_02-55-36_mineshaft-corpses, rule://mineshaft "Corpse locator"): a BLOCKS-source
 * {@code block.note_block.harp} at the player's position with pitch {@code floor(63 * (2 - (d/20)^2)) / 63},
 * d = feet to the nearest unlooted corpse stand, every ~400 ms + 30 ms per block, silent beyond 20.
 * Spots come from the mod's own {@code corpse_spots.json} (copied into this jar at build time).
 * Stands wear their SkyBlock helmet id (Lapis {@code LAPIS_ARMOR_HELMET}, Umber {@code ARMOR_OF_YOG_HELMET},
 * Tungsten {@code MINERAL_HELMET}, Vanguard {@code VANGUARD_HELMET}; {@code other} an id no corpse uses).
 */
public final class CorpseSim {
    private static final String[] TYPES = {"lapis", "umber", "tungsten", "vanguard", "other"};
    private static final Map<String, String> COLOR = Map.of("lapis", "§9", "umber", "§6", "tungsten", "§7", "vanguard", "§b", "other", "§d");
    private static final Map<String, String> HELMET_ID = Map.of(
        "lapis", "LAPIS_ARMOR_HELMET", "umber", "ARMOR_OF_YOG_HELMET", "tungsten", "MINERAL_HELMET", "vanguard", "VANGUARD_HELMET", "other", "FROSTBITTEN_HELMET");
    /** Stand-in looks only; the mod reads the id. */
    private static final Map<String, Material> HEAD = Map.of(
        "lapis", Material.LAPIS_BLOCK, "umber", Material.BROWN_TERRACOTTA, "tungsten", Material.GRAY_CONCRETE, "vanguard", Material.DIAMOND_HELMET, "other", Material.GLASS);
    /** {@code corpse wall}: stone this far east of the spot (the side {@code tp} puts the player on). */
    private static final int WALL_OFFSET = 5;
    /** Hypixel's tab row follows the loot line by ~0.8 s. */
    private static final long TAB_LAG_TICKS = 16L;

    private static final class Corpse {
        final int index;
        final String type;
        final Location stand;
        ArmorStand entity;
        boolean looted;

        Corpse(int index, String type, Location stand) {
            this.index = index;
            this.type = type;
            this.stand = stand;
        }
    }

    private static final class Session {
        String layout;
        List<int[]> spots;
        final List<Corpse> corpses = new ArrayList<>();
        BukkitTask task;
        long tick;
        long nextDing;
        final List<Location> wall = new ArrayList<>();
        final List<Location> cage = new ArrayList<>();
    }

    private final SoulTestKit plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private JsonObject layouts;
    /** {@code corpse talisman off}: no Organ Donor in the Accessory Bag, so no dings (kept across starts). */
    private boolean talisman = true;

    public CorpseSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    public List<String> completions(int pos) {
        return pos == 1 ? List.of("start", "tp", "wall", "cage", "place", "loot", "talisman", "status", "stop") : List.of();
    }

    public List<String> command(Player player, String[] rest) {
        if (rest.length == 0) throw new IllegalArgumentException(usage());
        return switch (rest[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (rest.length < 2) throw new IllegalArgumentException("corpse start <layout> [spot[:type]…]");
                yield start(player, rest[1].toUpperCase(Locale.ROOT), java.util.Arrays.copyOfRange(rest, 2, rest.length));
            }
            case "tp" -> {
                if (rest.length < 2) throw new IllegalArgumentException("corpse tp <spot> [distance] [side]");
                yield List.of(tp(player, Integer.parseInt(rest[1]), rest.length > 2 ? Double.parseDouble(rest[2]) : 12.0,
                    rest.length > 3 ? Double.parseDouble(rest[3]) : 0.0));
            }
            case "wall" -> {
                if (rest.length < 2) throw new IllegalArgumentException("corpse wall <spot> [on|off]");
                yield List.of(wall(player, Integer.parseInt(rest[1]), rest.length < 3 || rest[2].equalsIgnoreCase("on")));
            }
            case "cage" -> {
                if (rest.length < 2) throw new IllegalArgumentException("corpse cage <spot> [on|off]");
                yield List.of(cage(player, Integer.parseInt(rest[1]), rest.length < 3 || rest[2].equalsIgnoreCase("on")));
            }
            case "place" -> {
                if (rest.length < 5) throw new IllegalArgumentException("corpse place <spot> <fx> <fy> <fz>");
                yield List.of(place(player, Integer.parseInt(rest[1]), Double.parseDouble(rest[2]), Double.parseDouble(rest[3]), Double.parseDouble(rest[4])));
            }
            case "loot" -> List.of(loot(player, rest.length > 1 ? Integer.parseInt(rest[1]) : -1));
            case "status" -> status(player);
            case "talisman" -> {
                if (rest.length < 2) throw new IllegalArgumentException("corpse talisman <on|off>");
                talisman = rest[1].equalsIgnoreCase("on");
                yield List.of("organ donor dings " + (talisman ? "on" : "off"));
            }
            case "stop" -> List.of(stop(player) ? "corpses stopped" : "no corpses running");
            default -> throw new IllegalArgumentException(usage());
        };
    }

    private static String usage() {
        return "corpse <start <layout> [spot[:type]…]|tp <spot> [distance] [side]|wall <spot> [on|off]|cage <spot> [on|off]|place <spot> <fx> <fy> <fz>|loot [spot]|talisman <on|off>|status|stop>";
    }

    private List<int[]> spots(String layout) {
        if (layouts == null) {
            try (InputStream in = CorpseSim.class.getResourceAsStream("/corpse_spots.json")) {
                if (in == null) throw new IllegalStateException("corpse_spots.json missing from the plugin jar");
                layouts = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        JsonElement list = layouts.getAsJsonObject("layouts").get(layout);
        if (list == null) return null;
        List<int[]> out = new ArrayList<>();
        for (JsonElement e : list.getAsJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            out.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
        }
        return out;
    }

    /**
     * Mineshaft location, sidebar {@code <date> <server> <layout>}, tab corpse rows, corpses on the
     * chosen spots (0-based; default the first two, FAIR_1 its Vanguard), the player flying 14 blocks
     * from the first corpse. A layout without data (RUBY_C, LITT_L) gets the sidebar and no corpses.
     */
    private List<String> start(Player player, String layout, String[] picks) {
        stop(player);
        Session s = new Session();
        s.layout = layout;
        s.spots = spots(layout);
        boolean vanguard = layout.equals("FAIR_1");
        if (s.spots != null) {
            List<String> chosen = new ArrayList<>(List.of(picks));
            if (chosen.isEmpty()) {
                if (vanguard) chosen.add("0:vanguard");
                else for (int i = 0; i < Math.min(2, s.spots.size()); i++) chosen.add(i + ":lapis");
            }
            if (chosen.size() > 4) throw new IllegalArgumentException("at most 4 corpses in a mineshaft");
            for (String pick : chosen) {
                String[] parts = pick.split(":");
                int i = Integer.parseInt(parts[0]);
                if (i < 0 || i >= s.spots.size()) throw new IllegalArgumentException(layout + " has spots 0-" + (s.spots.size() - 1));
                String type = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : (vanguard ? "vanguard" : "lapis");
                if (!List.of(TYPES).contains(type)) throw new IllegalArgumentException("type " + type + " (" + String.join(", ", TYPES) + ")");
                int[] b = s.spots.get(i);
                // Inside the spot block, as the real stands are (e.g. -171.81, 16.44, -185.38 on -172, 16, -186).
                Location at = new Location(player.getWorld(), b[0] + 0.19, b[1] + 0.44, b[2] + 0.63);
                Corpse c = new Corpse(i, type, at);
                c.entity = player.getWorld().spawn(at, ArmorStand.class, a -> {
                    a.setGravity(false);
                    a.setPersistent(false);
                    a.setArms(true);
                    a.setItem(EquipmentSlot.HEAD, helmet(type));
                });
                s.corpses.add(c);
            }
        }
        plugin.clearSidebar(player);
        plugin.clearTab(player);
        // Every mineshaft is a fresh server: leave the area first so a restart is a new stay.
        plugin.applyLocation(player, "dwarven");
        plugin.applyLocation(player, "mineshaft");
        showSidebar(player, layout);
        showTab(player, s);
        player.setAllowFlight(true);
        player.setFlying(true);
        sessions.put(player.getUniqueId(), s);
        if (!s.corpses.isEmpty()) tp(player, s.corpses.get(0).index, 14.0, 0.0);
        s.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(player, s), 1L, 1L);
        List<String> out = new ArrayList<>();
        out.add("mineshaft " + layout + (s.spots == null ? " (no spot data)" : ", " + s.corpses.size() + " corpse(s)"));
        out.addAll(status(player));
        return out;
    }

    private static ItemStack helmet(String type) {
        JsonObject def = new JsonObject();
        def.addProperty("material", HEAD.get(type).name());
        def.addProperty("name", "§f" + HELMET_ID.get(type));
        return Items.build(HELMET_ID.get(type), def, 1);
    }

    /** Puts the player {@code distance} blocks east of a spot ({@code side} blocks south), one block up, facing the stand. */
    private String tp(Player player, int index, double distance, double side) {
        Session s = session(player);
        if (s.spots == null || index < 0 || index >= s.spots.size()) throw new IllegalArgumentException("no spot " + index);
        int[] b = s.spots.get(index);
        Location at = new Location(player.getWorld(), b[0] + 0.5 + distance, b[1] + 1, b[2] + 0.5 + side);
        Location eye = at.clone().add(0, player.getEyeHeight(), 0);
        at.setDirection(new Location(player.getWorld(), b[0] + 0.5, b[1] + 1.5, b[2] + 0.5).toVector().subtract(eye.toVector()));
        player.setFlying(true);
        player.teleport(at);
        return String.format(Locale.ROOT, "at %.1f %.1f %.1f, %.1f blocks from spot %d", at.getX(), at.getY(), at.getZ(), Math.hypot(distance, side), index);
    }

    /**
     * A 5 wide, 5 high stone wall {@value #WALL_OFFSET} blocks east of a spot: from {@code tp <spot> 9} the
     * stand is behind it (no line of sight), from {@code tp <spot> 9 6} it is in view again.
     */
    private String wall(Player player, int index, boolean on) {
        Session s = session(player);
        for (Location l : s.wall) l.getBlock().setType(Material.AIR);
        s.wall.clear();
        if (!on) return "wall removed";
        if (s.spots == null || index < 0 || index >= s.spots.size()) throw new IllegalArgumentException("no spot " + index);
        int[] b = s.spots.get(index);
        for (int dz = -2; dz <= 2; dz++) {
            for (int dy = -1; dy <= 3; dy++) {
                Location l = new Location(player.getWorld(), b[0] + WALL_OFFSET, b[1] + dy, b[2] + dz);
                l.getBlock().setType(Material.STONE);
                s.wall.add(l);
            }
        }
        return "wall at x " + (b[0] + WALL_OFFSET) + " east of spot " + index;
    }

    /**
     * Connected iron bars around a spot (the 8 blocks around it, 3 high from the spot's y), like the
     * caged corpses on Hypixel: seen through the gaps, while every bar's collision shape spans its face.
     */
    private String cage(Player player, int index, boolean on) {
        Session s = session(player);
        for (Location l : s.cage) l.getBlock().setType(Material.AIR);
        s.cage.clear();
        if (!on) return "cage removed";
        if (s.spots == null || index < 0 || index >= s.spots.size()) throw new IllegalArgumentException("no spot " + index);
        int[] b = s.spots.get(index);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 0; dy <= 2; dy++) {
                    Location l = new Location(player.getWorld(), b[0] + dx, b[1] + dy, b[2] + dz);
                    MultipleFacing bars = (MultipleFacing) Material.IRON_BARS.createBlockData();
                    // Connected to the ring neighbours, as Hypixel's cages are.
                    if (inRing(dx, dz - 1)) bars.setFace(BlockFace.NORTH, true);
                    if (inRing(dx, dz + 1)) bars.setFace(BlockFace.SOUTH, true);
                    if (inRing(dx - 1, dz)) bars.setFace(BlockFace.WEST, true);
                    if (inRing(dx + 1, dz)) bars.setFace(BlockFace.EAST, true);
                    l.getBlock().setBlockData(bars, false);
                    s.cage.add(l);
                }
            }
        }
        return "iron bar cage around spot " + index;
    }

    private static boolean inRing(int dx, int dz) {
        return Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && !(dx == 0 && dz == 0);
    }

    /** Moves a spot's stand to block + (fx, fy, fz); negative fractions put it a hair outside the block (boundary cases). */
    private String place(Player player, int index, double fx, double fy, double fz) {
        Session s = session(player);
        Corpse c = s.corpses.stream().filter(x -> x.index == index && !x.looted).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no unlooted corpse on spot " + index));
        int[] b = s.spots.get(index);
        c.stand.set(b[0] + fx, b[1] + fy, b[2] + fz);
        if (c.entity != null) c.entity.teleport(c.stand);
        return String.format(Locale.ROOT, "stand of spot %d at %.3f %.3f %.3f", index, c.stand.getX(), c.stand.getY(), c.stand.getZ());
    }

    /** The loot line for the nearest unlooted corpse (or spot {@code index}); its tab row flips a moment later. */
    private String loot(Player player, int index) {
        Session s = session(player);
        Location p = player.getLocation();
        Corpse c = s.corpses.stream()
            .filter(x -> !x.looted && (index < 0 || x.index == index))
            .min((a, b) -> Double.compare(a.stand.distanceSquared(p), b.stand.distanceSquared(p)))
            .orElseThrow(() -> new IllegalArgumentException("no unlooted corpse" + (index < 0 ? "" : " on spot " + index)));
        c.looted = true;
        if (c.entity != null) c.entity.remove();
        String upper = c.type.toUpperCase(Locale.ROOT);
        Nms.sendSystemChat(player, Txt.of("§r  §r" + COLOR.get(c.type) + "§l" + upper + " §r§b§lCORPSE LOOT! "), false);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && sessions.get(player.getUniqueId()) == s) showTab(player, s);
        }, TAB_LAG_TICKS);
        return upper + " corpse on spot " + c.index + " looted";
    }

    private List<String> status(Player player) {
        Session s = session(player);
        List<String> out = new ArrayList<>();
        Location p = player.getLocation();
        for (Corpse c : s.corpses) {
            out.add(String.format(Locale.ROOT, "spot %d %s at %.2f %.2f %.2f, %.1f away%s", c.index, c.type,
                c.stand.getX(), c.stand.getY(), c.stand.getZ(), c.stand.distance(p), c.looted ? ", looted" : ""));
        }
        return out;
    }

    public boolean stop(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) return false;
        if (s.task != null) s.task.cancel();
        for (Corpse c : s.corpses) if (c.entity != null) c.entity.remove();
        for (Location l : s.wall) l.getBlock().setType(Material.AIR);
        for (Location l : s.cage) l.getBlock().setType(Material.AIR);
        return true;
    }

    public void disable() {
        for (Session s : sessions.values()) {
            if (s.task != null) s.task.cancel();
            for (Corpse c : s.corpses) if (c.entity != null) c.entity.remove();
            for (Location l : s.wall) l.getBlock().setType(Material.AIR);
            for (Location l : s.cage) l.getBlock().setType(Material.AIR);
        }
        sessions.clear();
    }

    private Session session(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) throw new IllegalArgumentException("no corpses running (corpse start <layout>)");
        return s;
    }

    private void tick(Player player, Session s) {
        if (!player.isOnline()) {
            stop(player);
            return;
        }
        s.tick++;
        if (!talisman) return;
        if (s.tick < s.nextDing) return;
        Location at = player.getLocation();
        double d = s.corpses.stream().filter(c -> !c.looted && c.stand.getWorld() == at.getWorld())
            .mapToDouble(c -> c.stand.distance(at)).min().orElse(Double.MAX_VALUE);
        if (d > 20) {
            s.nextDing = s.tick + 1;
            return;
        }
        int n = Math.min(126, (int) Math.floor(63 * (2 - (d / 20) * (d / 20))));
        player.playSound(at, Sound.BLOCK_NOTE_BLOCK_HARP, SoundCategory.BLOCKS, 1f, n / 63f);
        s.nextDing = s.tick + Math.max(1, Math.round((400 + 30 * d) / 50));
    }

    private void showSidebar(Player player, String layout) {
        List<String> lines = List.of(
            "§7{date} §8{serverShort}§8 " + layout,
            "",
            " Late Autumn 29th",
            " §75:00am §b☽",
            " §7⏣ §bGlacite Mineshafts",
            "",
            "§fCold: §b0❄",
            "§fNearby Players: §a0",
            "",
            "§ewww.hypixel.net");
        List<String> filled = new ArrayList<>();
        for (String l : lines) filled.add(plugin.fill(player, l));
        Sidebar.show(player, "§e§lSKYBLOCK", filled);
    }

    private void showTab(Player player, Session s) {
        List<String> info = new ArrayList<>(List.of(
            "§b§lArea: §7Mineshaft",
            " Server: §8{server}",
            "",
            "§e§lProfile: §aBanana",
            ""));
        if (!s.corpses.isEmpty()) {
            info.add("§b§lFrozen Corpses:");
            for (Corpse c : s.corpses) {
                String name = c.type.substring(0, 1).toUpperCase(Locale.ROOT) + c.type.substring(1);
                info.add(" " + COLOR.get(c.type) + name + "§f: " + (c.looted ? "§a§lLOOTED" : "§c§lNOT LOOTED"));
            }
        }
        List<List<String>> columns = List.of(
            List.of("§a§lPlayers §f(1)", "§8[§9285§8] §b{player}"),
            info,
            List.of("§e§lPet:", " §7[Lvl 100] §6Glacite Golem"),
            List.of());
        TabList.show(player, plugin.state(player), "\n§bYou are playing on §e§lMC.HYPIXEL.NET\n",
            "\n§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET", columns, x -> plugin.fill(player, x));
    }
}
