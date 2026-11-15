package com.soulreturns.testkit.terminal;

import java.util.Random;
import java.util.Set;

/**
 * {@code §rCorrect all the panes!}, {@code generic_9x5}, 3x5 grid at slots 11-15, 20-24, 29-33: red {@code Off}
 * and lime {@code On} panes. A click toggles both ways (captured: On to Off is accepted with the same pling);
 * done when all 15 are On. Clicks outside the grid do nothing (assumption).
 */
public final class PanesTerminal extends Terminal {
    public static final int[] GRID = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24, 29, 30, 31, 32, 33};
    static final Cell OFF = Cell.of("red_stained_glass_pane", "§r§cOff");
    static final Cell ON = Cell.of("lime_stained_glass_pane", "§r§aOn");

    private PanesTerminal() {
        super(5);
        for (int slot : GRID) cells[slot] = OFF;
    }

    /** 1 to 4 panes start On (captured 1, 4, 2, 1, 2, 2). */
    public static PanesTerminal random(Random rng) {
        PanesTerminal terminal = new PanesTerminal();
        int on = 1 + rng.nextInt(4);
        for (int i = 0; i < on; ) {
            int slot = GRID[rng.nextInt(GRID.length)];
            if (terminal.cells[slot] == OFF) {
                terminal.cells[slot] = ON;
                i++;
            }
        }
        return terminal;
    }

    public static PanesTerminal of(Set<Integer> onSlots) {
        PanesTerminal terminal = new PanesTerminal();
        for (int slot : onSlots) terminal.cells[slot] = ON;
        return terminal;
    }

    @Override
    public Kind kind() {
        return Kind.PANES;
    }

    @Override
    public String title() {
        return "§rCorrect all the panes!";
    }

    @Override
    public Reply click(int slot, Input input) {
        if (!inRange(slot, 11, 15, 20, 24, 29, 33)) return Reply.IGNORED;
        Reply reply = accept(slot, cells[slot] == ON ? OFF : ON);
        return remaining() == 0 ? complete(2) : reply;
    }

    public int remaining() {
        int off = 0;
        for (int slot : GRID) if (cells[slot] != ON) off++;
        return off;
    }
}
