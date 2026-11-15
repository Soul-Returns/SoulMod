package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.sounds.SoundSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Floor 7 Arrow Align ({@code /hx arrows}) at its real place in a loaded {@code f7-boss} capture, from
 * {@code docs/research/f7-devices-2026-11-12.md} §11: a 5x5 wall of item frames in the blocks x -2, y 120-124, z 75-79
 * facing east (cell {@code (124 - y) * 5 + (79 - z)}), the block behind each at x -3, one of the nine layouts
 * (arrows with a target rotation, a lime {@code Start} wool, a red {@code End} wool, gaps).
 *
 * <p>Measured (7 recorded runs): a right click on an arrow rotates it one step (45 degrees, rotation 0-7, +1 per
 * click, one {@code entity.item_frame.rotate_item} at the frame); the block behind it is a sea lantern exactly while
 * the arrow is at its target and a blue terracotta otherwise (behind the wool frames a lantern, always); all arrows at
 * their targets = {@code block.note_block.pling} (volume 8, pitch 4.047619) at the player and {@code completed a
 * device! (n/7)} in the same tick. Arrows start at random rotations.
 *
 * <p>Assumed (no recording shows it): a click on a wool frame does nothing; the finished device ignores clicks.
 */
final class ArrowAlignSim implements Listener {
    static final int FRAME_X = -2;
    static final int BACK_X = -3;
    static final int Y_TOP = 124;
    static final int Z_LEFT = 79;
    static final int COLS = 5;
    static final int SIZE = 25;
    private static final int X = -1;
    private static final int S = -2;
    private static final int E = -3;
    private static final String PLING = "minecraft:block.note_block.pling";

    /** The nine layouts, as ArrowAlignModel.LAYOUTS (Skyblocker / NoFrills): target rotation, S, E or X (no frame). */
    static final int[][] LAYOUTS = {
        {1, 1, 3, X, X, 7, X, 3, X, X, S, X, 3, X, E, X, X, 3, X, 7, X, X, 1, 1, 7},
        {1, 1, E, 5, 5, 7, X, X, X, 7, 7, 5, X, 1, 7, X, 7, X, 7, X, X, S, X, S, X},
        {3, 5, 5, X, X, 3, X, 7, X, X, E, X, S, X, E, X, X, 3, X, 7, X, X, 1, 1, 7},
        {S, X, X, X, S, 3, X, X, X, 3, 3, X, E, X, 3, 3, X, 7, X, 3, 1, 1, 7, 5, 5},
        {1, 1, 1, 1, 3, 7, X, X, X, 3, 7, X, E, X, 3, 7, X, 7, X, 3, S, X, 7, 5, 5},
        {1, 1, 3, X, E, 7, X, 3, X, 7, 7, X, 3, X, 7, 7, X, 3, X, 7, S, X, 1, 1, 7},
        {S, 1, 1, 1, E, X, X, X, X, X, S, 1, 1, 1, E, X, X, X, X, X, S, 1, 1, 1, E},
        {X, 1, 1, 3, X, X, 7, X, 3, X, X, 7, X, 3, X, X, 7, X, 3, X, S, 7, X, 1, E},
        {S, 1, 3, X, X, X, X, 1, 1, E, S, 1, 7, X, X, X, X, 1, 1, E, S, 1, 7, X, X},
    };

    private static final BlockData LANTERN = Bukkit.createBlockData("minecraft:sea_lantern");
    private static final BlockData TERRACOTTA = Bukkit.createBlockData("minecraft:blue_terracotta");

    private final SoulTestKit plugin;
    private World world;
    private Player player;
    private boolean active;
    private int layout;
    private final int[] rot = new int[SIZE];
    private final ItemFrame[] frames = new ItemFrame[SIZE];
    private boolean done;
    private int counter = 2;
    private long now;

    /** Everything the device did, for the selftest and {@code status}: "tick kind detail". */
    final List<String> events = new ArrayList<>();

    ArrowAlignSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    GoldorStartSim goldor() {
        return plugin.goldor();
    }

    static int index(int y, int z) {
        return (Y_TOP - y) * COLS + (Z_LEFT - z);
    }

    static int y(int i) {
        return Y_TOP - i / COLS;
    }

    static int z(int i) {
        return Z_LEFT - i % COLS;
    }

    static String name(int i) {
        return "r" + (i / COLS + 1) + "c" + (i % COLS + 1);
    }

    int target(int i) {
        return LAYOUTS[layout][i];
    }

    boolean isArrow(int i) {
        return target(i) >= 0;
    }

    int rotation(int i) {
        return rot[i];
    }

    int layout() {
        return layout;
    }

    boolean done() {
        return done;
    }

    boolean hasFrame(int i) {
        return frames[i] != null && !frames[i].isDead();
    }

