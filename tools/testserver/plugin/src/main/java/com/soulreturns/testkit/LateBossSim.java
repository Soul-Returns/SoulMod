package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Goldor's sections (P3) and the Wither King's dragon spawns (P5) as the client sees them, from
 * {@code docs/research/m7-boss-2026-11-13.md} §2-§3 (P3) and P5 §3.1, for the section warning and the dragon countdown:
 * <ul>
 *   <li>{@code hx section start|next|done|gate|core|status}: {@code Who dares trespass…}, the open section's
 *   {@code <name> activated a terminal! (n/N)} lines (N = 7, 8, 7, 7), {@code The gate has been destroyed!} and
 *   {@code The Core entrance is opening!}; a section begins when its predecessor is full and its gate is down.</li>
 *   <li>{@code hx dragon <red|orange|green|blue|purple>}: 10 pulses 10 ticks apart of {@code flame} 20, {@code enchant} 40,
 *   {@code large_smoke} 40 and {@code witch} 40 at the spawn x/z, y 19.0, then an AI-less {@code ender_dragon} at y 14
 *   100 ticks after the first pulse (removed 60 ticks later).</li>
 * </ul>
 * All on the world's game time. {@code selftest} checks both schedules on a virtual clock.
 */
public final class LateBossSim {
    static final String GOLDOR = "§r§4[BOSS] Goldor§r§c: §r§c";
    static final String WHO_DARES = GOLDOR + "Who dares trespass into my domain?";
    static final String GATE = "§r§aThe gate has been destroyed!";
    static final String CORE = "§r§aThe Core entrance is opening!";
    static final int[] SIZES = {7, 8, 7, 7};
    static final int PULSES = 10, PULSE_GAP = 10, SPAWN_AFTER = 100, DRAGON_STAYS = 60;

    record Dragon(String name, double x, double z) {}

    static final List<Dragon> DRAGONS = List.of(
        new Dragon("red", 27, 59), new Dragon("orange", 85, 56), new Dragon("green", 27, 94),
        new Dragon("blue", 84, 94), new Dragon("purple", 56, 125));

    private final SoulTestKit plugin;
    private final TreeMap<Long, List<Runnable>> due = new TreeMap<>();
    private final List<EnderDragon> spawned = new ArrayList<>();
    private BukkitTask task;
    private World world;

    // Section state: the open section (1-4, 5 = core, 0 = none), its counter and gate.
    int open, done;
    boolean gateDown;

    public LateBossSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    public List<String> section(Player viewer, String[] rest) {
        String usage = "section <start|next|done|gate|core|status|selftest>";
        if (rest.length < 1) throw new IllegalArgumentException(usage);
        switch (rest[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                startSections();
                send(viewer, WHO_DARES);
                return List.of("Who dares sent; section 1 open (0/" + SIZES[0] + ")");
            }
            case "next" -> {
                return List.of(send(viewer, counter(viewer.getName(), Math.min(done + 1, size()))));
            }
            case "done" -> {
                return List.of(send(viewer, counter(viewer.getName(), size())));
            }
            case "gate" -> {
                if (open < 1 || open > 3) throw new IllegalArgumentException("no gate in section " + open);
                gateDown = true;
                send(viewer, GATE);
                advance();
                return List.of(statusLine());
            }
            case "core" -> {
                open = 5;
                send(viewer, CORE);
                return List.of(statusLine());
            }
            case "status" -> {
                return List.of(statusLine());
            }
            case "selftest" -> {
                return List.of(sectionSelftest());
            }
            default -> throw new IllegalArgumentException(usage);
        }
    }

    private void startSections() {
        open = 1;
        done = 0;
        gateDown = false;
    }

    private int size() {
        return open >= 1 && open <= 4 ? SIZES[open - 1] : 0;
    }

    /** The counter line for [n], with its effect on the state. */
    String counter(String who, int n) {
        if (open < 1 || open > 4) throw new IllegalArgumentException("no open section (hx section start)");
        done = n;
        String line = "§r§b" + who + "§r§a activated a terminal! (§r§c" + n + "§r§a/" + size() + ")";
        advance();
        return line;
    }

    private void advance() {
        if (open >= 1 && open <= 3 && done >= SIZES[open - 1] && gateDown) {
            open++;
            done = 0;
            gateDown = false;
        }
    }

    private String statusLine() {
        if (open == 0) return "sections: none";
        if (open == 5) return "sections: core open";
        return "sections: S" + open + " open, " + done + "/" + size() + (open < 4 ? (gateDown ? ", gate down" : ", gate up") : "");
    }

    private String send(Player viewer, String legacy) {
        Nms.sendSystemChat(viewer, Txt.of(legacy), false);
        plugin.getLogger().info("[late] tick=" + viewer.getWorld().getGameTime() + " chat: " + Txt.strip(legacy));
        return Txt.strip(legacy) + " → " + statusLine();
    }

