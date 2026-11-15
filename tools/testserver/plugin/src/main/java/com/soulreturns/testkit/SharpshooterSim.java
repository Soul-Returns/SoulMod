package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.sounds.SoundSource;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Floor 7 Sharpshooter ({@code /hx sharp}) at its real place in a loaded {@code f7-boss} capture, from
 * {@code docs/research/f7-devices-2026-11-12.md} §12: the 3x3 target grid (blue terracotta at x 64/66/68, y 130/128/126,
 * z 50, magenta panes between) shot from the pressure plate at (63, 127, 35).
 *
 * <p>Measured (7 recorded runs): the run starts when the plate has weight (power 1 or 2); 0 to 10 ticks later one
 * target turns into an emerald block; an arrow hit turns it back to blue terracotta and the next target (a cell not
 * used before in the run) appears 0 to 10 ticks later; there are 7 or 8 targets; the last hit plays
 * {@code block.note_block.pling} (volume 8, pitch 4.047619) at the player and sends {@code completed a device! (n/7)} in the
 * same tick; the plate is free about 20 ticks later (the player stepping off).
 *
 * <p>Assumed (no recording shows it): leaving the plate mid-run stops the run and clears the target; arrows hitting
 * anything but the current target do nothing; a finished device does not start again until the plate was free.
 */
final class SharpshooterSim implements Listener {
    static final int X0 = 64;
    static final int Y_TOP = 130;
    static final int Z = 50;
    static final int SIZE = 9;
    static final int PLATE_X = 63;
    static final int PLATE_Y = 127;
    static final int PLATE_Z = 35;
    private static final String PLING = "minecraft:block.note_block.pling";

    private enum State { IDLE, RUNNING, FINISHED }

    private final SoulTestKit plugin;
    private World world;
    private Player player;
    private boolean active;
    private State state = State.IDLE;
    private boolean armed;
    private int total;
    private int[] order = new int[0];
    private int shown;
    private int cur = -1;
    private long nextAt;
    private int counter = 2;
    private long now;
    private final Random rng = new Random();
    private BukkitTask task;

    /** The plate's power for the selftest (-1 = read the block). */
    int plateOverride = -1;

    /** Everything the device did, for the selftest and {@code status}: "tick kind detail". */
    final List<String> events = new ArrayList<>();

    SharpshooterSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    static int x(int i) {
        return X0 + 2 * (i % 3);
    }

    static int y(int i) {
        return Y_TOP - 2 * (i / 3);
    }

    static String name(int i) {
        return "r" + (i / 3 + 1) + "c" + (i % 3 + 1);
    }

    static int index(int x, int y) {
        int dx = x - X0;
        int dy = Y_TOP - y;
        if (dx < 0 || dy < 0 || dx > 4 || dy > 4 || dx % 2 != 0 || dy % 2 != 0) return -1;
        return dy / 2 * 3 + dx / 2;
    }

    boolean running() {
        return state == State.RUNNING;
    }

    boolean finished() {
        return state == State.FINISHED;
    }

    int current() {
        return cur;
    }

    int total() {
        return total;
    }

    int[] order() {
        return order;
    }

    Block cell(int i) {
        return world.getBlockAt(x(i), y(i), Z);
    }

    private void log(String kind, String detail) {
        String line = now + " " + kind + " " + detail;
        events.add(line);
        plugin.getLogger().info("[sharp] tick=" + line);
    }

    int plate() {
        if (plateOverride >= 0) return plateOverride;
        Block b = world.getBlockAt(PLATE_X, PLATE_Y, PLATE_Z);
        return b.getType() == Material.LIGHT_WEIGHTED_PRESSURE_PLATE ? ((AnaloguePowerable) b.getBlockData()).getPower() : 0;
    }

    // ---- the device ----

