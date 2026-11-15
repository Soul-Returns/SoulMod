package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.sounds.SoundSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

/**
 * Floor 7 Lights ({@code /hx lights}) at its real place in a loaded {@code f7-boss} capture, from
 * {@code docs/research/f7-devices-2026-11-12.md} §10: 5x4 {@code lever[face=wall,facing=north]} at x 58-62,
 * y 133-136, z 142, a {@code redstone_lamp} behind each at z 143, every lever off and every lamp dark at the start.
 *
 * <p>Measured (7 recorded runs): a right click on a lever turns it on (and plays {@code block.lever.click}, 0.3,
 * 0.587 at the lever), and the next tick its own lamp and the orthogonal neighbours' lamps are lit; a lamp is never
 * darkened; when all 20 lamps are lit the device plays {@code block.note_block.pling} (volume 8, 4.047619) at the
 * player and sends {@code completed a device! (n/7)}. The six levers (62,136) (58,136) (60,135) (60,134) (62,133)
 * (58,133) are the smallest set that lights everything and the only one any run used.
 *
 * <p>Stated by the user, in no recording yet: a lamp lit by two levers stays lit; a lever outside the six flips like
 * any other (other covers of the 20 lamps are legitimate).
 *
 * <p>M7 party recordings (2026-11-12, 5 of 5): a device lit in Storm's phase keeps its levers on and every lamp lit
 * and does not complete; touching a lever that is not on yet in Goldor's phase completes it in the same tick
 * ({@code /hx lights prefinish}: the usual six on, every lamp lit).
 *
 * <p>Assumed (no source shows it): a click on a lever that is already on turns it off and darkens the lamps no
 * other lever covers (a lamp is lit while some lever at it or next to it is on); nothing resets the device and it
 * does nothing once complete.
 */
final class LightsSim implements Listener {
    static final int X_RIGHT = 62;
    static final int Y_TOP = 136;
    static final int COLS = 5;
    static final int ROWS = 4;
    static final int LEVER_Z = 142;
    static final int LAMP_Z = 143;
    static final int[] SOLUTION = {index(62, 136), index(58, 136), index(60, 135), index(60, 134), index(62, 133), index(58, 133)};
    private static final String PLING = "minecraft:block.note_block.pling";

    private static final BlockData LEVER_ON = Bukkit.createBlockData("minecraft:lever[face=wall,facing=north,powered=true]");
    private static final BlockData LEVER_OFF = Bukkit.createBlockData("minecraft:lever[face=wall,facing=north,powered=false]");
    private static final BlockData LAMP_ON = Bukkit.createBlockData("minecraft:redstone_lamp[lit=true]");
    private static final BlockData LAMP_OFF = Bukkit.createBlockData("minecraft:redstone_lamp[lit=false]");

    private final SoulTestKit plugin;
    private World world;
    private Player player;
    private BukkitTask task;
    private boolean done;
    private int counter = 2;
    private final boolean[] on = new boolean[COLS * ROWS];
    private final boolean[] lit = new boolean[COLS * ROWS];
    /** Flips whose lamps come on the next tick: {tick, cell}. */
    private final List<long[]> pendingLamps = new ArrayList<>();
    private long now;

    /** Everything the device did, for the selftest and {@code status}: "tick kind detail". */
    final List<String> events = new ArrayList<>();

    LightsSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    GoldorStartSim goldor() {
        return plugin.goldor();
    }

    static int index(int x, int y) {
        return (Y_TOP - y) * COLS + (X_RIGHT - x);
    }

    static int x(int i) {
        return X_RIGHT - i % COLS;
    }

    static int y(int i) {
        return Y_TOP - i / COLS;
    }

    static String name(int i) {
        return "r" + (i / COLS + 1) + "c" + (i % COLS + 1);
    }

    /** The cells whose lamps a flip of {@code i} lights: its own and the orthogonal neighbours inside the grid. */
    static List<Integer> plus(int i) {
        List<Integer> out = new ArrayList<>();
        out.add(i);
        if (i % COLS > 0) out.add(i - 1);
        if (i % COLS < COLS - 1) out.add(i + 1);
        if (i / COLS > 0) out.add(i - COLS);
        if (i / COLS < ROWS - 1) out.add(i + COLS);
        return out;
    }

