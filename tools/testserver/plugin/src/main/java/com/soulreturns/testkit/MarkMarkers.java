package com.soulreturns.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * {@code /hx marks show <file|latest> [filter…]|list|tp <n>|clear}: marks from a dev bridge
 * {@code marks.ndjson} or a spots JSON array (docs/research/f7-boss-spots-*.json) as labeled
 * markers in the sender's world: a text display with the label, a small block at the position and
 * a thin line from the eye along the facing. {@code latest} = the newest file in
 * {@code plugins/SoulTestKit/marks/} ({@code tools/testserver/s marks install FILE}).
 */
final class MarkMarkers {
    static final String TAG = "soul_mark";
    private static final int LIST_LIMIT = 25;

    private final SoulTestKit plugin;
    private final Path dir;
    private List<Mark> shown = new ArrayList<>();

    private record Mark(int n, int source, String label, String blob, double x, double y, double z, float yaw, float pitch) {}

    MarkMarkers(SoulTestKit plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("marks");
    }

    Path dir() {
        return dir;
    }

    void command(CommandSender sender, Player player, String[] rest) {
        String usage = "marks <show|add <file|latest> [filter…]|list|tp <n>|clear>";
        if (rest.length == 0) throw new IllegalArgumentException(usage);
        switch (rest[0].toLowerCase(Locale.ROOT)) {
            case "show", "add" -> {
                boolean add = rest[0].equalsIgnoreCase("add");
                if (rest.length < 2) throw new IllegalArgumentException("marks " + rest[0] + " <file|latest> [filter…]");
                if (player == null) throw new IllegalArgumentException("marks " + rest[0] + " needs a player (the markers appear in their world)");
                show(sender, player, rest[1], String.join(" ", java.util.Arrays.copyOfRange(rest, 2, rest.length)), add);
            }
            case "list" -> list(sender);
            case "tp" -> {
                if (player == null) throw new IllegalArgumentException("marks tp needs a player");
                if (rest.length < 2) throw new IllegalArgumentException("marks tp <n>");
                int n = Integer.parseInt(rest[1]);
                Mark m = shown.stream().filter(k -> k.n == n).findFirst().orElseThrow(() -> new IllegalArgumentException("no mark #" + n + " in the last `marks show` (marks list)"));
                player.setAllowFlight(true);
                player.teleport(new Location(player.getWorld(), m.x, m.y, m.z, m.yaw, m.pitch));
                sender.sendMessage("§a" + player.getName() + " → mark #" + n + " " + m.label + " at " + String.format(Locale.ROOT, "%.2f %.2f %.2f", m.x, m.y, m.z));
            }
            case "clear" -> sender.sendMessage("§a" + clear() + " marker entities removed");
            default -> throw new IllegalArgumentException(usage);
        }
    }

