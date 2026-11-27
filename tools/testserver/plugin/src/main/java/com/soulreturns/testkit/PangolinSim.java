package com.soulreturns.testkit;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Armadillo;
import org.bukkit.entity.Player;

/**
 * Torrhus Canyon's Pangolin for the mob waypoints (rule://waypoints, capture 2026-11-27): a plain
 * {@code minecraft:armadillo} named "Armadillo", no name-tag stand, its state in entity data 18
 * (IDLE, ROLLING, SCARED, UNROLLING). Here the armadillos have no AI, so a state stays until
 * {@code pangolin roll} changes it. {@code catch} plays Hypixel's lasso catch as the client sees it:
 * {@code You caught x2 Pangolin Shards!} and the entity removed in the same tick. {@code share} is a
 * party member's waypoint line in party chat.
 */
public final class PangolinSim {
    private static final List<String> STATES = List.of("idle", "rolling", "scared", "unrolling");
    /** {@code pangolin start}: the armadillos stand this far east of the player. */
    private static final double AHEAD = 10.0;
    /** {@code pangolin wall}: stone this far west of the armadillo (towards the player). */
    private static final int WALL_OFFSET = 3;

    private static final class Session {
        final List<Armadillo> mobs = new ArrayList<>();
        final Map<Integer, List<Location>> walls = new HashMap<>();
    }

    private final SoulTestKit plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public PangolinSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    public List<String> completions(int pos) {
        return pos == 1 ? List.of("start", "roll", "wall", "move", "catch", "share", "status", "stop") : List.of();
    }

    private static String usage() {
        return "pangolin <start [n] [wall]|roll <i|all> <idle|rolling|scared|unrolling>|wall <i> [on|off]|move <i> <dx> <dy> <dz>|catch <i>"
            + "|share <name> <i>|share <name> <dx> <dy> <dz>|status|stop>";
    }

    public List<String> command(Player player, String[] rest) {
        if (rest.length == 0) throw new IllegalArgumentException(usage());
        return switch (rest[0].toLowerCase(Locale.ROOT)) {
            case "start" -> start(player, rest.length > 1 ? Integer.parseInt(rest[1]) : 2, rest.length > 2 && rest[2].equalsIgnoreCase("wall"));
            case "roll" -> {
                if (rest.length < 3) throw new IllegalArgumentException("pangolin roll <i|all> <idle|rolling|scared|unrolling>");
                yield List.of(roll(player, rest[1], rest[2].toLowerCase(Locale.ROOT)));
            }
            case "wall" -> {
                if (rest.length < 2) throw new IllegalArgumentException("pangolin wall <i> [on|off]");
                yield List.of(wall(player, Integer.parseInt(rest[1]), rest.length < 3 || rest[2].equalsIgnoreCase("on")));
            }
            case "move" -> {
                if (rest.length < 5) throw new IllegalArgumentException("pangolin move <i> <dx> <dy> <dz>");
                yield List.of(move(player, Integer.parseInt(rest[1]), Double.parseDouble(rest[2]), Double.parseDouble(rest[3]), Double.parseDouble(rest[4])));
            }
            case "catch" -> {
                if (rest.length < 2) throw new IllegalArgumentException("pangolin catch <i>");
                yield List.of(caught(player, Integer.parseInt(rest[1])));
            }
            case "share" -> {
                if (rest.length != 3 && rest.length != 5) throw new IllegalArgumentException("pangolin share <name> <i> | share <name> <dx> <dy> <dz>");
                yield List.of(share(player, rest[1], java.util.Arrays.copyOfRange(rest, 2, rest.length)));
            }
            case "status" -> status(player);
            case "stop" -> List.of(stop(player) ? "pangolins removed" : "no pangolins running");
            default -> throw new IllegalArgumentException(usage());
        };
    }

    /**
     * Torrhus Canyon location, sidebar and tab; n armadillos 10 blocks east of the player, 6 apart; the player
     * faces them. With {@code walled}, each stands behind its wall from the same tick (never in sight).
     */
    private List<String> start(Player player, int n, boolean walled) {
        if (n < 0 || n > 8) throw new IllegalArgumentException("0-8 pangolins");
        stop(player);
        Session s = new Session();
        JsonObject location = new JsonObject();
        location.addProperty("serverName", "mini148DJ");
        location.addProperty("serverType", "SKYBLOCK");
        location.addProperty("mode", "foraging_3");
        location.addProperty("map", "Torrhus Canyon");
        plugin.clearSidebar(player);
        plugin.clearTab(player);
        plugin.setLocation(player, location);
        showSidebar(player);
        showTab(player);
        World world = player.getWorld();
        Location at = player.getLocation();
        Location face = at.clone();
        face.setYaw(-90f);
        face.setPitch(10f);
        player.teleport(face);
        for (int i = 0; i < n; i++) {
            double x = at.getX() + AHEAD;
            double z = at.getZ() + (i - (n - 1) / 2.0) * 6.0;
            int y = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            Armadillo mob = world.spawn(new Location(world, x, y, z), Armadillo.class, a -> {
                a.setAI(false);
                a.setSilent(true);
                a.setInvulnerable(true);
                a.setPersistent(true);
                a.setRemoveWhenFarAway(false);
            });
            s.mobs.add(mob);
        }
        sessions.put(player.getUniqueId(), s);
        if (walled) for (int i = 0; i < n; i++) wall(player, i, true);
        List<String> out = new ArrayList<>();
        out.add("Torrhus Canyon, " + n + " pangolin(s)");
        out.addAll(status(player));
        return out;
    }

