package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code /hx sharp selftest}: plays the device on a virtual clock (the same calls the live handler makes, the plate's
 * power set by hand) and reads only the nine target blocks like a client: runs of 7 or 8 distinct targets, one
 * emerald block at a time, each hit answered with the next target within 10 ticks, the completion line and pling
 * at the last hit, a wrong cell and a hit with no target ignored, the plate freed mid-run, a finished device that
 * waits for a free plate. Expect {@code N checks, 0 failed}.
 */
final class SharpshooterSelfTest {
    private final SharpshooterSim sim;
    private final World world;
    private final Player player;
    private final List<String> failures = new ArrayList<>();
    private int checks;
    private String section = "";
    private long t;

    SharpshooterSelfTest(SharpshooterSim sim, World world, Player player) {
        this.sim = sim;
        this.world = world;
        this.player = player;
    }

    String run() {
        try {
            for (int k = 0; k < 6; k++) {
                section = "run " + (k + 1);
                fullRun();
            }
            section = "wrong cell";
            wrongCell();
            section = "plate freed";
            plateFreed();
            section = "after the run";
            afterTheRun();
            section = "not armed";
            standingOnThePlate();
        } catch (RuntimeException e) {
            fail("exception " + e);
        }
        sim.clear();
        String result = checks + " checks, " + failures.size() + " failed";
        for (String f : failures) result += "\n  " + f;
        return "sharpshooter selftest: " + result;
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

    /** What a client reads: the cells that are an emerald block, and whether all nine are the device's own blocks. */
    private List<Integer> emeralds() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < SharpshooterSim.SIZE; i++) {
            Material m = sim.cell(i).getType();
            if (m == Material.EMERALD_BLOCK) out.add(i);
            else check(m == Material.BLUE_TERRACOTTA, "cell " + SharpshooterSim.name(i) + " is " + m + ", neither emerald nor blue terracotta");
        }
        return out;
    }

    private void tick() {
        sim.tick(++t);
    }

    private void start() {
        sim.reset(world, player);
        sim.plateOverride = 0;
        t = world.getGameTime();
        tick();
    }

    /** Steps on the plate and ticks until a target shows (at most 12 ticks). */
    private int step() {
        sim.plateOverride = 1;
        for (int k = 0; k < 14; k++) {
            tick();
            List<Integer> e = emeralds();
            if (!e.isEmpty()) return e.get(0);
        }
        return -1;
    }

    private void fullRun() {
        start();
        check(sim.current() == -1 && emeralds().isEmpty(), "no target with the plate free");
        int e0 = sim.events.size();
        int cur = step();
        check(cur >= 0, "a target shows within 12 ticks of the plate being pressed");
        Set<Integer> seen = new HashSet<>();
        int n = 0;
        while (cur >= 0 && n < 12) {
            check(seen.add(cur), "cell " + SharpshooterSim.name(cur) + " was the target twice");
            check(emeralds().size() == 1, "exactly one emerald block at a time, " + emeralds().size() + " now");
            check(sim.current() == cur, "the sim's target is the emerald block");
            n++;
            sim.hit(t, cur);
            check(sim.cell(cur).getType() == Material.BLUE_TERRACOTTA, "the hit cell is blue terracotta again at once");
            int waited = 0;
            cur = -1;
            while (waited < 12 && !sim.finished()) {
                tick();
                waited++;
                List<Integer> e = emeralds();
                if (!e.isEmpty()) {
                    cur = e.get(0);
                    break;
                }
            }
            if (cur >= 0) check(waited <= 11, "the next target came after " + waited + " ticks");
        }
        check(n == 7 || n == 8, n + " targets in the run");
        check(n == sim.total(), "all " + sim.total() + " targets were shown and hit");
        check(sim.finished() && emeralds().isEmpty(), "finished with no emerald left");
        check(count("complete", e0) == 1, "one completion line and pling");
        check(count("target", e0) == n && count("hit", e0) == n, "every target hit once");
        sim.plateOverride = 0;
        tick();
        check(!sim.finished() && !sim.running(), "ready again once the plate was free");
    }

    private void wrongCell() {
        start();
        int cur = step();
        int other = (cur + 1) % SharpshooterSim.SIZE;
        sim.hit(t, other);
        check(sim.cell(cur).getType() == Material.EMERALD_BLOCK && sim.current() == cur, "a hit on another cell leaves the target");
        check(sim.cell(other).getType() == Material.BLUE_TERRACOTTA, "the other cell stays blue terracotta");
        sim.hit(t, cur);
        sim.hit(t, cur);
        check(count("ignored", 0) >= 2, "a hit on a cell with no target is ignored");
    }

    private void plateFreed() {
        start();
        int cur = step();
        check(cur >= 0, "target shown");
        sim.plateOverride = 0;
        tick();
        check(emeralds().isEmpty() && !sim.running(), "stepping off the plate clears the target and stops the run");
        sim.plateOverride = 1;
        tick();
        check(sim.running(), "stepping on again starts a new run");
    }

    private void afterTheRun() {
        start();
        int cur = step();
        while (cur >= 0 && !sim.finished()) {
            sim.hit(t, cur);
            cur = -1;
            for (int k = 0; k < 12 && !sim.finished() && cur < 0; k++) {
                tick();
                List<Integer> e = emeralds();
                if (!e.isEmpty()) cur = e.get(0);
            }
        }
        check(sim.finished(), "finished");
        for (int k = 0; k < 40; k++) tick();
        check(sim.finished() && emeralds().isEmpty(), "a finished device does not start again while the plate is pressed");
        sim.plateOverride = 0;
        tick();
        sim.plateOverride = 1;
        tick();
        check(sim.running(), "a free plate and a new press start a new run");
    }

    private void standingOnThePlate() {
        sim.reset(world, player);
        sim.plateOverride = 1;
        t = world.getGameTime();
        for (int k = 0; k < 14; k++) tick();
        check(!sim.running() && emeralds().isEmpty(), "standing on the plate when the device is set up does not start it");
        sim.plateOverride = 0;
        tick();
        sim.plateOverride = 2;
        tick();
        check(sim.running(), "pressing it after a free plate starts it (power 2 as well as 1)");
    }
}
