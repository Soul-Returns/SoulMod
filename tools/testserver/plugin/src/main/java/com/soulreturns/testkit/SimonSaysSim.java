package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Random;
import net.minecraft.sounds.SoundSource;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 * Floor 7 Simon Says ({@code /hx ss}) at its real place in a loaded {@code f7-boss} capture, from
 * {@code docs/research/f7-devices-2026-11-12.md}: lamp wall {@code x 111, y 120-123, z 92-95} (obsidian /
 * sea lantern), button grid at {@code x 110} (16 {@code stone_button[face=wall,facing=west]}), start button
 * {@code (110, 121, 91)}. Everything runs on the world's game time (what {@code set_time} carries).
 *
 * <p>Measured timings (server ticks): a display's first lamp lights 4 ticks after the start press (or in the
 * tick the buttons are removed, for the next round); each lamp is lit 8 ticks and the next one lights in the
 * tick the previous goes out; the buttons appear 10 ticks after the last lamp went out, except in front of a
 * lamp still lit (that one 10 ticks after it goes out); a correct press plays the pling at the player; 4 ticks
 * after a round's last press the buttons go and the next round's display starts (lamps 1, 2, 3, 4), after the
 * 4-lamp round a second pling, the chat line and the buttons go. Start presses: a second one while the first
 * display has not placed its buttons plays the error sound; a third one then starts a second display of a new
 * sequence one lamp longer that runs on top of the first (the skip: lamp 1 of the first display, then lamps 1-2
 * of the second, which is what the server expects); a fourth plays the error sound and cuts the second display
 * after its current lamp: the buttons come (from the first display) but presses do nothing until the next start
 * press, which shows a new two-lamp sequence.
 *
 * <p>Assumed (no recording shows it): a wrong button and any other start press while the device runs play the
 * error sound and reset it to round 1 with the buttons removed (the reference mods' reading); sequences are 4
 * distinct buttons; the device does nothing once complete.
 */
final class SimonSaysSim implements Listener {
    static final int BX = 110;
    static final int LX = 111;
    static final int SY = 121;
    static final int SZ = 91;
    static final int LENGTH = 4;
    private static final String PLING = "minecraft:block.note_block.pling";
    private static final String ERROR = "minecraft:entity.enderman.teleport";

    private final SoulTestKit plugin;
    private World world;
    private Player player;
    private BukkitTask task;
    private Random rng = new Random();
    private long seed;

    // ---- device state ----
    private enum State { IDLE, BUSY, BROKEN, DONE }

    private State state = State.IDLE;
    private int round = 1;
    private List<Integer> seq = List.of();
    private int progress;
    private boolean inputOpen;
    /** Start presses of the current burst (ticks) and whether the third one may start the overlapping display. */
    private final List<Long> burst = new ArrayList<>();
    private boolean armed;
    private Display first;
    private Display second;
    private final List<Display> displays = new ArrayList<>();
    private long now;
    private int counter = 2;

    /** Everything the device did, for the selftest and {@code status}: "tick kind detail". */
    final List<String> events = new ArrayList<>();

    private record Action2(long tick, int order, Runnable run) {}

    private final PriorityQueue<Action2> queue = new PriorityQueue<>((a, b) -> a.tick != b.tick ? Long.compare(a.tick, b.tick) : Integer.compare(a.order, b.order));
    private int order;

    /** One display task: lamps {@code lamps} from tick {@code t0}, 8 ticks each, buttons 10 after the last. */
    private final class Display {
        final List<Integer> lamps;
        final long t0;
        /** False for the first display once the skip's second one runs: the second opens the input. */
        boolean opensInput;
        boolean cut;
        int lit = -1;

        Display(List<Integer> lamps, long t0, boolean opensInput) {
            this.lamps = lamps;
            this.t0 = t0;
            this.opensInput = opensInput;
            for (int k = 0; k < lamps.size(); k++) {
                int kk = k;
                at(t0 + 8L * k, () -> show(kk));
            }
            at(t0 + 8L * lamps.size(), this::lastOff);
        }

        private void show(int k) {
            if (cut || state == State.DONE) return;
            lit = lamps.get(k);
            for (int i = 0; i < 16; i++) if (i != lit && lamp(i)) setLamp(i, false);
            setLamp(lit, true);
            log("lamp", name(lit) + " on (display " + id() + ")");
        }

        /** Turns off the position of its own last lamp, whoever lit it (four-press run, ring timers fight 1: a and b shared a lamp). */
        private void lastOff() {
            if (state == State.DONE) return;
            int pos = lamps.isEmpty() ? -1 : lamps.get(lamps.size() - 1);
            if (!cut && pos >= 0 && lamp(pos)) {
                setLamp(pos, false);
                log("lamp", name(pos) + " off (display " + id() + ")");
            }
            lit = -1;
            at(now + 10, this::grid);
        }

        private void grid() {
            if (state == State.DONE || !displays.contains(this)) return;
            int placed = 0;
            for (int i = 0; i < 16; i++) {
                if (!lamp(i) && !button(i)) {
                    setButton(i, true);
                    placed++;
                }
            }
            if (placed > 0) log("buttons", placed + " placed (display " + id() + ")");
            displays.remove(this);
            if (opensInput && !cut && state == State.BUSY) {
                inputOpen = true;
                armed = false;
            } else if (cut || (state == State.BUSY && !inputOpen && displays.isEmpty())) {
                state = State.BROKEN;
                log("state", "broken: buttons up, presses ignored until a start press");
            }
            if (displays.isEmpty() && armed) armed = false;
        }

        private String id() {
            return this == first ? "1" : this == second ? "2" : "r" + lamps.size();
        }
    }

    SimonSaysSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    // ---- world helpers ----

    static int index(int y, int z) {
        return (123 - y) * 4 + (z - 92);
    }

    static String name(int i) {
        return "r" + (i / 4 + 1) + "c" + (i % 4 + 1);
    }

    private Block lampBlock(int i) {
        return world.getBlockAt(LX, 123 - i / 4, 92 + i % 4);
    }

    private Block buttonBlock(int i) {
        return world.getBlockAt(BX, 123 - i / 4, 92 + i % 4);
    }

    boolean lamp(int i) {
        return lampBlock(i).getType() == Material.SEA_LANTERN;
    }

    boolean button(int i) {
        return buttonBlock(i).getType() == Material.STONE_BUTTON;
    }

    private void setLamp(int i, boolean on) {
        lampBlock(i).setType(on ? Material.SEA_LANTERN : Material.OBSIDIAN, false);
    }

    private static final BlockData BUTTON = Bukkit.createBlockData("minecraft:stone_button[face=wall,facing=west,powered=false]");

    private void setButton(int i, boolean on) {
        Block b = buttonBlock(i);
        if (on) b.setBlockData(BUTTON, false);
        else b.setType(Material.AIR, false);
    }

    private void removeGrid() {
        for (int i = 0; i < 16; i++) if (button(i)) setButton(i, false);
        inputOpen = false;
        log("buttons", "removed");
    }

    private void at(long tick, Runnable r) {
        queue.add(new Action2(tick, order++, r));
    }

    private void log(String kind, String detail) {
        String line = now + " " + kind + " " + detail;
        events.add(line);
        plugin.getLogger().info("[ss] tick=" + line);
    }

    private void sound(String id, SoundSource source, float volume, float pitch) {
        if (player != null && player.isOnline()) Terminals.sound(player, id, source, volume, pitch);
    }

    // ---- the device ----

    /** Lamps obsidian, no buttons, start button, round 1. */
    void reset(World w, Player p, long seedValue) {
        world = w;
        player = p;
        seed = seedValue;
        rng = new Random(seedValue);
        queue.clear();
        displays.clear();
        burst.clear();
        first = null;
        second = null;
        armed = false;
        state = State.IDLE;
        round = 1;
        seq = List.of();
        progress = 0;
        inputOpen = false;
        now = w.getGameTime();
        for (int i = 0; i < 16; i++) {
            setLamp(i, false);
            setButton(i, false);
        }
        world.getBlockAt(BX, SY, SZ).setBlockData(BUTTON, false);
        events.clear();
        log("reset", "seed " + seedValue);
    }

    private List<Integer> newSequence() {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < 16; i++) all.add(i);
        java.util.Collections.shuffle(all, rng);
        return List.copyOf(all.subList(0, LENGTH));
    }

    /** Runs every queued action up to {@code tick}. */
    void step(long tick) {
        while (!queue.isEmpty() && queue.peek().tick <= tick) {
            Action2 a = queue.poll();
            now = a.tick;
            a.run.run();
        }
        now = tick;
    }

    void startPress(long tick) {
        step(tick);
        log("press", "start");
        switch (state) {
            case DONE -> {
            }
            case IDLE, BROKEN -> {
                boolean broken = state == State.BROKEN;
                state = State.BUSY;
                seq = newSequence();
                progress = 0;
                inputOpen = false;
                burst.clear();
                burst.add(tick);
                armed = false;
                displays.clear();
                second = null;
                if (broken) at(tick + 4, this::removeGrid);
                first = new Display(seq.subList(0, round), tick + 4, true);
                displays.add(first);
            }
            case BUSY -> {
                boolean firstRunning = first != null && displays.contains(first);
                if (burst.size() == 1 && firstRunning && !inputOpen) {
                    error("start while the first display runs");
                    burst.add(tick);
                    armed = true;
                } else if (burst.size() == 2 && armed && firstRunning) {
                    // The skip: a second display of a new sequence, one lamp longer, on top of the first.
                    burst.add(tick);
                    round = Math.min(round + 1, LENGTH);
                    seq = newSequence();
                    progress = 0;
                    first.opensInput = false;
                    second = new Display(seq.subList(0, round), tick + 4, true);
                    displays.add(second);
                    log("state", "second display (skip), round " + round + ": " + names(seq.subList(0, round)));
                } else if (burst.size() == 3 && second != null && displays.contains(second)) {
                    burst.add(tick);
                    error("fourth start press: second display cut");
                    second.cut = true;
                    displays.remove(second);
                    // Its lamp goes out on its normal step; no lamp after it, no input: the first display's buttons come, presses do nothing.
                    int lit = second.lit;
                    if (lit >= 0) {
                        at(second.t0 + 8L * (second.lamps.indexOf(lit) + 1), () -> {
                            if (lamp(lit)) setLamp(lit, false);
                            log("lamp", name(lit) + " off (display 2, cut)");
                        });
                    }
                } else {
                    error("start while running: reset to round 1");
                    resetToRoundOne();
                }
            }
        }
    }

    private void resetToRoundOne() {
        queue.clear();
        displays.clear();
        for (int i = 0; i < 16; i++) if (lamp(i)) setLamp(i, false);
        removeGrid();
        state = State.IDLE;
        round = 1;
        progress = 0;
        burst.clear();
        armed = false;
        first = null;
        second = null;
    }

    private void error(String why) {
        sound(ERROR, SoundSource.HOSTILE, 8.0f, 0.0f);
        log("error", why);
    }

    void buttonPress(long tick, int i) {
        step(tick);
        log("press", name(i));
        if (!inputOpen || state != State.BUSY) {
            log("ignored", name(i) + " (" + (state == State.BROKEN ? "broken" : "no input") + ")");
            return;
        }
        if (seq.get(progress) != i) {
            error("wrong button " + name(i) + ", wanted " + name(seq.get(progress)) + ": reset to round 1");
            resetToRoundOne();
            return;
        }
        sound(PLING, SoundSource.BLOCKS, 8.0f, 4.047619f);
        log("pling", name(i) + " " + (progress + 1) + "/" + round);
        progress++;
        if (progress < round) return;
        inputOpen = false;
        if (round >= LENGTH) {
            at(tick + 4, this::complete);
            return;
        }
        round++;
        progress = 0;
        at(tick + 4, () -> {
            removeGrid();
            Display next = new Display(seq.subList(0, round), now, true);
            displays.add(next);
        });
    }

    private void complete() {
        sound(PLING, SoundSource.BLOCKS, 8.0f, 4.047619f);
        removeGrid();
        state = State.DONE;
        counter = counter >= 7 ? 1 : counter + 1;
        String line = "§r§6" + (player != null ? player.getName() : "?") + "§r§a completed a device! (§r§c" + counter + "§r§a/7)";
        if (player != null && player.isOnline()) Nms.sendSystemChat(player, Txt.of(Terminals.wire(line)), false);
        log("complete", Txt.strip(line));
    }

    private static String names(List<Integer> list) {
        StringBuilder b = new StringBuilder();
        for (int i : list) b.append(b.isEmpty() ? "" : " ").append(name(i));
        return b.toString();
    }

    // ---- live ----

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (world == null || task == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block b = event.getClickedBlock();
        if (b == null || !b.getWorld().equals(world) || b.getType() != Material.STONE_BUTTON) return;
        boolean start = b.getX() == BX && b.getY() == SY && b.getZ() == SZ;
        boolean grid = b.getX() == BX && b.getY() >= 120 && b.getY() <= 123 && b.getZ() >= 92 && b.getZ() <= 95;
        if (!start && !grid) return;
        // Hypixel answers a press by re-sending the button (unpowered) and the block in front: a cancelled interact does the same.
        event.setCancelled(true);
        player = event.getPlayer();
        long t = world.getGameTime();
        if (start) startPress(t);
        else buttonPress(t, index(b.getY(), b.getZ()));
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
        queue.clear();
    }

    // ---- /hx ss ----

    static final String USAGE = "ss <start [seed]|reset [seed]|status|stop|selftest>";

    List<String> command(Player viewer, String[] args, CommandSender sender) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start", "reset" -> {
                World w = viewer.getWorld();
                if (!w.getName().startsWith("soulcap_f7-boss")) {
                    throw new IllegalArgumentException("stand in the f7-boss capture first: hx world load f7-boss entry; hx world tp f7-boss entry 108.5 120 93.5 (you are in " + w.getName() + ")");
                }
                long s = args.length > 1 ? Long.parseLong(args[1]) : Terminals.newSeed();
                if (sub.equals("start")) {
                    plugin.applyLocation(viewer, "dungeon");
                    plugin.applySidebar(viewer, "dungeon");
                    plugin.applyTab(viewer, "dungeon");
                    plugin.applyActionbar(viewer, "dungeon");
                }
                reset(w, viewer, s);
                ensureTask();
                return List.of("simon says ready at (110, 121, 91), seed " + s + ": press the start button (3 quick presses = the skip)");
            }
            case "status" -> {
                if (world == null) return List.of("simon says not started (hx ss start)");
                List<String> out = new ArrayList<>();
                out.add("state " + state + ", round " + round + ", progress " + progress + ", input " + (inputOpen ? "open" : "closed")
                    + ", sequence " + names(seq) + ", burst " + burst.size() + ", seed " + seed + ", running " + (task != null));
                int from = Math.max(0, events.size() - 12);
                out.addAll(events.subList(from, events.size()));
                return out;
            }
            case "stop" -> {
                stop();
                return List.of("simon says stopped (blocks stay)");
            }
            case "selftest" -> {
                World w = viewer.getWorld();
                if (!w.getName().startsWith("soulcap_f7-boss")) throw new IllegalArgumentException("stand in the f7-boss capture first");
                stop();
                String result = new SimonSaysSelfTest(this, w, viewer).run();
                reset(w, viewer, Terminals.newSeed());
                ensureTask();
                return List.of(result);
            }
            default -> throw new IllegalArgumentException("usage: /hx " + USAGE);
        }
    }

    List<String> completions(int pos) {
        return pos == 1 ? List.of("start", "reset", "status", "stop", "selftest") : List.of();
    }

    // ---- for the selftest ----

    long now() {
        return now;
    }

    int round() {
        return round;
    }

    String state() {
        return state.name();
    }

    List<Integer> expected() {
        return seq.subList(0, Math.min(round, seq.size()));
    }
}