    // ---- world helpers ----

    boolean lever(int i) {
        return world.getBlockAt(x(i), y(i), LEVER_Z).getBlockData().getAsString().contains("powered=true");
    }

    boolean lamp(int i) {
        return world.getBlockAt(x(i), y(i), LAMP_Z).getBlockData().getAsString().contains("lit=true");
    }

    private void setLever(int i, boolean value) {
        on[i] = value;
        world.getBlockAt(x(i), y(i), LEVER_Z).setBlockData(value ? LEVER_ON : LEVER_OFF, false);
    }

    private void setLamp(int i, boolean value) {
        lit[i] = value;
        world.getBlockAt(x(i), y(i), LAMP_Z).setBlockData(value ? LAMP_ON : LAMP_OFF, false);
    }

    private void log(String kind, String detail) {
        String line = now + " " + kind + " " + detail;
        events.add(line);
        plugin.getLogger().info("[lights] tick=" + line);
    }

    // ---- the device ----

    /** Every lever off, every lamp dark ({@code finishedEarly}: the usual six on and every lamp lit, as after Storm's phase). */
    void reset(World w, Player p, boolean finishedEarly) {
        world = w;
        player = p;
        pendingLamps.clear();
        done = false;
        now = w.getGameTime();
        for (int i = 0; i < COLS * ROWS; i++) {
            setLever(i, false);
            setLamp(i, finishedEarly);
        }
        if (finishedEarly) for (int i : SOLUTION) setLever(i, true);
        events.clear();
        log("reset", finishedEarly ? "pre-finished" : "");
    }

    void reset(World w, Player p) {
        reset(w, p, false);
    }

    /** A right click on lever {@code i} at {@code tick}. */
    void flip(long tick, int i) {
        step(tick);
        log("flip", name(i));
        if (done) return;
        if (on[i]) {
            // A second flick turns it off; a lamp stays lit while another lever at it or beside it is on.
            setLever(i, false);
            world.playSound(new Location(world, x(i) + 0.5, y(i) + 0.5, LEVER_Z + 0.5), "minecraft:block.lever.click", SoundCategory.BLOCKS, 0.3f, 0.5873016f);
            pendingLamps.add(new long[] {tick + 1, -1 - i});
            return;
        }
        setLever(i, true);
        world.playSound(new Location(world, x(i) + 0.5, y(i) + 0.5, LEVER_Z + 0.5), "minecraft:block.lever.click", SoundCategory.BLOCKS, 0.3f, 0.5873016f);
        pendingLamps.add(new long[] {tick + 1, i});
    }

    /** Lights the lamps of every flip due by {@code tick}. */
    void step(long tick) {
        for (var it = pendingLamps.iterator(); it.hasNext(); ) {
            long[] p = it.next();
            if (p[0] > tick) continue;
            it.remove();
            now = p[0];
            if (p[1] < 0) {
                int off = (int) (-1 - p[1]);
                int dark = 0;
                for (int c : plus(off)) {
                    if (lit[c] && !covered(c)) {
                        setLamp(c, false);
                        dark++;
                    }
                }
                log("lamps", name(off) + " turned off, " + dark + " lamps dark");
                continue;
            }
            int lamps = 0;
            for (int c : plus((int) p[1])) {
                if (!lit[c]) {
                    setLamp(c, true);
                    lamps++;
                }
            }
            log("lamps", name((int) p[1]) + " lit " + lamps + " new");
            // Lit before Goldor's phase it stays pre-finished: the first touch from T on completes it.
            if (allLit() && plugin.goldor().accepts()) complete();
        }
        now = tick;
    }

    /** Some lever at lamp {@code c} or beside it is on. */
    private boolean covered(int c) {
        for (int l : plus(c)) if (on[l]) return true;
        return false;
    }

    boolean allLit() {
        for (boolean b : lit) if (!b) return false;
        return true;
    }

