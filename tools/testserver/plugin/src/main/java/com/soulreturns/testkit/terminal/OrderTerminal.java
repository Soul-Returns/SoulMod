package com.soulreturns.testkit.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * {@code §rClick in order!}, {@code generic_9x4}, 2x5 grid at slots 11-15, 20-24: ten red panes named
 * {@code §r§a1}..{@code §r§a10} whose count equals the number. Ascending clicks turn them lime. A wrong number
 * plays the error sound and prints {@code Wrong number!}; nothing is reset and the menu stays open (captured).
 * Assumption: a click on an already lime pane or outside the grid does nothing.
 */
public final class OrderTerminal extends Terminal {
    public static final int[] GRID = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
    public static final String WRONG = "§r§cWrong number!";

    private int next = 1;

    private OrderTerminal() {
        super(4);
    }

    static Cell pane(int number, boolean done) {
        return Cell.of(done ? "lime_stained_glass_pane" : "red_stained_glass_pane", "§r§a" + number, number);
    }

    public static OrderTerminal random(Random rng) {
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i <= GRID.length; i++) numbers.add(i);
        Collections.shuffle(numbers, rng);
        OrderTerminal terminal = new OrderTerminal();
        for (int i = 0; i < GRID.length; i++) terminal.cells[GRID[i]] = pane(numbers.get(i), false);
        return terminal;
    }

    public static OrderTerminal of(Map<Integer, Integer> numbers) {
        OrderTerminal terminal = new OrderTerminal();
        numbers.forEach((slot, number) -> terminal.cells[slot] = pane(number, false));
        return terminal;
    }

    public int next() {
        return next;
    }

    @Override
    public Kind kind() {
        return Kind.ORDER;
    }

    @Override
    public String title() {
        return "§rClick in order!";
    }

    @Override
    public Reply click(int slot, Input input) {
        if (!inRange(slot, 11, 15, 20, 24)) return Reply.IGNORED;
        Cell cell = cells[slot];
        if (cell.id().equals("lime_stained_glass_pane")) return Reply.IGNORED;
        if (cell.count() != next) return new Reply(Outcome.FAIL_STAY, Map.of(), WRONG, 0);
        Reply reply = accept(slot, pane(next, true));
        next++;
        return next > GRID.length ? complete(2) : reply;
    }
}
