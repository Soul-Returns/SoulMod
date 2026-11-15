package com.soulreturns.testkit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.entity.LargeFireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Floor 7 Storm (P2) in a loaded {@code f7-boss} capture, on the server clock: the {@code Pathetic Maxor}
 * anchor P, the 20-tick pad poll, pillars that really move one layer per 4 ticks with the captured piston
 * and iron-golem sounds, the 3-poll crush rule, Storm's scripted opening, chase, high and Giga phases with
 * their titles and chat, and Goldor's start / death-zone / core lines. Numbers come from
 * {@code docs/research/storm-crush-2026-11-12.md}; what that doc does not pin down is marked in the README.
 * Every signal sent is logged with its server tick ({@code [storm] tick=… P+…}).
 */
public final class StormSim implements Listener {
    static final int FLOOR = 169, TOP = 189, START_Y = 175, POLL = 20, STEP = 4, BATCH = 5, REVERSAL = 8, PRE_ROLL = 40;
    static final int CRUSH_WINDOW = 3, BAR_STEP = 8;
    static final int HIGH_CLIMB = 18, HIGH_LEAVE = 162, HIGH_CHASE = 190, FIREBALL_PHASE = 35;
    static final double HIGH_RISE = 14.4;
    private static final double SPEED = 0.45, HOVER = 5.0;
    private static final double[] SPAWN = {103, 188, 53};
    private static final double[] PARK = {102.4, 183.0, 52.4};
    private static final double[] IN_E = {101.2, 179.2, 63.8};
    private static final double[] GIGA_SPOT = {72.3, 179.9, 53.3};
    private static final double[] WEST = {35, 188, 53};

    private static final String STORM = "§r§4[BOSS] Storm§r§c: §r§c";
    private static final String GOLDOR = "§r§4[BOSS] Goldor§r§c: §r§c";
    private static final String PATHETIC = STORM + "Pathetic Maxor, just like expected.";
    private static final String OUCH = STORM + "Ouch, that hurt!";
    private static final String OOF = STORM + "Oof";
    private static final String ENRAGED = "§r§c⚠ Storm is enraged! ⚠";
    private static final String POWER = STORM + "The power of lightning is quite phenomenal. A single strike can vaporize a person whole.";
    private static final String CATALYST = STORM + "THUNDER LET ME BE YOUR CATALYST!";
    private static final String ENERGY = STORM + "ENERGY HEED MY CALL!";
    private static final String GIGA_HIT = "§r§cStorm's§r§7 Giga Lightning hit you for §r§c13,915.4§r§7 true damage.";
    private static final String FIREBALL = "§r§cStorm's§r§7 Lightning Fireball hit you for §r§c8,415.2§r§7 true damage.";
    private static final String END = STORM + "I should have known that I stood no chance.";
    private static final String WHO_DARES = GOLDOR + "Who dares trespass into my domain?";
    private static final String DEATH_ZONE = GOLDOR + "What do you think you are doing there!";
    private static final String CORE = "§r§aThe Core entrance is opening!";
    private static final String SPIRIT_SAVE = "§r§aSecond Wind Activated!§r§a Your Spirit Mask saved your life!";
    /** The P2→P3 floor collapse: falling blocks at the Storm pad, WD 2–3 ticks later, Goldor checks at collapse + 61…62 + 60k (M7, lone-ping clock). */
    private static final int COLLAPSE_TO_WD = 2, COLLAPSE_TO_CHECK = 61;

    private record Def(String name, String pad, int x, int z, int padX, int padZ, int soundX, int soundZ) {}

    private static final List<Def> DEFS = List.of(
        new Def("E", "purple", 97, 62, 111, 91, 97, 64),
        new Def("WS", "yellow", 43, 62, 29, 91, 43, 64),
        new Def("WN", "green", 43, 38, 29, 9, 43, 40));

    private final SoulTestKit plugin;
    private Run run;
    private Goldor goldor;
    private BukkitTask task;

    public StormSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- commands