    private Path resolve(String arg) {
        if (arg.equalsIgnoreCase("latest")) {
            try (Stream<Path> files = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
                return files.filter(Files::isRegularFile).max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalArgumentException("no marks file in " + dir + " (tools/testserver/s marks install FILE)"));
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }
        for (Path p : List.of(Path.of(arg), dir.resolve(arg))) if (Files.isRegularFile(p)) return p;
        throw new IllegalArgumentException("no marks file " + arg + " (an absolute path, or a file in " + dir + ")");
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static List<Mark> parse(Path file) throws IOException {
        String content = Files.readString(file).strip();
        List<JsonObject> raw = new ArrayList<>();
        if (content.startsWith("[")) {
            for (JsonElement e : JsonParser.parseString(content).getAsJsonArray()) raw.add(e.getAsJsonObject());
        } else {
            for (String line : content.split("\n")) {
                if (line.isBlank()) continue;
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                if (o.has("kind")) {
                    if (!text(o, "kind").equals("mark")) continue;
                    o = o.getAsJsonObject("data");
                }
                raw.add(o);
            }
        }
        List<Mark> out = new ArrayList<>();
        for (JsonObject o : raw) {
            if (!o.has("pos") || !o.get("pos").isJsonArray()) continue;
            JsonArray pos = o.getAsJsonArray("pos");
            String label = !text(o, "name").isEmpty() ? text(o, "name") : text(o, "label");
            String blob = String.join(" ", text(o, "label"), text(o, "name"), text(o, "phase"), text(o, "section"), o.has("classes") ? o.get("classes").toString() : "");
            out.add(new Mark(out.size() + 1, o.has("n") && o.get("n").isJsonPrimitive() ? o.get("n").getAsInt() : out.size() + 1, label.isEmpty() ? "mark " + text(o, "n") : label, blob.toLowerCase(Locale.ROOT), pos.get(0).getAsDouble(), pos.get(1).getAsDouble(), pos.get(2).getAsDouble(),
                o.has("yaw") ? o.get("yaw").getAsFloat() : 0f, o.has("pitch") ? o.get("pitch").getAsFloat() : 0f));
        }
        return out;
    }

    private void show(CommandSender sender, Player player, String arg, String filter, boolean add) {
        Path file = resolve(arg);
        List<Mark> all;
        try {
            all = parse(file);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage());
        }
        String[] words = filter.toLowerCase(Locale.ROOT).split("\\s+");
        List<Mark> picked = new ArrayList<>();
        for (Mark m : all) {
            boolean ok = true;
            for (String w : words) {
                if (w.isEmpty()) continue;
                // n>=85 keeps marks numbered 85 and up in the source file (the dev bridge's own mark numbers)
                if (w.startsWith("n>=")) ok &= m.source >= Integer.parseInt(w.substring(3));
                else if (!m.blob.contains(w)) ok = false;
            }
            if (ok) picked.add(new Mark((add ? shown.size() : 0) + picked.size() + 1, m.source, m.label, m.blob, m.x, m.y, m.z, m.yaw, m.pitch));
        }
        if (add) {
            List<Mark> both = new ArrayList<>(shown);
            both.addAll(picked);
            shown = both;
        } else {
            clear();
            shown = picked;
        }
        World world = player.getWorld();
        for (Mark m : picked) render(world, m);
        sender.sendMessage("§a" + picked.size() + " of " + all.size() + " marks from " + file.getFileName() + (add ? " added in " : " shown in ") + world.getName() + (filter.isBlank() ? "" : " (filter: " + filter + ")") + "; §7/hx marks tp <n>, /hx marks clear");
        list(sender);
    }

    private void list(CommandSender sender) {
        int i = 0;
        for (Mark m : shown) {
            if (i++ == LIST_LIMIT) {
                sender.sendMessage("§7… " + (shown.size() - LIST_LIMIT) + " more (narrow with a filter)");
                break;
            }
            sender.sendMessage(String.format(Locale.ROOT, "§e%d §f%s §7%.1f %.1f %.1f", m.n, m.label, m.x, m.y, m.z));
        }
        if (shown.isEmpty()) sender.sendMessage("§7no marks shown");
    }

    private void render(World world, Mark m) {
        world.spawn(new Location(world, m.x, m.y + 2.3, m.z), TextDisplay.class, t -> {
            t.text(Component.text("#" + m.n + " ", NamedTextColor.YELLOW).append(Component.text(m.label, NamedTextColor.WHITE)));
            t.setBillboard(Display.Billboard.CENTER);
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(2f, 2f, 2f), new Quaternionf()));
            t.setSeeThrough(true);
            t.setViewRange(4f);
            t.setPersistent(false);
            t.addScoreboardTag(TAG);
        });
        world.spawn(new Location(world, m.x, m.y, m.z), BlockDisplay.class, b -> {
            b.setBlock(Material.GOLD_BLOCK.createBlockData());
            b.setTransformation(new Transformation(new Vector3f(-0.15f, 0f, -0.15f), new Quaternionf(), new Vector3f(0.3f, 0.3f, 0.3f), new Quaternionf()));
            b.setPersistent(false);
            b.addScoreboardTag(TAG);
        });
        // Minecraft yaw 0 faces +Z; a positive pitch looks down.
        Quaternionf q = new Quaternionf().rotationYXZ((float) Math.toRadians(-m.yaw), (float) Math.toRadians(m.pitch), 0f);
        Vector3f shift = new Vector3f(-0.025f, -0.025f, 0f).rotate(q);
        world.spawn(new Location(world, m.x, m.y + 1.62, m.z), BlockDisplay.class, b -> {
            b.setBlock(Material.RED_CONCRETE.createBlockData());
            b.setTransformation(new Transformation(shift, q, new Vector3f(0.06f, 0.06f, 1.5f), new Quaternionf()));
            b.setPersistent(false);
            b.addScoreboardTag(TAG);
        });
    }

    int clear() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(TAG)) {
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }
}
