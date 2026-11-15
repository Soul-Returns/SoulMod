package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code /hx arrows selftest}: plays the device on a virtual clock (the same click calls the live interact handler
 * makes) and solves it only from what the world shows, like a player: every layout identified from its frames and
 * wool, each arrow clicked up to its target, the lanterns checked after every click, an over-rotation (seven more
 * clicks to come back), the wool frames. Expect {@code N checks, 0 failed}.
 */
final class ArrowAlignSelfTest {
    private final ArrowAlignSim sim;
    private final World world;
    private final Player player;
    private final List<String> failures = new ArrayList<>();
    private int checks;
    private String section = "";

    ArrowAlignSelfTest(ArrowAlignSim sim, World world, Player player) {
        this.sim = sim;
        this.world = world;
        this.player = player;
    }

    String run() {
        sim.goldor().set(GoldorStartSim.Phase.UNKNOWN);
        try {
            for (int l = 0; l < ArrowAlignSim.LAYOUTS.length; l++) {
                section = "layout " + (l + 1);
                layout(l);
            }
            section = "before Goldor";
            beforeGoldor();
            section = "over-rotation";
            overRotation();
            section = "wool";
            wool();
            section = "done";
            finished();
        } catch (RuntimeException e) {
            fail("exception " + e);
        }
        sim.goldor().set(GoldorStartSim.Phase.UNKNOWN);
        sim.clear();
        String result = checks + " checks, " + failures.size() + " failed";
        for (String f : failures) result += "\n  " + f;
        return "arrow align selftest: " + result;
    }

    private void check(boolean ok, String what) {
        checks++;
        if (!ok) fail(what);
    }

    private void fail(String what) {
        failures.add(section + ": " + what);
    }

    private long count(String kind, int fromIndex) {
        return sim.events.subList(fromIndex, sim.events.size()).stream().filter(e -> e.split(" ")[1].equals(kind)).count();
    }

    /** The rotation the frame entity shows (what a client reads), not the sim's own state. */
    private int shown(int cell) {
        for (var e : world.getNearbyEntities(new org.bukkit.Location(world, -1.5, y(cell) + 0.5, z(cell) + 0.5), 0.6, 0.6, 0.6)) {
            if (e instanceof org.bukkit.entity.ItemFrame f) return rotationInt(f.getRotation());
        }
        return -1;
    }

    private static int rotationInt(Rotation r) {
        return r.ordinal();
    }

    private static int y(int cell) {
        return ArrowAlignSim.y(cell);
    }

    private static int z(int cell) {
        return ArrowAlignSim.z(cell);
    }