    /** Returns the lines to print; throws IllegalArgumentException with the usage on a bad command. */
    public List<String> command(Player viewer, String[] rest) {
        String usage = "storm <start [F7|M7] [flight]|stop|park [x y z]|goto x y z|follow|away|back|phase <high|giga [high]|chase>|crush"
            + "|line <on|late N|off>|pillar <E|WS|WN> <down|up> [layers]|goldor [core|every N|spot x y z [r]|save]|end|status>";
        if (rest.length == 0) throw new IllegalArgumentException(usage);
        String sub = rest[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start": return List.of(start(viewer, rest));
            case "goldor": return List.of(goldorCommand(viewer, rest));
            case "stop": return List.of(stop());
            case "status": return status();
            default: break;
        }
        Run r = run;
        if (r == null) throw new IllegalArgumentException("no storm run (hx storm start)");
        if (r.p < 0) throw new IllegalArgumentException("storm starts in " + (r.startTick - r.world.getGameTime()) + " ticks, wait for the Pathetic line");
        r.cur = r.world.getGameTime();
        switch (sub) {
            case "park" -> {
                double[] at = rest.length >= 4 ? vec(rest, 1) : PARK;
                r.setMode("go", r.go(at[0], at[1], at[2], SPEED));
                return List.of(String.format(Locale.ROOT, "storm flying to %.1f %.1f %.1f and holding", at[0], at[1], at[2]));
            }
            case "goto" -> {
                if (rest.length < 4) throw new IllegalArgumentException("storm goto x y z");
                double[] at = vec(rest, 1);
                r.setMode("go", r.go(at[0], at[1], at[2], SPEED));
                return List.of(String.format(Locale.ROOT, "storm flying to %.1f %.1f %.1f and holding", at[0], at[1], at[2]));
            }
            case "follow" -> {
                r.setMode("chase", r.chase());
                return List.of("storm chasing you");
            }
            case "away" -> {
                r.hide(true);
                return List.of("storm hidden for you (out of tracking range)");
            }
            case "back" -> {
                r.hide(false);
                return List.of("storm shown again (same entity id)");
            }
            case "phase" -> {
                if (rest.length < 2) throw new IllegalArgumentException("storm phase <high|giga [high]|chase>");
                return List.of(switch (rest[1].toLowerCase(Locale.ROOT)) {
                    case "high" -> r.high();
                    case "giga" -> r.gigaPhase(rest.length > 2 && rest[2].equalsIgnoreCase("high"));
                    case "chase" -> { r.setMode("chase", r.chase()); yield "storm chasing you"; }
                    default -> throw new IllegalArgumentException("storm phase <high|giga [high]|chase>");
                });
            }
            case "crush" -> {
                r.crush(null, -1);
                return List.of("crush " + r.crushes + " (line " + r.lineMode() + ", no pillar destroyed)");
            }
            case "line" -> {
                if (rest.length < 2) throw new IllegalArgumentException("storm line <on|late N|off>");
                r.lineDelay = switch (rest[1].toLowerCase(Locale.ROOT)) {
                    case "on" -> 0;
                    case "off" -> -1;
                    case "late" -> Math.max(0, Integer.parseInt(rest[2]));
                    default -> throw new IllegalArgumentException("storm line <on|late N|off>");
                };
                return List.of("crush lines " + r.lineMode());
            }
            case "end" -> {
                r.chat(END);
                return List.of("end line sent");
            }
            case "pillar" -> {
                if (rest.length < 3) throw new IllegalArgumentException("storm pillar <E|WS|WN> <down|up> [layers]");
                Pillar p = r.pillar(rest[1]);
                if (!p.usable) throw new IllegalArgumentException("pillar " + p.def.name + " has no blocks in this capture");
                int dir = switch (rest[2].toLowerCase(Locale.ROOT)) {
                    case "down" -> -1;
                    case "up" -> 1;
                    default -> throw new IllegalArgumentException("storm pillar <E|WS|WN> <down|up> [layers]");
                };
                int layers = rest.length > 3 ? Integer.parseInt(rest[3]) : BATCH;
                if (layers < 1) throw new IllegalArgumentException("layers >= 1");
                p.forcedDir = dir;
                p.forcedLayers = layers;
                long next = r.p < 0 ? -1 : r.p + ((r.cur - r.p) / POLL + 1) * POLL;
                return List.of("pillar " + p.def.name + " " + rest[2] + " x" + layers + " starts at the next poll"
                    + (next < 0 ? " (P not set yet)" : " (tick " + next + ", P+" + (next - r.p) + ")"));
            }
            default -> throw new IllegalArgumentException(usage);
        }
    }

    private static double[] vec(String[] a, int at) {
        return new double[] {Double.parseDouble(a[at]), Double.parseDouble(a[at + 1]), Double.parseDouble(a[at + 2])};
    }

    private String start(Player viewer, String[] rest) {
        if (run != null) throw new IllegalArgumentException("a storm run exists (hx storm stop first)");
        World world = viewer.getWorld();
        if (!world.getName().startsWith("soulcap_f7-boss")) {
            throw new IllegalArgumentException("stand in the f7-boss capture first: hx world load f7-boss entry; "
                + "hx world tp f7-boss entry 100 170 91 (you are in " + world.getName() + ")");
        }
        boolean m7 = false, flight = false;
        for (int i = 1; i < rest.length; i++) {
            switch (rest[i].toLowerCase(Locale.ROOT)) {
                case "f7" -> m7 = false;
                case "m7" -> m7 = true;
                case "flight" -> flight = true;
                default -> throw new IllegalArgumentException("storm start [F7|M7] [flight]");
            }
        }
        if (world.getDifficulty() == Difficulty.PEACEFUL) world.setDifficulty(Difficulty.EASY);
        plugin.applyLocation(viewer, "dungeon");
        plugin.applySidebar(viewer, m7 ? "dungeon_m7" : "dungeon");
        plugin.applyTab(viewer, "dungeon");
        plugin.applyActionbar(viewer, "dungeon");
        Run r = new Run(world, viewer, m7, flight);
        run = r;
        ensureTask();
        StringBuilder out = new StringBuilder("storm " + (m7 ? "M7" : "F7") + " start in " + PRE_ROLL + " ticks (P = that tick), pillars:");
        for (Pillar p : r.pillars) out.append(' ').append(p.summary());
        return out.toString();
    }

    private String stop() {
        boolean any = run != null || goldor != null;
        if (run != null) {
            run.stop();
            run = null;
        }
        if (goldor != null) goldor.stop();
        goldor = null;
        if (task != null) {
            task.cancel();
            task = null;
        }
        return any ? "storm stopped, pillars restored" : "nothing running";
    }