    void reset(World w, Player p) {
        world = w;
        player = p;
        now = w.getGameTime();
        for (int i = 0; i < SIZE; i++) cell(i).setType(Material.BLUE_TERRACOTTA, false);
        state = State.IDLE;
        cur = -1;
        shown = 0;
        total = 0;
        events.clear();
        armed = plate() == 0;
        active = true;
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(world.getGameTime()), 1, 1);
        log("reset", "armed=" + armed);
    }

    void stop() {
        active = false;
        if (task != null) task.cancel();
        task = null;
    }

    /** Removes the emerald target (cleanup after tests). */
    void clear() {
        stop();
        if (world != null) for (int i = 0; i < SIZE; i++) cell(i).setType(Material.BLUE_TERRACOTTA, false);
        state = State.IDLE;
        cur = -1;
        plateOverride = -1;
    }

    /** One server tick of the device. */
    void tick(long t) {
        now = t;
        int p = plate();
        switch (state) {
            case IDLE -> {
                if (p == 0) armed = true;
                else if (armed) begin();
            }
            case RUNNING -> {
                if (p == 0) {
                    // Assumed: the device only runs with weight on the plate.
                    if (cur >= 0) cell(cur).setType(Material.BLUE_TERRACOTTA, false);
                    log("abort", "plate free with " + shown + " of " + total + " targets shown");
                    cur = -1;
                    state = State.IDLE;
                    armed = true;
                } else if (cur < 0 && now >= nextAt) {
                    showNext();
                }
            }
            case FINISHED -> {
                if (p == 0) {
                    state = State.IDLE;
                    armed = true;
                    log("free", "plate free after the run");
                }
            }
        }
    }

    private void begin() {
        total = 7 + rng.nextInt(2);
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) cells.add(i);
        Collections.shuffle(cells, rng);
        order = new int[total];
        for (int i = 0; i < total; i++) order[i] = cells.get(i);
        shown = 0;
        cur = -1;
        nextAt = now + rng.nextInt(11);
        armed = false;
        state = State.RUNNING;
        log("begin", total + " targets");
    }

    private void showNext() {
        cur = order[shown++];
        cell(cur).setType(Material.EMERALD_BLOCK, false);
        log("target", name(cur) + " (" + shown + "/" + total + ")");
    }

    /** An arrow hit on the block of cell {@code i}. */
    void hit(long t, int i) {
        now = t;
        if (state != State.RUNNING || cur < 0) {
            log("ignored", name(i) + " (no target)");
            return;
        }
        if (i != cur) {
            log("ignored", name(i) + " (not the target " + name(cur) + ")");
            return;
        }
        cell(cur).setType(Material.BLUE_TERRACOTTA, false);
        log("hit", name(cur));
        cur = -1;
        if (shown == total) {
            state = State.FINISHED;
            complete();
        } else {
            nextAt = now + rng.nextInt(11);
        }
    }

    private void complete() {
        if (player != null && player.isOnline()) {
            Terminals.sound(player, PLING, SoundSource.BLOCKS, 8.0f, 4.047619f);
            counter = counter >= 7 ? 1 : counter + 1;
            String line = "§r§6" + player.getName() + "§r§a completed a device! (§r§c" + counter + "§r§a/7)";
            Nms.sendSystemChat(player, Txt.of(Terminals.wire(line)), false);
            log("complete", Txt.strip(line));
        } else {
            log("complete", "");
        }
    }

    // ---- live ----

    @EventHandler
    public void onHit(ProjectileHitEvent event) {
        if (!active || world == null || !(event.getEntity() instanceof AbstractArrow) || !event.getEntity().getWorld().equals(world)) return;
        Block b = event.getHitBlock();
        if (b == null || b.getZ() != Z) return;
        int i = index(b.getX(), b.getY());
        if (i >= 0) hit(world.getGameTime(), i);
    }

    // ---- /hx sharp ----

    static final String USAGE = "sharp <start|reset|status|hit [cell]|stop|selftest>";

    List<String> command(Player viewer, String[] args, CommandSender sender) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start", "reset" -> {
                World w = viewer.getWorld();
                if (!w.getName().startsWith("soulcap_f7-boss")) {
                    throw new IllegalArgumentException("stand in the f7-boss capture first: hx world load f7-boss entry (you are in " + w.getName() + ")");
                }
                if (!sub.equals("reset")) {
                    plugin.applyLocation(viewer, "dungeon");
                    plugin.applySidebar(viewer, "dungeon");
                    plugin.applyTab(viewer, "dungeon");
                    plugin.applyActionbar(viewer, "dungeon");
                }
                reset(w, viewer);
                return List.of("sharpshooter ready: stand on the plate at 63 127 35 (hx world tp ... 63.5 127 35.5); the targets appear one at a time, shoot them (or hx sharp hit)");
            }
            case "hit" -> {
                if (world == null) throw new IllegalArgumentException("hx sharp start first");
                int i = cur;
                if (args.length > 1) {
                    i = -1;
                    for (int k = 0; k < SIZE; k++) if (name(k).equals(args[1].toLowerCase(Locale.ROOT))) i = k;
                    if (i < 0) throw new IllegalArgumentException("cell r1c1..r3c3");
                }
                if (i < 0) throw new IllegalArgumentException("no target now");
                player = viewer;
                hit(world.getGameTime(), i);
                return List.of("hit " + name(i));
            }
            case "status" -> {
                if (world == null) return List.of("sharpshooter not started (hx sharp start)");
                List<String> out = new ArrayList<>();
                out.add("state " + state + ", target " + (cur < 0 ? "-" : name(cur)) + ", " + shown + "/" + total + " shown, plate power " + plate());
                out.addAll(events.subList(Math.max(0, events.size() - 8), events.size()));
                return out;
            }
            case "stop" -> {
                stop();
                return List.of("sharpshooter stopped (the targets stay as they are)");
            }
            case "selftest" -> {
                World w = viewer.getWorld();
                if (!w.getName().startsWith("soulcap_f7-boss")) throw new IllegalArgumentException("stand in the f7-boss capture first");
                stop();
                String result = new SharpshooterSelfTest(this, w, viewer).run();
                reset(w, viewer);
                return List.of(result);
            }
            default -> throw new IllegalArgumentException("usage: /hx " + USAGE);
        }
    }

    List<String> completions(int pos) {
        return pos == 1 ? List.of("start", "reset", "hit", "status", "stop", "selftest") : List.of();
    }

    long now() {
        return now;
    }
}