    /**
     * Before Goldor's phase start T: lanterns dark whatever the rotation, and an aligned board does not complete; from T on
     * a full turn of one arrow completes it. Then the first click after T lights every arrow at its target at once.
     */
    private void beforeGoldor() {
        GoldorStartSim goldor = sim.goldor();
        goldor.set(GoldorStartSim.Phase.BEFORE);
        sim.reset(world, player, 6, 4242);
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) if (sim.isArrow(i)) check(!sim.lantern(i), "an arrow lantern is dark before Goldor at " + ArrowAlignSim.name(i));
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) if (sim.hasFrame(i) && !sim.isArrow(i)) check(sim.lantern(i), "a wool lantern is lit before Goldor at " + ArrowAlignSim.name(i));
        long t = sim.tick();
        int first = -1;
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) {
            if (!sim.isArrow(i)) continue;
            if (first < 0) first = i;
            while (sim.rotation(i) != sim.target(i)) sim.click(++t, i);
            check(!sim.lantern(i), "an arrow at its target keeps a dark lantern before Goldor at " + ArrowAlignSim.name(i));
        }
        check(sim.allAligned() && !sim.done(), "aligned before Goldor's phase is not complete");
        int e0 = sim.events.size();
        goldor.set(GoldorStartSim.Phase.ACTIVE);
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) if (sim.isArrow(i)) check(!sim.lantern(i), "nothing lights at Goldor's start at " + ArrowAlignSim.name(i));
        for (int k = 0; k < 8; k++) sim.click(++t, first);
        check(sim.done() && count("complete", e0) == 1, "a full turn from T on completes it, once");
        // The first click afterwards lights every arrow at its target at once.
        goldor.set(GoldorStartSim.Phase.BEFORE);
        sim.reset(world, player, 6, 4243);
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) if (sim.isArrow(i)) while (sim.rotation(i) != (sim.target(i) + 7) % 8 && sim.isArrow(i)) sim.click(++t, i);
        goldor.set(GoldorStartSim.Phase.ACTIVE);
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) if (sim.isArrow(i)) check(!sim.lantern(i), "all dark before the first click at " + ArrowAlignSim.name(i));
        int last = -1;
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) if (sim.isArrow(i)) last = i;
        sim.click(++t, last);
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) if (sim.isArrow(i)) check(sim.lantern(i) == (sim.rotation(i) == sim.target(i)), "one click refreshes every lantern at " + ArrowAlignSim.name(i));
        goldor.set(GoldorStartSim.Phase.UNKNOWN);
    }

    private void layout(int l) {
        sim.reset(world, player, l, 1000 + l);
        int e0 = sim.events.size();
        int frames = 0;
        int arrows = 0;
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) {
            check(sim.hasFrame(i) == (ArrowAlignSim.LAYOUTS[l][i] != -1), "frame " + ArrowAlignSim.name(i) + " present exactly where the layout has one");
            if (sim.hasFrame(i)) frames++;
            if (sim.isArrow(i)) arrows++;
            if (sim.isArrow(i)) check(sim.lantern(i) == (sim.rotation(i) == sim.target(i)), "lantern " + ArrowAlignSim.name(i) + " matches 'at target' at the start");
            if (sim.isArrow(i)) check(shown(i) == sim.rotation(i), "the frame entity shows rotation " + sim.rotation(i) + " at " + ArrowAlignSim.name(i) + ", it shows " + shown(i));
        }
        check(!sim.allAligned() && !sim.done(), "never starts solved");
        long t = sim.tick();
        int needed = 0;
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) {
            if (!sim.isArrow(i)) continue;
            int clicks = (sim.target(i) - sim.rotation(i) + 8) % 8;
            for (int k = 0; k < clicks; k++) {
                sim.click(++t, i);
                needed++;
                check(sim.lantern(i) == (sim.rotation(i) == sim.target(i)), "lantern follows the rotation at " + ArrowAlignSim.name(i));
                check(shown(i) == sim.rotation(i), "the frame entity follows (" + ArrowAlignSim.name(i) + ")");
            }
        }
        check(sim.done(), "solved after " + needed + " clicks");
        check(count("complete", e0) == 1, "completed once");
        check(count("click", e0) == needed, "one click event per click");
        check(frames >= 13 && frames <= 17, "13-17 frames, got " + frames + " (" + arrows + " arrows)");
    }

    private void overRotation() {
        sim.reset(world, player, 6, 77);
        int arrow = -1;
        for (int i = 0; i < ArrowAlignSim.SIZE && arrow < 0; i++) if (sim.isArrow(i)) arrow = i;
        long t = sim.tick();
        int clicks = (sim.target(arrow) - sim.rotation(arrow) + 8) % 8;
        for (int k = 0; k < clicks; k++) sim.click(++t, arrow);
        check(sim.lantern(arrow), "at the target the lantern is lit");
        sim.click(++t, arrow);
        check(!sim.lantern(arrow) && sim.rotation(arrow) == (sim.target(arrow) + 1) % 8, "one click past the target: lantern dark");
        for (int k = 0; k < 6; k++) {
            sim.click(++t, arrow);
            check(!sim.lantern(arrow), "dark on the way round");
        }
        sim.click(++t, arrow);
        check(sim.lantern(arrow) && sim.rotation(arrow) == sim.target(arrow), "seven more clicks bring it back to the target");
    }

    private void wool() {
        sim.reset(world, player, 6, 78);
        int e0 = sim.events.size();
        int start = 0;
        check(ArrowAlignSim.LAYOUTS[6][start] == -2 && sim.hasFrame(start), "layout 7 starts with the Start wool at r1c1");
        sim.click(sim.tick() + 1, start);
        check(count("ignored", e0) == 1 && count("click", e0) == 0, "a click on a wool frame does nothing");
        check(sim.lantern(start), "the wool frame's lantern stays lit");
    }

    private void finished() {
        sim.reset(world, player, 6, 79);
        long t = sim.tick();
        for (int i = 0; i < ArrowAlignSim.SIZE; i++) {
            if (!sim.isArrow(i)) continue;
            int clicks = (sim.target(i) - sim.rotation(i) + 8) % 8;
            for (int k = 0; k < clicks; k++) sim.click(++t, i);
        }
        check(sim.done(), "done");
        int e0 = sim.events.size();
        int arrow = -1;
        for (int i = 0; i < ArrowAlignSim.SIZE && arrow < 0; i++) if (sim.isArrow(i)) arrow = i;
        int before = sim.rotation(arrow);
        sim.click(++t, arrow);
        check(sim.rotation(arrow) == before && count("ignored", e0) == 1, "the finished device ignores clicks");
    }
}