    private List<String> status() {
        List<String> out = new ArrayList<>();
        if (run == null && goldor == null) return List.of("no storm run");
        if (run != null) out.addAll(run.status());
        if (goldor != null) out.add(goldor.status());
        return out;
    }

    private String goldorCommand(Player viewer, String[] rest) {
        World world = viewer.getWorld();
        if (rest.length > 1 && rest[1].equalsIgnoreCase("core")) {
            long now = world.getGameTime();
            send(viewer, CORE);
            logLine(now, goldor == null ? "" : "WD+" + (now - goldor.wd), "chat: " + Txt.strip(CORE));
            if (goldor != null) goldor.stop();
            goldor = null;
            return "core line sent, death-zone grid stopped";
        }
        int every = 3;
        double[] spot = null;
        double radius = 3;
        boolean save = false;
        for (int i = 1; i < rest.length; i++) {
            switch (rest[i].toLowerCase(Locale.ROOT)) {
                case "every" -> every = Integer.parseInt(rest[++i]);
                case "save" -> save = true;
                case "spot" -> {
                    spot = vec(rest, i + 1);
                    i += 3;
                    if (i + 1 < rest.length && !Character.isLetter(rest[i + 1].charAt(0))) radius = Double.parseDouble(rest[++i]);
                }
                default -> throw new IllegalArgumentException("storm goldor [core|every N|spot x y z [r]|save]");
            }
        }
        if (every < 1) throw new IllegalArgumentException("every >= 1");
        if (goldor != null) goldor.stop();
        Goldor g = new Goldor(world, viewer, every, spot, radius, save);
        goldor = g;
        g.begin();
        ensureTask();
        return "goldor started: floor collapse now, WD at +" + COLLAPSE_TO_WD + ", death-zone line"
            + (save ? " after a Spirit Mask save" : "") + " on collapse+" + COLLAPSE_TO_CHECK + "+60k, "
            + (spot == null ? "every " + every + " periods"
            : String.format(Locale.ROOT, "when within %.1f of %.1f %.1f %.1f", radius, spot[0], spot[1], spot[2]));
    }

    /** The damage chat only when a fireball of the run actually hits you (all player damage is cancelled anyway). */
    @EventHandler
    public void onFireballHit(ProjectileHitEvent event) {
        Run r = run;
        if (r == null || !(event.getEntity() instanceof LargeFireball f) || !r.fireballs.remove(f)) return;
        if (event.getHitEntity() == r.viewer) {
            r.cur = r.world.getGameTime();
            r.chat(FIREBALL);
        }
        f.remove();
    }

    public void shutdown() {
        try {
            stop();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[storm] shutdown: " + e);
        }
    }

    // ---------------------------------------------------------------- the master tick

    private void ensureTask() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        try {
            if (run != null) {
                if (!run.alive()) {
                    plugin.getLogger().warning("[storm] world or player gone, run dropped");
                    run.stop();
                    run = null;
                } else {
                    run.advance();
                }
            }
            if (goldor != null) goldor.advance();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[storm] " + e);
            e.printStackTrace();
        }
        if (run == null && goldor == null && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void send(Player player, String legacy) {
        Nms.sendSystemChat(player, Txt.of(legacy), false);
    }

    private void logLine(long tick, String rel, String what) {
        plugin.getLogger().info("[storm] tick=" + tick + (rel.isEmpty() ? "" : " " + rel) + " " + what);
    }

    // ---------------------------------------------------------------- Goldor

    private final class Goldor {
        final World world;
        final Player viewer;
        final int every;
        final double[] spot;
        final double radius;
        final boolean save;
        long collapse, wd, processed;
        final List<org.bukkit.entity.FallingBlock> falling = new ArrayList<>();

        Goldor(World world, Player viewer, int every, double[] spot, double radius, boolean save) {
            this.world = world;
            this.viewer = viewer;
            this.every = every;
            this.spot = spot;
            this.radius = radius;
            this.save = save;
        }

        void begin() {
            collapse = world.getGameTime();
            wd = collapse + COLLAPSE_TO_WD;
            processed = collapse;
            // A few blocks of the burst (Hypixel: ~2000 in one tick over x 90-112, z 30-52, y 161-232); gone 3 ticks later.
            BlockData stone = Material.STONE_BRICKS.createBlockData();
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    falling.add(world.spawn(new Location(world, 98.5 + dx * 3, 221, 38.5 + dz * 3), org.bukkit.entity.FallingBlock.class, f -> {
                        f.setBlockData(stone);
                        f.setDropItem(false);
                        f.setGravity(false);
                        f.setPersistent(false);
                    }));
                }
            }
            logLine(collapse, "WD-" + COLLAPSE_TO_WD, "floor collapse: " + falling.size() + " falling blocks at the Storm pad");
        }

        void stop() {
            falling.forEach(f -> { if (f.isValid()) f.remove(); });
            falling.clear();
        }

