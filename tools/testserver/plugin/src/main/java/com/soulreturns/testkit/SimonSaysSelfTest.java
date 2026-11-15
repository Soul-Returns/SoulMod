package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code /hx ss selftest}: plays the device on a virtual clock (the same press and step calls the live
 * interact handler makes) and solves it only from what the world shows, like a player reading the lamps:
 * a normal run, the three-press skip, four presses, a wrong button and a slow second press. Expect
 * {@code N checks, 0 failed}.
 */
final class SimonSaysSelfTest {
    private final SimonSaysSim sim;
    private final World world;
    private final Player player;
    private final List<String> failures = new ArrayList<>();
    private int checks;
    private String section = "";

    SimonSaysSelfTest(SimonSaysSim sim, World world, Player player) {
        this.sim = sim;
        this.world = world;
        this.player = player;
    }

    String run() {
        try {
            section = "normal";
            normal();
            section = "skip";
            skip();
            section = "four";
            four();
            section = "wrong";
            wrong();
            section = "slow";
            slow();
        } catch (RuntimeException e) {
            fail("exception " + e);
        }
        String result = checks + " checks, " + failures.size() + " failed";
        for (String f : failures) result += "\n  " + f;
        return "simon says selftest: " + result;
    }

    private void check(boolean ok, String what) {
        checks++;
        if (!ok) fail(what);
    }

    private void fail(String what) {
        failures.add(section + ": " + what);
    }

    // ---- reading the world like a client ----

    private int lampMask() {
        int m = 0;
        for (int i = 0; i < 16; i++) if (sim.lamp(i)) m |= 1 << i;
        return m;
    }

    private int buttons() {
        int n = 0;
        for (int i = 0; i < 16; i++) if (sim.button(i)) n++;
        return n;
    }

    /** One display as seen: lamps in order of lighting, and how many buttons were up while a lamp was still lit. */
    private record Seen(List<Integer> lamps, int buttonsWhileLit, long end) {}

    /**
     * Steps until the buttons are up and no lamp is lit (or the device is complete / the limit passes), pressing
     * the start button at {@code starts} (ticks) on the way, so lamps lit between presses are seen too.
     */
    private Seen watch(long from, int limit, long... starts) {
        List<Integer> lamps = new ArrayList<>();
        int prev = lampMask();
        int whileLit = 0;
        for (long t = from; t < from + limit; t++) {
            for (long s : starts) if (s == t) sim.startPress(t);
            sim.step(t);
            int m = lampMask();
            int on = m & ~prev;
            for (int i = 0; i < 16; i++) if ((on & (1 << i)) != 0) lamps.add(i);
            prev = m;
            int b = buttons();
            if (m != 0 && b >= 8) whileLit = Math.max(whileLit, b);
            // A display was seen and is over: its buttons are up, no lamp lit.
            if (b == 16 && m == 0 && !lamps.isEmpty()) return new Seen(lamps, whileLit, t);
            if (sim.state().equals("DONE")) return new Seen(lamps, whileLit, t);
        }
        return new Seen(lamps, whileLit, from + limit);
    }

    private long count(String kind, int fromIndex) {
        return sim.events.subList(fromIndex, sim.events.size()).stream().filter(e -> e.split(" ")[1].equals(kind)).count();
    }

    /** Presses the lamps from index {@code skip} on, 6 ticks apart; returns the tick after the last. */
    private long press(List<Integer> lamps, int skip, long t) {
        for (int k = skip; k < lamps.size(); k++) {
            t += 6;
            sim.buttonPress(t, lamps.get(k));
        }
        return t;
    }

    /** Solves rounds until complete; returns the shown lamp counts. */
    private List<Integer> finish(long t, List<Integer> sizes) {
        for (int guard = 0; guard < 6 && !sim.state().equals("DONE"); guard++) {
            Seen s = watch(t, 200);
            if (sim.state().equals("DONE")) break;
            sizes.add(s.lamps.size());
            t = press(s.lamps, 0, s.end);
        }
        sim.step(t + 10);
        return sizes;
    }

    // ---- scenarios ----