    private String sectionSelftest() {
        LateBossSim s = new LateBossSim(plugin);
        s.startSections();
        List<String> fails = new ArrayList<>();
        s.gateDown = true; // gate 1 before S1 N/N (7 of 8 M7 runs)
        for (int i = 1; i <= 7; i++) s.counter("x", i);
        if (s.open != 2) fails.add("S1 full after its gate: open " + s.open);
        for (int i = 1; i <= 8; i++) s.counter("x", i);
        if (s.open != 2) fails.add("S2 full, gate up: open " + s.open);
        s.gateDown = true;
        s.advance();
        if (s.open != 3) fails.add("S2 gate after N/N: open " + s.open);
        s.counter("x", 7);
        s.gateDown = true;
        s.advance();
        if (s.open != 4) fails.add("S3: open " + s.open);
        s.counter("x", 7);
        if (s.open != 4) fails.add("S4 full opens nothing before the core line: open " + s.open);
        return "section selftest: " + (fails.isEmpty() ? "ok" : String.join("; ", fails));
    }

    public List<String> dragon(Player viewer, String[] rest) {
        String usage = "dragon <red|orange|green|blue|purple|stop|selftest>";
        if (rest.length < 1) throw new IllegalArgumentException(usage);
        String name = rest[0].toLowerCase(Locale.ROOT);
        if (name.equals("stop")) {
            stop();
            return List.of("dragons stopped");
        }
        if (name.equals("selftest")) return List.of(dragonSelftest());
        Dragon d = DRAGONS.stream().filter(x -> x.name.equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException(usage));
        world = viewer.getWorld();
        long now = world.getGameTime();
        for (long[] step : schedule(now)) {
            if (step[1] == 0) at(step[0], () -> pulse(viewer, d));
            else at(step[0], () -> spawn(d));
        }
        ensureTask();
        plugin.getLogger().info("[late] tick=" + now + " dragon " + d.name + ": first pulse now, dragon at " + (now + SPAWN_AFTER));
        return List.of(d.name + " dragon: pulses from tick " + now + ", dragon at " + (now + SPAWN_AFTER) + " (" + d.x + ", 14, " + d.z + ")");
    }

    /** [tick, 0 = pulse | 1 = dragon] for a spawn whose first pulse is at [first]. */
    static List<long[]> schedule(long first) {
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < PULSES; i++) out.add(new long[] {first + (long) i * PULSE_GAP, 0});
        out.add(new long[] {first + SPAWN_AFTER, 1});
        return out;
    }

    private String dragonSelftest() {
        List<long[]> s = schedule(1000);
        List<String> fails = new ArrayList<>();
        long pulses = s.stream().filter(x -> x[1] == 0).count();
        if (pulses != PULSES) fails.add(pulses + " pulses");
        if (s.get(PULSES - 1)[0] - s.get(0)[0] != 90) fails.add("pulses span " + (s.get(PULSES - 1)[0] - s.get(0)[0]));
        if (s.get(PULSES)[0] - s.get(0)[0] != SPAWN_AFTER) fails.add("dragon after " + (s.get(PULSES)[0] - s.get(0)[0]));
        return "dragon selftest: " + (fails.isEmpty() ? "ok" : String.join("; ", fails));
    }

    private void pulse(Player viewer, Dragon d) {
        if (!viewer.isOnline()) return;
        viewer.spawnParticle(Particle.FLAME, d.x, 19.0, d.z, 20, 0.5, 0.5, 0.5, 0.0, null, true);
        viewer.spawnParticle(Particle.ENCHANT, d.x, 19.0, d.z, 40, 0.5, 0.5, 0.5, 0.0, null, true);
        viewer.spawnParticle(Particle.LARGE_SMOKE, d.x, 19.0, d.z, 40, 0.5, 0.5, 0.5, 0.0, null, true);
        viewer.spawnParticle(Particle.WITCH, d.x, 19.0, d.z, 40, 0.5, 0.5, 0.5, 0.0, null, true);
    }

    private void spawn(Dragon d) {
        EnderDragon e = world.spawn(new Location(world, d.x, 14.0, d.z), EnderDragon.class, x -> {
            x.setAI(false);
            x.setSilent(true);
            x.setPersistent(false);
        });
        spawned.add(e);
        plugin.getLogger().info("[late] tick=" + world.getGameTime() + " dragon " + d.name + " added");
        at(world.getGameTime() + DRAGON_STAYS, () -> { if (e.isValid()) e.remove(); spawned.remove(e); });
    }

    private void at(long tick, Runnable r) {
        due.computeIfAbsent(tick, k -> new ArrayList<>()).add(r);
    }

    private void ensureTask() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (world == null) return;
        long now = world.getGameTime();
        while (!due.isEmpty() && due.firstKey() <= now) {
            Map.Entry<Long, List<Runnable>> e = due.pollFirstEntry();
            for (Runnable r : e.getValue()) r.run();
        }
        if (due.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    public void stop() {
        due.clear();
        for (EnderDragon e : spawned) if (e.isValid()) e.remove();
        spawned.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