        void advance() {
            long now = world.getGameTime();
            while (processed < now) {
                long tick = ++processed;
                if (tick == collapse + 3) stop();
                if (tick == wd) {
                    send(viewer, WHO_DARES);
                    logLine(wd, "WD+0", "chat: " + Txt.strip(WHO_DARES));
                }
                long t = tick - collapse - COLLAPSE_TO_CHECK;
                if (t < 0 || t % 60 != 0) continue;
                long k = t / 60;
                Location l = viewer.getLocation();
                boolean fire = spot == null ? k % every == 0
                    : viewer.getWorld() == world && l.distance(new Location(world, spot[0], spot[1], spot[2])) <= radius;
                if (!fire) continue;
                if (save) {
                    send(viewer, SPIRIT_SAVE);
                    logLine(processed, "WD+" + (processed - wd), "chat: " + Txt.strip(SPIRIT_SAVE));
                }
                send(viewer, DEATH_ZONE);
                logLine(processed, "WD+" + (processed - wd), "chat: " + Txt.strip(DEATH_ZONE) + " (check " + k + ")");
            }
        }

        String status() {
            long now = world.getGameTime();
            long t = now - collapse - COLLAPSE_TO_CHECK;
            long next = collapse + COLLAPSE_TO_CHECK + (t < 0 ? 0 : (t / 60 + 1) * 60);
            return "goldor: collapse=" + collapse + " WD=" + wd + " now=" + now + " (WD+" + (now - wd) + "), next check WD+" + (next - wd)
                + ", " + (spot == null ? "every " + every + " periods" : "spot " + java.util.Arrays.toString(spot) + " r " + radius);
        }
    }

    // ---------------------------------------------------------------- pillars

    private final class Pillar {
        final Def def;
        final World world;
        boolean usable;
        int start, bottom, height, dir = -1;
        BlockData[][] pattern, orig, cur;
        int remaining;
        long nextLayer;
        boolean reversal, crushed, destroyed;
        int forcedDir, forcedLayers;
        final List<Long> downPolls = new ArrayList<>();
        final Run run;

        Pillar(Run run, Def def) {
            this.run = run;
            this.def = def;
            this.world = run.world;
            scan();
        }

        /** The column's bottom and height from its center, and a copy of everything in its box. */
        private void scan() {
            int cx = def.x + 3, cz = def.z + 3;
            int y = FLOOR;
            while (y <= TOP && world.getBlockAt(cx, y, cz).getType() == Material.AIR) y++;
            if (y > TOP) return;
            int top = y;
            while (top < 250 && world.getBlockAt(cx, top + 1, cz).getType() != Material.AIR) top++;
            start = bottom = y;
            height = top - y + 1;
            int rows = TOP + height - FLOOR;
            orig = new BlockData[rows][49];
            cur = new BlockData[rows][49];
            for (int yi = 0; yi < rows; yi++) {
                for (int c = 0; c < 49; c++) {
                    BlockData d = world.getBlockAt(def.x + c / 7, FLOOR + yi, def.z + c % 7).getBlockData();
                    orig[yi][c] = d;
                    cur[yi][c] = d;
                }
            }
            pattern = new BlockData[height][];
            for (int i = 0; i < height; i++) pattern[i] = orig[start + i - FLOOR].clone();
            usable = true;
            for (int cxk = (def.x >> 4); cxk <= (def.x + 6) >> 4; cxk++) {
                for (int czk = (def.z >> 4); czk <= (def.z + 6) >> 4; czk++) world.addPluginChunkTicket(cxk, czk, plugin);
            }
        }

        String summary() {
            return usable ? def.name + "(y" + bottom + ".." + (bottom + height - 1) + ")" : def.name + "(no blocks, not moved)";
        }

        boolean inside(double x, double z) {
            return x >= def.x && x < def.x + 7 && z >= def.z && z < def.z + 7;
        }

        boolean onPad(Player p) {
            Location l = p.getLocation();
            return l.getWorld() == world && l.getX() >= def.padX && l.getX() < def.padX + 7
                && l.getZ() >= def.padZ && l.getZ() < def.padZ + 7 && Math.abs(l.getY() - 170) <= 0.5;
        }

        boolean armed(long k) {
            for (long d : downPolls) if (d >= k - CRUSH_WINDOW && d <= k - 1) return true;
            return false;
        }

        void write(int y, int c, BlockData want) {
            int yi = y - FLOOR;
            if (cur[yi][c].equals(want)) return;
            world.getBlockAt(def.x + c / 7, y, def.z + c % 7).setBlockData(want, false);
            cur[yi][c] = want;
        }

        private void setBottom(int to) {
            BlockData air = Material.AIR.createBlockData();
            int lo = Math.min(bottom, to), hi = Math.max(bottom, to) + height - 1;
            for (int y = lo; y <= hi; y++) {
                int li = y - to;
                boolean in = li >= 0 && li < height;
                for (int c = 0; c < 49; c++) write(y, c, in ? pattern[li][c] : air);
            }
            bottom = to;
        }

        /** One layer, when due; the golem hurt marks arriving at the floor or the top. */
        void step(long tick) {
            if (!usable || crushed || remaining <= 0 || tick < nextLayer) return;
            int from = bottom, to = bottom + dir;
            if (to < FLOOR || to > TOP) {
                remaining = 0;
                return;
            }
            run.sound(dir < 0 ? "block.piston.extend" : "block.piston.contract", Sound.Source.BLOCK, 10f, 0.4920635f,
                def.soundX, from, def.soundZ, "pillar " + def.name + " " + (dir < 0 ? "down" : "up") + " layer y" + from);
            setBottom(to);
            remaining--;
            nextLayer = tick + STEP;
            if (to == FLOOR || to == TOP) {
                remaining = 0;
                dir = -dir;
                reversal = true;
                run.sound("entity.iron_golem.hurt", Sound.Source.NEUTRAL, 10f, 0.4920635f,
                    def.soundX, to == FLOOR ? FLOOR : TOP - 1, def.soundZ, "pillar " + def.name + " reached " + (to == FLOOR ? "floor" : "top"));
            }
        }

        /** Crushed pillar: layers y170 and up become air. */
        void destroy() {
            if (!usable || destroyed) return;
            destroyed = true;
            BlockData air = Material.AIR.createBlockData();
            for (int y = 170; y <= bottom + height - 1; y++) for (int c = 0; c < 49; c++) write(y, c, air);
        }

        void restore() {
            if (!usable) return;
            for (int yi = 0; yi < orig.length; yi++) for (int c = 0; c < 49; c++) write(FLOOR + yi, c, orig[yi][c]);
            for (int cxk = (def.x >> 4); cxk <= (def.x + 6) >> 4; cxk++) {
                for (int czk = (def.z >> 4); czk <= (def.z + 6) >> 4; czk++) world.removePluginChunkTicket(cxk, czk, plugin);
            }
        }

        String describe(long k) {
            if (!usable) return def.name + ": no blocks in this capture";
            StringBuilder armedPolls = new StringBuilder();
            for (long d : downPolls) if (d >= k - CRUSH_WINDOW) armedPolls.append('P').append('+').append(d * POLL).append(' ');
            return def.name + " (" + def.pad + " pad): bottom y" + bottom + ", next move " + (dir < 0 ? "down" : "up")
                + ", " + (remaining > 0 ? remaining + " layers pending" : "idle") + (destroyed ? ", DESTROYED" : crushed ? ", crushed" : "")
                + ", down polls in the last " + CRUSH_WINDOW + ": " + (armedPolls.length() == 0 ? "none" : armedPolls.toString().trim())
                + (forcedLayers > 0 ? ", forced " + (forcedDir < 0 ? "down" : "up") + " x" + forcedLayers + " pending" : "");
        }
    }

    // ---------------------------------------------------------------- the run

    private interface Mode {
        void step(long now);
    }

    private final class Run {
        final World world;
        final Player viewer;
        final boolean m7, flight;
        final List<Pillar> pillars = new ArrayList<>();
        final TreeMap<Long, List<Runnable>> events = new TreeMap<>();
        long startTick, processed, p = -1, cur;
        Wither storm;
        double sx = SPAWN[0], sy = SPAWN[1], sz = SPAWN[2];
        boolean hidden, ended;
        int crushes, modeSeq;
        /** The crush line: 0 on time, N ticks late (M7: +26…48 behind Storm's dialogue), -1 never (2 of 16 M7 crushes). */
        int lineDelay;
        /** Storm's boss bar as Hypixel shows it: full until crush 1, 0.45 until crush 2 (the bar steps 3-18 ticks after a crush). */
        BossBar bar;
        long fbFrom, fbTo;
        final List<LargeFireball> fireballs = new ArrayList<>();
        String modeName = "waiting";
        Mode mode = now -> {};
        int trackerWas = -2;

        Run(World world, Player viewer, boolean m7, boolean flight) {
            this.world = world;
            this.viewer = viewer;
            this.m7 = m7;
            this.flight = flight;
            for (Def d : DEFS) pillars.add(new Pillar(this, d));
            processed = cur = world.getGameTime();
            startTick = processed + PRE_ROLL;
        }

        boolean alive() {
            return Bukkit.getWorld(world.getName()) != null && viewer.isOnline();
        }

        Pillar pillar(String name) {
            for (Pillar pl : pillars) if (pl.def.name.equalsIgnoreCase(name)) return pl;
            throw new IllegalArgumentException("pillar E|WS|WN");
        }

        // ---- signals

        private String rel() {
            return p < 0 ? "" : "P" + (cur - p >= 0 ? "+" : "") + (cur - p);
        }

        void log(String what) {
            logLine(cur, rel(), what);
        }

        void chat(String legacy) {
            send(viewer, legacy);
            log("chat: " + Txt.strip(legacy));
        }

        void title(String text, String subtitle) {
            viewer.sendTitlePart(TitlePart.TIMES, Title.Times.times(Duration.ofMillis(0), Duration.ofSeconds(2), Duration.ofMillis(250)));
            viewer.sendTitlePart(TitlePart.TITLE, Txt.of(text));
            if (subtitle != null) viewer.sendTitlePart(TitlePart.SUBTITLE, Txt.of(subtitle));
            log("title: " + Txt.strip(text) + (subtitle == null ? "" : " | " + Txt.strip(subtitle)));
        }

        void clearTitle() {
            viewer.clearTitle();
            log("title cleared");
        }

        void sound(String key, Sound.Source source, float volume, float pitch, double x, double y, double z, String why) {
            viewer.playSound(Sound.sound(Key.key(key), source, volume, pitch), x, y, z);
            log(String.format(Locale.ROOT, "sound %s %s vol %.1f pitch %.4f at %.1f %.1f %.1f (%s)", key, source, volume, pitch, x, y, z, why));
        }

        /** Runs now when the tick has come, else when the master tick reaches it. */
        void at(long abs, Runnable r) {
            if (abs <= cur) r.run();
            else events.computeIfAbsent(abs, k -> new ArrayList<>()).add(r);
        }

        // ---- Storm

        String lineMode() {
            return lineDelay == 0 ? "on time" : lineDelay < 0 ? "off" : lineDelay + " ticks late";
        }

        private void spawnStorm() {
            bar = BossBar.bossBar(Txt.of("§r§c§lStorm"), 1f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS);
            viewer.showBossBar(bar);
            Location at = new Location(world, SPAWN[0], SPAWN[1], SPAWN[2]);
            storm = world.spawn(at, Wither.class, w -> {
                w.setAI(false);
                w.setSilent(true);
                w.setInvulnerable(true);
                w.setGravity(false);
                w.setPersistent(false);
                w.setRemoveWhenFarAway(false);
                w.setCustomNameVisible(false);
                w.customName(Txt.of("Storm"));
                w.setInvulnerableTicks(0);
                if (w.getBossBar() != null) w.getBossBar().setVisible(false);
            });
            trackerWas = Nms.trackEveryTick(storm);
            log("storm spawned at " + SPAWN[0] + " " + SPAWN[1] + " " + SPAWN[2] + " entity id " + storm.getEntityId()
                + (trackerWas > 0 ? " (tracker interval " + trackerWas + " -> 1)" : " (tracker interval unchanged: reflection failed)"));
        }

        void place(double x, double y, double z) {
            double dx = x - sx, dz = z - sz;
            if (storm != null && storm.isValid()) {
                if (dx * dx + dz * dz > 1e-6) storm.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0f);
                Nms.setPos(storm, x, y, z);
            }
            sx = x;
            sy = y;
            sz = z;
        }

        void setMode(String name, Mode m) {
            modeName = name;
            mode = m;
            fbFrom = fbTo = 0;
            modeSeq++;
        }

        Mode go(double tx, double ty, double tz, double speed) {
            return now -> {
                double dx = tx - sx, dy = ty - sy, dz = tz - sz;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d <= speed) place(tx, ty, tz);
                else place(sx + dx / d * speed, sy + dy / d * speed, sz + dz / d * speed);
            };
        }

        Mode chase() {
            return now -> {
                double tx = viewer.getLocation().getX(), tz = viewer.getLocation().getZ();
                double ty = Math.max(172, Math.min(176, viewer.getLocation().getY() + 3));
                double dx = tx - sx, dz = tz - sz, hd = Math.hypot(dx, dz);
                double nx = sx, nz = sz;
                if (hd > HOVER) {
                    double move = Math.min(SPEED, hd - HOVER);
                    nx += dx / hd * move;
                    nz += dz / hd * move;
                }
                place(nx, sy + Math.max(-0.25, Math.min(0.25, ty - sy)), nz);
            };
        }

        /** Piecewise-linear script, times relative to P; holds the last point. */
        Mode walk(double[][] pts) {
            return now -> {
                double t = now - p;
                int i = 0;
                while (i + 1 < pts.length && pts[i + 1][0] <= t) i++;
                if (i + 1 >= pts.length) {
                    place(pts[i][1], pts[i][2], pts[i][3]);
                    return;
                }
                double f = (t - pts[i][0]) / (pts[i + 1][0] - pts[i][0]);
                place(pts[i][1] + (pts[i + 1][1] - pts[i][1]) * f, pts[i][2] + (pts[i + 1][2] - pts[i][2]) * f,
                    pts[i][3] + (pts[i + 1][3] - pts[i][3]) * f);
            };
        }

        void hide(boolean away) {
            if (storm == null) return;
            hidden = away;
            if (away) viewer.hideEntity(plugin, storm);
            else viewer.showEntity(plugin, storm);
            log("storm " + (away ? "hidden" : "shown") + " for the player");
        }

        /**
         * High (fireball) phase on the measured clock (spec 2.3): A = now; straight up 0.8 b/tick to +14.4 b (top at A+18),
         * hold; L = A+162 leave the top (about 5 b diagonally down in 8 ticks), pause, chase from A+190. Fireballs
         * fire on the run-wide 40-tick grid (P+35 mod 40) on grid ticks in [top, L).
         */
        String high() {
            long a = cur;
            double x0 = sx, y0 = sy, z0 = sz;
            double dx = viewer.getLocation().getX() - x0, dz = viewer.getLocation().getZ() - z0, hd = Math.hypot(dx, dz);
            double ux = hd > 1e-6 ? dx / hd : 0, uz = hd > 1e-6 ? dz / hd : 0;
            setMode("high", now -> {
                long t = now - a;
                if (t <= 0) return;
                if (t <= HIGH_CLIMB) place(x0, y0 + Math.min(HIGH_RISE, 0.8 * t), z0);
                else if (t > HIGH_LEAVE && t <= HIGH_LEAVE + 8) {
                    double f = (t - HIGH_LEAVE) / 8.0;
                    place(x0 + ux * 3.5 * f, y0 + HIGH_RISE - 3.5 * f, z0 + uz * 3.5 * f);
                }
            });
            int seq = modeSeq;
            fbFrom = a + HIGH_CLIMB;
            fbTo = a + HIGH_LEAVE;
            log("high A (ascent start) from " + String.format(Locale.ROOT, "%.2f %.2f %.2f", x0, y0, z0) + ", top A+" + HIGH_CLIMB + " = tick " + fbFrom
                + ", leave-top L = A+" + HIGH_LEAVE + " = tick " + fbTo);
            at(a + HIGH_CLIMB, () -> { if (seq == modeSeq) log("high top y" + String.format(Locale.ROOT, "%.2f", sy)); });
            at(a + HIGH_LEAVE, () -> { if (seq == modeSeq) log("high leave-top L = A+" + HIGH_LEAVE); });
            at(a + HIGH_CHASE, () -> { if (seq == modeSeq) { setMode("chase", chase()); log("high phase over (A+" + HIGH_CHASE + "), chasing"); } });
            return "high phase from tick " + a + ": top A+" + HIGH_CLIMB + ", leaves the top at A+" + HIGH_LEAVE + ", chases from A+" + HIGH_CHASE
                + "; fireballs on the P+35 mod 40 grid";
        }

        /** A real fireball entity at Storm's position aimed at you, with the wither shoot sound in the same tick. */
        void fireball() {
            Location l = viewer.getLocation();
            Vector dir = new Vector(l.getX() - sx, l.getY() + 1 - sy, l.getZ() - sz);
            if (dir.lengthSquared() < 1e-6) dir = new Vector(0, -1, 0);
            Vector aim = dir.normalize();
            LargeFireball f = world.spawn(new Location(world, sx, sy, sz), LargeFireball.class, e -> {
                e.setShooter(storm);
                e.setDirection(aim);
                e.setYield(0f);
                e.setIsIncendiary(false);
            });
            fireballs.add(f);
            sound("entity.wither.shoot", Sound.Source.HOSTILE, 1f, 1f, sx, sy, sz, "fireball");
            log("fireball entity id " + f.getEntityId() + " at " + String.format(Locale.ROOT, "%.2f %.2f %.2f", sx, sy, sz));
            at(cur + 80, () -> { if (f.isValid()) f.remove(); });
        }

        /** Later Giga: titles 6..1 25 apart, strike at 6+145; then chase at 6+170, or with {@code high} drift west and climb at 6+173. */
        String gigaPhase(boolean thenHigh) {
            setMode("giga", go(GIGA_SPOT[0], GIGA_SPOT[1], GIGA_SPOT[2], SPEED));
            int seq = modeSeq;
            long first = cur;
            giga(first, 6, false);
            if (!thenHigh) {
                at(first + 170, () -> { if (seq == modeSeq) { setMode("chase", chase()); log("giga over, chasing"); } });
                return "giga countdown 6..1, strike at +145, chase at +170";
            }
            double z = ThreadLocalRandom.current().nextBoolean() ? 48 : 57;
            at(first + 160, () -> {
                if (seq != modeSeq) return;
                setMode("giga drift", go(66, GIGA_SPOT[1], z, 0.6));
                int drift = modeSeq;
                at(first + 173, () -> { if (drift == modeSeq) high(); });
            });
            return "giga countdown 6..1, strike at +145, drift west to (66, " + GIGA_SPOT[1] + ", " + z + "), high phase ascent at +173";
        }

        void giga(long first, int from, boolean opening) {
            for (int i = 0; i < from; i++) {
                int n = from - i;
                boolean firstTitle = i == 0;
                at(first + 25L * i, () -> {
                    title("§r§4" + n, null);
                    Location l = viewer.getLocation();
                    sound("entity.ender_dragon.flap", Sound.Source.HOSTILE, 8f, 0.492f, l.getX(), l.getY(), l.getZ(), "giga countdown");
                    if (opening && firstTitle) chat(POWER);
                });
            }
            long last = first + 25L * (from - 1);
            at(last + 20, () -> chat(GIGA_HIT));
            at(last + 25, this::clearTitle);
            at(opening ? p + 548 : first + 110, () -> chat(opening ? CATALYST : ENERGY));
        }

        // ---- crush

        /** Crush line, Storm's hurt sound, and (with a pillar) its destruction 20 ticks later. */
        void crush(Pillar pillar, long poll) {
            crushes++;
            int n = crushes;
            String line = n == 1 ? OUCH : OOF;
            if (lineDelay == 0) chat(line);
            else if (lineDelay > 0) at(cur + lineDelay, () -> chat(line));
            else log("crush " + n + ": no line");
            if (bar != null) {
                at(cur + BAR_STEP, () -> { bar.progress(n == 1 ? 0.95f : 0.40f); log("boss bar " + bar.progress()); });
                if (n == 1) at(cur + BAR_STEP + 6, () -> { bar.progress(0.45f); log("boss bar 0.45"); });
            }
            sound("entity.wither.hurt", Sound.Source.HOSTILE, 1f, 1f, sx, sy, sz, "crush " + n);
            int seq = modeSeq;
            if (pillar != null) {
                pillar.crushed = true;
                pillar.remaining = 0;
                log("crush " + n + " by pillar " + pillar.def.name + " at poll " + poll + " (P+" + poll * POLL + "), storm at "
                    + String.format(Locale.ROOT, "%.2f %.2f %.2f", sx, sy, sz));
                at(cur + 20, () -> {
                    sound("entity.zombie.break_wooden_door", Sound.Source.HOSTILE, 3f, 1f, pillar.def.x + 3.5, START_Y, pillar.def.z + 3.5,
                        "pillar " + pillar.def.name + " destroyed");
                    pillar.destroy();
                });
            }
            if (n == 1) {
                at(cur + 30, () -> {
                    chat(ENRAGED);
                    viewer.showTitle(Title.title(Txt.of(""), Txt.of(ENRAGED), Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
                    log("title: | " + Txt.strip(ENRAGED));
                });
                at(cur + 20, () -> { if (seq == modeSeq) { setMode("chase", chase()); log("chasing after crush 1"); } });
            } else if (n == 2) {
                at(cur + 26, () -> { ended = true; chat(END); });
            }
        }

        // ---- the clock

        void advance() {
            long now = world.getGameTime();
            while (processed < now) step(++processed);
        }

        private void begin(long tick) {
            p = tick;
            cur = tick;
            chat(PATHETIC);
            spawnStorm();
            double[][] script = flight
                ? new double[][] {{0, SPAWN[0], SPAWN[1], SPAWN[2]}, {45, SPAWN[0], SPAWN[1], SPAWN[2]}, {215, WEST[0], WEST[1], WEST[2]},
                    {385, PARK[0], PARK[1], PARK[2]}, {648, PARK[0], PARK[1], PARK[2]}, {659, IN_E[0], IN_E[1], IN_E[2]}}
                : new double[][] {{0, SPAWN[0], SPAWN[1], SPAWN[2]}, {45, SPAWN[0], SPAWN[1], SPAWN[2]}, {140, PARK[0], PARK[1], PARK[2]},
                    {648, PARK[0], PARK[1], PARK[2]}, {659, IN_E[0], IN_E[1], IN_E[2]}};
            setMode("opening", walk(script));
            int seq = modeSeq;
            giga(p + 424, 4, true);
            at(p + 720, () -> {
                if (seq == modeSeq && crushes == 0) {
                    setMode("chase", chase());
                    log("no crush by P+720, chasing");
                }
            });
        }

        private void step(long tick) {
            cur = tick;
            if (p < 0) {
                if (tick < startTick) return;
                begin(tick);
            }
            mode.step(tick);
            List<Runnable> due = events.remove(tick);
            if (due != null) for (Runnable r : due) r.run();
            if (tick >= fbFrom && tick < fbTo && Math.floorMod(tick - p - FIREBALL_PHASE, 40) == 0) fireball();
            long t = tick - p;
            if (t % POLL == 0) poll(t / POLL);
            for (Pillar pl : pillars) pl.step(tick);
        }

        /** The 3-poll crush check, then pad (or forced) batch starts; layers follow in the same tick. */
        private void poll(long k) {
            if (!ended && crushes < 2) {
                for (Pillar pl : pillars) {
                    if (!pl.usable || pl.crushed || !pl.armed(k) || !pl.inside(sx, sz)) continue;
                    crush(pl, k);
                    break;
                }
            }
            for (Pillar pl : pillars) {
                if (!pl.usable || pl.crushed) continue;
                boolean forced = pl.forcedLayers > 0;
                if (!(forced || pl.onPad(viewer))) continue;
                if (pl.remaining > 0) continue;
                int layers = BATCH;
                if (forced) {
                    if (pl.dir != pl.forcedDir) pl.reversal = false;
                    pl.dir = pl.forcedDir;
                    layers = pl.forcedLayers;
                    pl.forcedLayers = 0;
                }
                if (pl.dir < 0 ? pl.bottom <= FLOOR : pl.bottom >= TOP) {
                    log("poll " + k + ": pillar " + pl.def.name + " is at " + (pl.dir < 0 ? "the floor" : "the top") + ", nothing moves");
                    continue;
                }
                pl.nextLayer = tickOf(k) + (pl.reversal ? REVERSAL : 0);
                pl.reversal = false;
                pl.remaining = layers;
                if (pl.dir < 0) pl.downPolls.add(k);
                log("poll " + k + " (P+" + k * POLL + "): pillar " + pl.def.name + " " + (forced ? "forced " : "on pad ") + (pl.dir < 0 ? "down" : "up")
                    + " x" + layers + " from y" + pl.bottom + ", first layer at P+" + (pl.nextLayer - p));
            }
        }

        private long tickOf(long k) {
            return p + k * POLL;
        }

        void stop() {
            fireballs.forEach(f -> { if (f.isValid()) f.remove(); });
            for (Pillar pl : pillars) pl.restore();
            if (bar != null && viewer.isOnline()) viewer.hideBossBar(bar);
            if (storm != null) {
                if (hidden && viewer.isOnline()) viewer.showEntity(plugin, storm);
                storm.remove();
            }
            if (viewer.isOnline()) viewer.clearTitle();
        }

        List<String> status() {
            long now = world.getGameTime();
            List<String> out = new ArrayList<>();
            if (p < 0) {
                out.add("storm " + (m7 ? "M7" : "F7") + ": starting, P in " + (startTick - now) + " ticks (tick " + startTick + "), now " + now);
                return out;
            }
            long t = now - p, k = t / POLL;
            out.add(String.format(Locale.ROOT, "storm %s: P=%d now=%d (P+%d) poll %d, next poll P+%d in %d ticks, crushes %d%s",
                m7 ? "M7" : "F7", p, now, t, k, (k + 1) * POLL, (k + 1) * POLL - t, crushes, ended ? ", ended" : ""));
            out.add(String.format(Locale.ROOT, "storm at %.2f %.2f %.2f mode %s%s, entity id %d%s", sx, sy, sz, modeName, hidden ? " (hidden for you)" : "",
                storm == null ? -1 : storm.getEntityId(), trackerWas > 0 ? "" : ", tracker interval default"));
            for (Pillar pl : pillars) out.add(pl.describe(k));
            return out;
        }
    }
}