    boolean lantern(int i) {
        return world.getBlockAt(BACK_X, y(i), z(i)).getType() == Material.SEA_LANTERN;
    }

    private void log(String kind, String detail) {
        String line = now + " " + kind + " " + detail;
        events.add(line);
        plugin.getLogger().info("[arrows] tick=" + line);
    }

    /**
     * The arrow lanterns do not light before Goldor's phase (live pre-device run, research §15): they stay blue
     * terracotta whatever the rotation, the wool lanterns are lit from the start. {@code hx goldor before} and
     * {@code start} turn them off, Goldor's phase start T ({@link GoldorStartSim}) on (every arrow's lantern then follows
     * its rotation). An aligned board completes only from T on.
     */
    private boolean lanternsOn = true;

    void lanterns(boolean on) {
        lanternsOn = on;
        if (world == null || !active) return;
        // Measured (research §16): nothing changes at Goldor's start; the first click afterwards lights every arrow at its target at once.
        if (!on) for (int i = 0; i < SIZE; i++) if (hasFrame(i) && isArrow(i)) setBack(i, false);
        log("lanterns", on ? "on (Goldor's phase): they refresh with the next click" : "off (before Goldor)");
    }

    /** A click in Goldor's phase: every arrow's lantern follows its rotation in the same tick (8 at once in the live run). */
    private void refreshLanterns() {
        for (int i = 0; i < SIZE; i++) if (hasFrame(i) && isArrow(i)) setBack(i, rot[i] == target(i));
    }

    boolean lanternsOn() {
        return lanternsOn;
    }

    private void setBack(int i, boolean lit) {
        world.getBlockAt(BACK_X, y(i), z(i)).setBlockData(lit && (lanternsOn || !isArrow(i)) ? LANTERN : TERRACOTTA, false);
    }

    private static Rotation rotationOf(int r) {
        return Rotation.values()[r & 7];
    }

    // ---- the device ----

    /** Spawns the layout's frames (arrows at random rotations, never all at their targets), lanterns as the server shows them. */
    void reset(World w, Player p, int layoutIndex, long seed) {
        world = w;
        player = p;
        layout = layoutIndex;
        done = false;
        now = w.getGameTime();
        // Whatever frames the capture holds in the device's place are replaced.
        for (Entity e : w.getNearbyEntities(new Location(w, -1.5, 122.5, 77.5), 4, 4, 4)) {
            if (e instanceof ItemFrame f && f.getLocation().getBlockX() <= 0 && f.getLocation().getBlockX() >= -3) f.remove();
        }
        Random rng = new Random(seed);
        for (int i = 0; i < SIZE; i++) {
            if (frames[i] != null) frames[i].remove();
            frames[i] = null;
            int t = target(i);
            if (t == X) {
                rot[i] = 0;
                setBack(i, false);
                continue;
            }
            Location at = new Location(w, FRAME_X + 0.5, y(i) + 0.5, z(i) + 0.5);
            ItemStack item = t >= 0 ? new ItemStack(Material.ARROW) : new ItemStack(t == S ? Material.LIME_WOOL : Material.RED_WOOL);
            if (t < 0) {
                ItemMeta meta = item.getItemMeta();
                meta.displayName(net.kyori.adventure.text.Component.text(t == S ? "Start" : "End", t == S ? net.kyori.adventure.text.format.NamedTextColor.GREEN : net.kyori.adventure.text.format.NamedTextColor.RED));
                item.setItemMeta(meta);
            }
            rot[i] = t >= 0 ? rng.nextInt(8) : 0;
            ItemFrame f = w.spawn(at, ItemFrame.class, fr -> {
                fr.setFacingDirection(BlockFace.EAST, true);
                fr.setItem(item, false);
            });
            f.setRotation(rotationOf(rot[i]));
            frames[i] = f;
            setBack(i, t < 0 || rot[i] == t);
        }
        // Never start solved.
        if (allAligned()) {
            for (int i = 0; i < SIZE; i++) if (isArrow(i)) {
                rot[i] = (rot[i] + 3) % 8;
                frames[i].setRotation(rotationOf(rot[i]));
                setBack(i, false);
                break;
            }
        }
        active = true;
        events.clear();
        log("reset", "layout " + (layout + 1) + ", seed " + seed);
    }

    boolean allAligned() {
        for (int i = 0; i < SIZE; i++) if (isArrow(i) && rot[i] != target(i)) return false;
        return true;
    }