    private void complete() {
        if (done) return;
        done = true;
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

    boolean done() {
        return done;
    }

    // ---- live ----

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (world == null || task == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block b = event.getClickedBlock();
        if (b == null || !b.getWorld().equals(world) || b.getType() != Material.LEVER) return;
        if (b.getZ() != LEVER_Z || b.getX() < X_RIGHT - COLS + 1 || b.getX() > X_RIGHT || b.getY() > Y_TOP || b.getY() < Y_TOP - ROWS + 1) return;
        // The server decides: the cancelled interact re-sends the lever, flip() sets it.
        event.setCancelled(true);
        player = event.getPlayer();
        touch(world.getGameTime(), index(b.getX(), b.getY()), player.isSneaking());
    }

    /**
     * A right click on a lever as Hypixel handles it: a sneaking click is ignored (measured: none of the 54 lever
     * clicks of the recordings was made while sneaking, though 90% of the frame clicks of the same runs were), and the
     * client gets the lever back unchanged.
     */
    void touch(long tick, int i, boolean sneaking) {
        now = tick;
        if (sneaking) {
            log("ignored", name(i) + " (sneaking)");
            return;
        }
        flip(tick, i);
    }

    private void ensureTask() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (world == null || Bukkit.getWorld(world.getName()) == null) {
                stop();
                return;
            }
            step(world.getGameTime());
        }, 1L, 1L);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        pendingLamps.clear();
    }

    // ---- /hx lights ----

    static final String USAGE = "lights <start|reset|prefinish|status|stop|selftest>";

    List<String> command(Player viewer, String[] args, CommandSender sender) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start", "reset", "prefinish" -> {
                World w = viewer.getWorld();
                if (!w.getName().startsWith("soulcap_f7-boss")) {
                    throw new IllegalArgumentException("stand in the f7-boss capture first: hx world load f7-boss entry; hx world tp f7-boss entry 60.5 132 138.5 (you are in " + w.getName() + ")");
                }
                if (!sub.equals("reset")) {
                    plugin.applyLocation(viewer, "dungeon");
                    plugin.applySidebar(viewer, "dungeon");
                    plugin.applyTab(viewer, "dungeon");
                    plugin.applyActionbar(viewer, "dungeon");
                }
                reset(w, viewer, sub.equals("prefinish"));
                ensureTask();
                return List.of(sub.equals("prefinish")
                    ? "lights pre-finished: the usual six levers on, every lamp lit, not complete; touch a lever that is not on to complete it"
                    : "lights ready: 20 levers at x 58-62, y 133-136, z 142 (the usual six: " + solutionNames() + ")");
            }
            case "status" -> {
                if (world == null) return List.of("lights not started (hx lights start)");
                List<String> out = new ArrayList<>();
                int levers = 0;
                int lamps = 0;
                for (int i = 0; i < COLS * ROWS; i++) {
                    if (on[i]) levers++;
                    if (lit[i]) lamps++;
                }
                out.add("levers on " + levers + "/20, lamps lit " + lamps + "/20, " + (done ? "done" : "not done") + ", running " + (task != null));
                int from = Math.max(0, events.size() - 10);
                out.addAll(events.subList(from, events.size()));
                return out;
            }
            case "stop" -> {
                stop();
                return List.of("lights stopped (blocks stay)");
            }
            case "selftest" -> {
                World w = viewer.getWorld();
                if (!w.getName().startsWith("soulcap_f7-boss")) throw new IllegalArgumentException("stand in the f7-boss capture first");
                stop();
                String result = new LightsSelfTest(this, w, viewer).run();
                reset(w, viewer);
                ensureTask();
                return List.of(result);
            }
            default -> throw new IllegalArgumentException("usage: /hx " + USAGE);
        }
    }

    private static String solutionNames() {
        StringBuilder b = new StringBuilder();
        for (int i : SOLUTION) b.append(b.isEmpty() ? "" : " ").append(name(i));
        return b.toString();
    }

    List<String> completions(int pos) {
        return pos == 1 ? List.of("start", "reset", "prefinish", "status", "stop", "selftest") : List.of();
    }

    long now() {
        return now;
    }
}