    private void normal() {
        sim.reset(world, player, 101);
        int e0 = sim.events.size();
        long t = sim.now() + 1;
        sim.startPress(t);
        List<Integer> sizes = finish(t, new ArrayList<>());
        check(sizes.equals(List.of(1, 2, 3, 4)), "rounds " + sizes + ", want [1, 2, 3, 4]");
        check(count("error", e0) == 0, "no error sound");
        check(count("pling", e0) == 10, "10 press plings, got " + count("pling", e0));
        check(count("complete", e0) == 1, "completed once");
        check(sim.state().equals("DONE"), "state DONE, got " + sim.state());
        check(buttons() == 0 && lampMask() == 0, "buttons removed, lamps out after completion");
    }

    private void skip() {
        sim.reset(world, player, 202);
        int e0 = sim.events.size();
        long t = sim.now() + 1;
        Seen s = watch(t, 200, t, t + 3, t + 6);
        check(s.lamps.size() == 3, "skip display shows 3 lamps, got " + s.lamps);
        check(s.buttonsWhileLit == 15, "buttons appear (15) while the third lamp is lit, got " + s.buttonsWhileLit);
        check(count("error", e0) == 1, "one error sound (the second press), got " + count("error", e0));
        check(sim.expected().equals(s.lamps.subList(1, 3)), "server expects lamps 2-3 " + s.lamps.subList(1, 3) + ", it expects " + sim.expected());
        int e1 = sim.events.size();
        int bad = s.lamps.get(0).equals(s.lamps.get(1)) ? (s.lamps.get(1) + 1) % 16 : s.lamps.get(0);
        sim.buttonPress(s.end + 6, bad);
        check(count("error", e1) == 1, "the first lamp's button is wrong");
        // Again, solved right this time.
        sim.reset(world, player, 202);
        e0 = sim.events.size();
        t = sim.now() + 1;
        s = watch(t, 200, t, t + 3, t + 6);
        long end = press(s.lamps, 1, s.end);
        check(count("pling", e0) == 2, "both skip presses accepted");
        List<Integer> sizes = finish(end, new ArrayList<>(List.of(3)));
        check(sizes.equals(List.of(3, 3, 4)), "rounds " + sizes + ", want [3, 3, 4]");
        check(count("complete", e0) == 1, "completed");
    }

    private void four() {
        sim.reset(world, player, 303);
        int e0 = sim.events.size();
        long t = sim.now() + 1;
        Seen s = watch(t, 200, t, t + 3, t + 6, t + 10);
        check(count("error", e0) == 2, "error sounds on presses 2 and 4, got " + count("error", e0));
        check(s.lamps.size() < 3, "the second display is cut (fewer than 3 lamps), got " + s.lamps);
        check(sim.state().equals("BROKEN"), "state BROKEN, got " + sim.state());
        int e1 = sim.events.size();
        for (int i = 0; i < 16; i++) sim.buttonPress(s.end + 5 + i, i);
        check(count("pling", e1) == 0 && count("error", e1) == 0, "presses on the cut round do nothing");
        long t2 = s.end + 40;
        sim.startPress(t2);
        Seen r = watch(t2 + 1, 200);
        check(r.lamps.size() == 2, "the restart shows two lamps (round 2), got " + r.lamps);
        long end = press(r.lamps, 0, r.end);
        List<Integer> sizes = finish(end, new ArrayList<>(List.of(2)));
        check(sizes.equals(List.of(2, 3, 4)), "rounds " + sizes + ", want [2, 3, 4]");
        check(count("complete", e0) == 1, "completed");
    }

    private void wrong() {
        sim.reset(world, player, 404);
        int e0 = sim.events.size();
        long t = sim.now() + 1;
        sim.startPress(t);
        Seen s = watch(t + 1, 200);
        int bad = (s.lamps.get(0) + 1) % 16;
        sim.buttonPress(s.end + 5, bad);
        check(count("error", e0) == 1, "wrong button: error sound");
        check(buttons() == 0, "wrong button: buttons removed");
        sim.startPress(s.end + 20);
        Seen r = watch(s.end + 21, 200);
        check(r.lamps.size() == 1, "back to round 1, got " + r.lamps);
    }

    private void slow() {
        sim.reset(world, player, 505);
        int e0 = sim.events.size();
        long t = sim.now() + 1;
        sim.startPress(t);
        Seen s = watch(t + 1, 200);
        sim.startPress(s.end + 8);
        check(count("error", e0) == 1, "a start press during the input: error sound");
        check(buttons() == 0 && sim.state().equals("IDLE"), "reset to idle");
        sim.startPress(s.end + 30);
        Seen r = watch(s.end + 31, 200);
        check(r.lamps.size() == 1, "round 1 again, got " + r.lamps);
    }
}