    private Session session(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) throw new IllegalArgumentException("no pangolins: pangolin start first");
        return s;
    }

    private Armadillo mob(Player player, int i) {
        Session s = session(player);
        if (i < 0 || i >= s.mobs.size() || !s.mobs.get(i).isValid()) throw new IllegalArgumentException("no pangolin " + i);
        return s.mobs.get(i);
    }

    private String roll(Player player, String which, String state) {
        if (!STATES.contains(state)) throw new IllegalArgumentException("state " + state + " (" + String.join(", ", STATES) + ")");
        List<Armadillo> targets = new ArrayList<>();
        if (which.equalsIgnoreCase("all")) {
            for (Armadillo a : session(player).mobs) if (a.isValid()) targets.add(a);
        } else targets.add(mob(player, Integer.parseInt(which)));
        for (Armadillo a : targets) Nms.armadilloState(a, state.toUpperCase(Locale.ROOT));
        return targets.size() + " pangolin(s) " + state.toUpperCase(Locale.ROOT);
    }

    /** A 5x5 stone wall WALL_OFFSET blocks west of pangolin i (between it and a player facing east from the west). */
    private String wall(Player player, int i, boolean on) {
        Session s = session(player);
        List<Location> old = s.walls.remove(i);
        if (old != null) for (Location l : old) l.getBlock().setType(Material.AIR, false);
        if (!on) return "wall " + i + " removed";
        Location m = mob(player, i).getLocation();
        List<Location> blocks = new ArrayList<>();
        int x = m.getBlockX() - WALL_OFFSET;
        for (int dy = -1; dy <= 3; dy++) {
            for (int dz = -2; dz <= 2; dz++) {
                Location l = new Location(m.getWorld(), x, m.getBlockY() + dy, m.getBlockZ() + dz);
                if (!l.getBlock().getType().isAir()) continue;
                l.getBlock().setType(Material.STONE, false);
                blocks.add(l);
            }
        }
        s.walls.put(i, blocks);
        return "wall " + i + " at x " + x + " (" + blocks.size() + " blocks)";
    }

    private String move(Player player, int i, double dx, double dy, double dz) {
        Armadillo a = mob(player, i);
        Location l = a.getLocation();
        Nms.setPos(a, l.getX() + dx, l.getY() + dy, l.getZ() + dz);
        return "pangolin " + i + " at " + fmt(a.getLocation());
    }

    /** Hypixel's lasso catch as recorded: the shard line and the entity removed in the same tick. */
    private String caught(Player player, int i) {
        Armadillo a = mob(player, i);
        Nms.sendSystemChat(player, Txt.of("§r§aYou caught §7x2 §9Pangolin §aShards§a!"), false);
        a.remove();
        return "pangolin " + i + " caught";
    }

    /** {@code §9Party §8> §b[MVP§c+§b] <name>§f: x: X, y: Y, z: Z | Pangolin}, at pangolin i or the player + offset. */
    private String share(Player player, String name, String[] where) {
        Location l;
        if (where.length == 1) l = mob(player, Integer.parseInt(where[0])).getLocation();
        else l = player.getLocation().add(Double.parseDouble(where[0]), Double.parseDouble(where[1]), Double.parseDouble(where[2]));
        String body = "x: " + Math.round(l.getX()) + ", y: " + Math.round(l.getY()) + ", z: " + Math.round(l.getZ()) + " | Pangolin";
        Nms.sendSystemChat(player, Txt.of("§9Party §8> §b[MVP§c+§b] " + name + "§f: " + body), false);
        plugin.getLogger().info("[pangolin] " + name + " shared " + body);
        return name + " shared " + body;
    }

    private List<String> status(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return List.of("no pangolins");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.mobs.size(); i++) {
            Armadillo a = s.mobs.get(i);
            out.add("#" + i + (a.isValid() ? " id " + a.getEntityId() + " " + a.getState() + " at " + fmt(a.getLocation()) + String.format(Locale.ROOT, " (%.1f away)", a.getLocation().distance(player.getLocation())) : " gone"));
        }
        return out;
    }

    private boolean stop(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) return false;
        for (Armadillo a : s.mobs) if (a.isValid()) a.remove();
        for (List<Location> wall : s.walls.values()) for (Location l : wall) l.getBlock().setType(Material.AIR, false);
        return true;
    }

    public void disable() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) stop(p);
        }
    }

    private static String fmt(Location l) {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", l.getX(), l.getY(), l.getZ());
    }

    private void showSidebar(Player player) {
        List<String> lines = List.of(
            "§7{date} §8{serverShort}",
            "",
            " Autumn 10th",
            " §75:00pm §e☀",
            " §7⏣ §6Torrhus Canyon",
            "",
            "§fPurse: §62,161,505",
            "",
            "§ewww.hypixel.net");
        List<String> filled = new ArrayList<>();
        for (String l : lines) filled.add(plugin.fill(player, l));
        Sidebar.show(player, "§e§lSKYBLOCK", filled);
    }

    private void showTab(Player player) {
        List<List<String>> columns = List.of(
            List.of("§a§lPlayers §f(1)", "§8[§9285§8] §b{player}"),
            List.of("§b§lArea: §7Torrhus Canyon", " Server: §8{server}", "", "§e§lProfile: §aStrawberry", ""),
            List.of("§e§lPet:", " §7[Lvl 100] §6Elephant"),
            List.of());
        TabList.show(player, plugin.state(player), "\n§bYou are playing on §e§lMC.HYPIXEL.NET\n",
            "\n§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET", columns, x -> plugin.fill(player, x));
    }
}
