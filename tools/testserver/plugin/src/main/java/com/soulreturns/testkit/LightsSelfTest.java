package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code /hx lights selftest}: plays the device on a virtual clock (the same flip and step calls the live
 * interact handler makes) and solves it only from what the world shows, like a player reading the lamps: the
 * six-lever solution in several orders, a lever flicked twice, a lever outside the six, every lever, a device
 * finished early. Expect {@code N checks, 0 failed}.
 */
final class LightsSelfTest {
    private final LightsSim sim;
    private final World world;
    private final Player player;
    private final List<String> failures = new ArrayList<>();
    private int checks;
    private String section = "";

    LightsSelfTest(LightsSim sim, World world, Player player) {
        this.sim = sim;
        this.world = world;
        this.player = player;
    }

    String run() {
        sim.goldor().set(GoldorStartSim.Phase.UNKNOWN);
        try {
            section = "solution";
            solution();
            section = "orders";
            orders();
            section = "repeat";
            repeat();
            section = "sneaking";
            sneaking();
            section = "extra";
            extra();
            section = "all";
            all();
            section = "prefinished";
            prefinished();
            section = "before Goldor";
            beforeGoldor();
        } catch (RuntimeException e) {
            fail("exception " + e);
        }
        sim.goldor().set(GoldorStartSim.Phase.UNKNOWN);
        String result = checks + " checks, " + failures.size() + " failed";
        for (String f : failures) result += "\n  " + f;
        return "lights selftest: " + result;
    }

    private void check(boolean ok, String what) {
        checks++;
        if (!ok) fail(what);
    }

    private void fail(String what) {
        failures.add(section + ": " + what);
    }

    private int lampCount() {
        int n = 0;
        for (int i = 0; i < 20; i++) if (sim.lamp(i)) n++;
        return n;
    }

    private int leverCount() {
        int n = 0;
        for (int i = 0; i < 20; i++) if (sim.lever(i)) n++;
        return n;
    }

    private long count(String kind, int fromIndex) {
        return sim.events.subList(fromIndex, sim.events.size()).stream().filter(e -> e.split(" ")[1].equals(kind)).count();
    }

    /** Flips the cells 3 ticks apart, one step after each, checking lamps only ever get lit. Returns the tick after. */
    private long flips(List<Integer> cells, long t) {
        int before = lampCount();
        for (int c : cells) {
            t += 3;
            sim.flip(t, c);
            sim.step(t + 1);
            int now = lampCount();
            check(now >= before, "lamps never darken (" + before + " -> " + now + " after " + LightsSim.name(c) + ")");
            before = now;
        }
        return t + 2;
    }

    private List<Integer> solutionCells() {
        List<Integer> l = new ArrayList<>();
        for (int c : LightsSim.SOLUTION) l.add(c);
        return l;
    }

    private void solution() {
        sim.reset(world, player);
        check(leverCount() == 0 && lampCount() == 0, "starts with every lever off and every lamp dark");
        int e0 = sim.events.size();
        long t = sim.now() + 1;
        List<Integer> cells = solutionCells();
        // After each flip exactly its plus shape is lit (union so far).
        boolean[] expect = new boolean[20];
        for (int c : cells) {
            t += 3;
            sim.flip(t, c);
            check(sim.lever(c), LightsSim.name(c) + " turns on at once");
            check(expect[c] || !sim.lamp(c), LightsSim.name(c) + "'s lamp is not lit in the flip's own tick (the next one lights it)");
            sim.step(t + 1);
            for (int p : LightsSim.plus(c)) expect[p] = true;
            int want = 0;
            for (boolean b : expect) if (b) want++;
            check(lampCount() == want, "lit lamps " + lampCount() + ", want " + want + " after " + LightsSim.name(c));
        }
        check(lampCount() == 20, "all 20 lamps lit after the six flips, got " + lampCount());
        check(sim.done(), "device done");
        check(count("complete", e0) == 1, "completed once");
        check(leverCount() == 6, "six levers on, got " + leverCount());
    }

