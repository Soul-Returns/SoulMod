package com.soulreturns.testkit.terminal;

import java.util.Map;
import java.util.Random;

/**
 * {@code §rChange all to same color!}, {@code generic_9x5}, 3x3 grid at slots 12-14, 21-23, 30-32 of colored panes.
 * Left click (PICKUP, PICKUP_ALL, drag phase, middle) cycles Red, Orange, Yellow, Green, Blue, Red; right click
 * goes backwards (all captured). Done when all nine match, any color (Green, Red and Blue were seen finishing);
 * there is no error response (captured: mismatches just stay).
 */
public final class RubixTerminal extends Terminal {
    public static final int[] GRID = {12, 13, 14, 21, 22, 23, 30, 31, 32};
    public static final String[] COLORS = {"Red", "Orange", "Yellow", "Green", "Blue"};

    private final int[] color = new int[cells.length];

    private RubixTerminal() {
        super(5);
    }

    static Cell pane(int color) {
        return Cell.of(COLORS[color].toLowerCase(java.util.Locale.ROOT) + "_stained_glass_pane", "§r§a" + COLORS[color]);
    }

    public static int indexOf(String name) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i].equalsIgnoreCase(name)) return i;
        throw new IllegalArgumentException("unknown rubix color " + name);
    }

    /** Every pane random; re-rolled while all nine happen to match. */
    public static RubixTerminal random(Random rng) {
        int[] picks = new int[GRID.length];
        boolean same;
        do {
            same = true;
            for (int i = 0; i < picks.length; i++) {
                picks[i] = rng.nextInt(COLORS.length);
                same &= picks[i] == picks[0];
            }
        } while (same);
        RubixTerminal terminal = new RubixTerminal();
        for (int i = 0; i < GRID.length; i++) terminal.set(GRID[i], picks[i]);
        return terminal;
    }

    public static RubixTerminal of(Map<Integer, Integer> colors) {
        RubixTerminal terminal = new RubixTerminal();
        colors.forEach(terminal::set);
        return terminal;
    }

    private void set(int slot, int value) {
        color[slot] = value;
        cells[slot] = pane(value);
    }

    public int colorAt(int slot) {
        return color[slot];
    }

    @Override
    public Kind kind() {
        return Kind.RUBIX;
    }

    @Override
    public String title() {
        return "§rChange all to same color!";
    }

    @Override
    public Reply click(int slot, Input input) {
        if (!inRange(slot, 12, 14, 21, 23, 30, 32)) return Reply.IGNORED;
        int next = Math.floorMod(color[slot] + (input.backward() ? -1 : 1), COLORS.length);
        color[slot] = next;
        Reply reply = accept(slot, pane(next));
        for (int other : GRID) if (color[other] != next) return reply;
        return complete(2);
    }
}