    /** A right click on the frame of cell {@code i} (server side: one step). */
    void click(long tick, int i) {
        now = tick;
        if (!hasFrame(i)) return;
        if (done) {
            log("ignored", name(i) + " (device done)");
            return;
        }
        if (!isArrow(i)) {
            log("ignored", name(i) + " (wool frame)");
            return;
        }
        rot[i] = (rot[i] + 1) % 8;
        frames[i].setRotation(rotationOf(rot[i]));
        Location at = frames[i].getLocation();
        world.playSound(at, "minecraft:entity.item_frame.rotate_item", SoundCategory.BLOCKS, 1.0f, 1.0f);
        setBack(i, rot[i] == target(i));
        if (lanternsOn) refreshLanterns();
        log("click", name(i) + " -> " + rot[i] + (rot[i] == target(i) ? " (target)" : ""));
        if (!allAligned()) return;
        // Aligned is not complete before Goldor's phase (live pre-dev run: aligned for minutes, completed by a click in it).
        if (plugin.goldor().accepts()) complete();
        else log("aligned", "before Goldor's phase: not complete");
    }

    private void complete() {
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

    // ---- live ----

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!active || world == null || event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof ItemFrame f) || !f.getWorld().equals(world)) return;
        for (int i = 0; i < SIZE; i++) {
            if (frames[i] != null && frames[i].equals(f)) {
                // The server decides: the frame never rotates by itself.
                event.setCancelled(true);
                player = event.getPlayer();
                click(world.getGameTime(), i);
                return;
            }
        }
    }

    void stop() {
        active = false;
    }

    /** Removes the frames (cleanup after tests). */
    void clear() {
        for (int i = 0; i < SIZE; i++) {
            if (frames[i] != null) frames[i].remove();
            frames[i] = null;
        }
        active = false;
    }

    // ---- /hx arrows ----

    static final String USAGE = "arrows <start [layout 1-9] [seed]|reset [layout] [seed]|status|stop|selftest>";

    List<String> command(Player viewer, String[] args, CommandSender sender) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start", "reset" -> {
                World w = viewer.getWorld();
                if (!w.getName().startsWith("soulcap_f7-boss")) {
                    throw new IllegalArgumentException("stand in the f7-boss capture first: hx world load f7-boss entry; hx world tp f7-boss entry 3.5 120 77.5 (you are in " + w.getName() + ")");
                }
                long seed = args.length > 2 ? Long.parseLong(args[2]) : Terminals.newSeed();
                int pick = args.length > 1 ? Integer.parseInt(args[1]) - 1 : new Random(seed).nextInt(LAYOUTS.length);
                if (pick < 0 || pick >= LAYOUTS.length) throw new IllegalArgumentException("layout 1-9");
                if (!sub.equals("reset")) {
                    plugin.applyLocation(viewer, "dungeon");
                    plugin.applySidebar(viewer, "dungeon");
                    plugin.applyTab(viewer, "dungeon");
                    plugin.applyActionbar(viewer, "dungeon");
                }
                reset(w, viewer, pick, seed);
                int frameCount = 0;
                for (int i = 0; i < SIZE; i++) if (hasFrame(i)) frameCount++;
                return List.of("arrow align ready: layout " + (pick + 1) + ", " + frameCount + " frames, seed " + seed + "; right-click the arrows (each click turns one step)");
            }
            case "status" -> {
                if (world == null) return List.of("arrow align not started (hx arrows start)");
                List<String> out = new ArrayList<>();
                StringBuilder b = new StringBuilder();
                for (int r = 0; r < 5; r++) {
                    for (int c = 0; c < 5; c++) {
                        int i = r * COLS + c;
                        b.append(!hasFrame(i) ? " . " : target(i) == S ? " S " : target(i) == E ? " E " : " " + rot[i] + (rot[i] == target(i) ? "*" : " "));
                    }
                    b.append(r < 4 ? " / " : "");
                }
                out.add("layout " + (layout + 1) + (done ? " done" : "") + ", rotations (* = at target): " + b);
                int from = Math.max(0, events.size() - 8);
                out.addAll(events.subList(from, events.size()));
                return out;
            }
            case "stop" -> {
                stop();
                return List.of("arrow align stopped (frames stay)");
            }
            case "selftest" -> {
                World w = viewer.getWorld();
                if (!w.getName().startsWith("soulcap_f7-boss")) throw new IllegalArgumentException("stand in the f7-boss capture first");
                stop();
                String result = new ArrowAlignSelfTest(this, w, viewer).run();
                reset(w, viewer, new Random().nextInt(LAYOUTS.length), Terminals.newSeed());
                return List.of(result);
            }
            default -> throw new IllegalArgumentException("usage: /hx " + USAGE);
        }
    }

    List<String> completions(int pos) {
        return pos == 1 ? List.of("start", "reset", "status", "stop", "selftest") : List.of();
    }

    long now() {
        return now;
    }

    long tick() {
        return world != null ? world.getGameTime() : 0;
    }
}