    private void orders() {
        // Four more orders of the same six, reversed and rotated.
        for (int k = 0; k < 4; k++) {
            sim.reset(world, player);
            int e0 = sim.events.size();
            List<Integer> cells = solutionCells();
            java.util.Collections.rotate(cells, k * 2);
            if (k % 2 == 1) java.util.Collections.reverse(cells);
            flips(cells, sim.now() + 1);
            check(sim.done() && lampCount() == 20, "order " + k + " completes");
            check(count("complete", e0) == 1, "order " + k + ": one completion");
        }
    }

    private void repeat() {
        sim.reset(world, player);
        long t = sim.now() + 1;
        int c = LightsSim.SOLUTION[0];
        sim.flip(t + 3, c);
        sim.step(t + 4);
        check(lampCount() == 3, "a corner lever lights three lamps, got " + lampCount());
        sim.flip(t + 6, c);
        sim.step(t + 8);
        check(!sim.lever(c) && lampCount() == 0, "a second flick turns the lever and its lamps off, got " + lampCount() + " lamps");
        // Two levers sharing a lamp: flicking one back leaves the shared lamp lit (the other still covers it).
        int a = LightsSim.index(60, 135);
        int b = LightsSim.index(60, 134);
        sim.flip(t + 10, a);
        sim.flip(t + 12, b);
        sim.step(t + 14);
        int both = lampCount();
        sim.flip(t + 16, a);
        sim.step(t + 18);
        check(sim.lamp(b) && sim.lamp(a), "lamps lit by two levers stay lit when one is flicked back");
        check(lampCount() < both, "the lamps only the flicked-back lever covered went dark (" + both + " -> " + lampCount() + ")");
    }

    private void sneaking() {
        sim.reset(world, player);
        long t = sim.now() + 1;
        int i = LightsSim.index(62, 136);
        sim.touch(t, i, true);
        check(!sim.lever(i) && lampCount() == 0, "a sneaking click is ignored: the lever stays off and no lamp lights");
        sim.touch(t + 1, i, false);
        check(sim.lever(i), "the same lever flips when clicked standing");
    }

    private void extra() {
        sim.reset(world, player);
        long t = sim.now() + 1;
        int wrong = LightsSim.index(61, 135);
        t = flips(List.of(wrong), t);
        check(sim.lever(wrong) && lampCount() == 5, "a lever outside the six lights its five lamps, got " + lampCount());
        t = flips(solutionCells(), t);
        check(sim.done(), "the six on top of it still complete the device");
    }

    private void all() {
        sim.reset(world, player);
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < 20; i++) cells.add(i);
        int e0 = sim.events.size();
        flips(cells, sim.now() + 1);
        check(sim.done() && count("complete", e0) == 1, "all 20 levers: done once");
        check(lampCount() == 20 && leverCount() < 20, "all lamps lit, and the levers after the completing flip stay off (the finished device does nothing), levers on: " + leverCount());
    }

    private void prefinished() {
        sim.reset(world, player, true);
        int e0 = sim.events.size();
        check(lampCount() == 20 && leverCount() == 6, "pre-finished: every lamp lit, the usual six levers on");
        check(!sim.done(), "not complete yet");
        long t = sim.now() + 1;
        int any = LightsSim.index(61, 135);
        sim.flip(t + 3, any);
        sim.step(t + 4);
        check(sim.done() && count("complete", e0) == 1, "touching any lever completes it, once");
        check(sim.lever(any) && lampCount() == 20, "the lever is on, every lamp stays lit");
    }

    /** Lit before Goldor's phase start T it stays pre-finished; from T on the first touch completes it. */
    private void beforeGoldor() {
        GoldorStartSim goldor = sim.goldor();
        goldor.set(GoldorStartSim.Phase.BEFORE);
        sim.reset(world, player);
        int e0 = sim.events.size();
        long t = sim.now() + 1;
        for (int i : LightsSim.SOLUTION) sim.flip(t++, i);
        sim.step(t++);
        check(lampCount() == 20 && !sim.done() && count("complete", e0) == 0, "lit before T: pre-finished, no completion");
        goldor.set(GoldorStartSim.Phase.ACTIVE);
        int any = LightsSim.index(61, 135);
        sim.flip(t++, any);
        sim.step(t);
        check(sim.done() && count("complete", e0) == 1, "from T on a touch completes it, once");
    }
}
