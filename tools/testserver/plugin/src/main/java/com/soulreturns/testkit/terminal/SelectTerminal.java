package com.soulreturns.testkit.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * {@code §rSelect all the <COLOR> items!}, {@code generic_9x6}, 4x7 board at slots 10-16, 19-25, 28-34, 37-43
 * of wool, stained clay, stained glass and dyes. Accepted click: glint-override patch, like starts-with.
 * Wrong color: closes the menu like a wrong starts-with item (user report, not captured); the error sound and
 * the {@code Does not match!} chat line are assumed to be the same as starts-with's. Assumption: a click on a
 * selected item or outside the board does nothing.
 */
public final class SelectTerminal extends Terminal {
    public static final int[] BOARD = {
        10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private final Pools.Hue target;

    private SelectTerminal(Pools.Hue target) {
        super(6);
        this.target = target;
    }

    /** Random board: 5 or 6 target items (captured: 5, 6, 6), distractors from four other colors. */
    public static SelectTerminal random(Random rng, Pools.Hue requested) {
        Pools.Hue target = requested != null ? requested : Pools.OBSERVED_TARGETS.get(rng.nextInt(Pools.OBSERVED_TARGETS.size()));
        List<Pools.Hue> others = new ArrayList<>(Pools.HUES);
        others.remove(target);
        Collections.shuffle(others, rng);
        others = others.subList(0, 4);
        int hits = 5 + rng.nextInt(2);
        List<Cell> items = new ArrayList<>();
        for (int i = 0; i < hits; i++) items.add(target.item(rng.nextInt(Pools.FORMS)));
        while (items.size() < BOARD.length) items.add(others.get(rng.nextInt(others.size())).item(rng.nextInt(Pools.FORMS)));
        Collections.shuffle(items, rng);
        SelectTerminal terminal = new SelectTerminal(target);
        for (int i = 0; i < BOARD.length; i++) terminal.cells[BOARD[i]] = items.get(i);
        return terminal;
    }

    public static SelectTerminal of(Pools.Hue target, Map<Integer, String> names) {
        SelectTerminal terminal = new SelectTerminal(target);
        names.forEach((slot, name) -> terminal.cells[slot] = Pools.item(name));
        return terminal;
    }

    public Pools.Hue target() {
        return target;
    }

    public boolean matches(Cell cell) {
        return Pools.hueOfName(cell.plainName()) == target;
    }

    @Override
    public Kind kind() {
        return Kind.SELECT;
    }

    @Override
    public String title() {
        return "§rSelect all the " + target.title() + " items!";
    }

    @Override
    public Reply click(int slot, Input input) {
        if (!inRange(slot, 10, 16, 19, 25, 28, 34, 37, 43)) return Reply.IGNORED;
        Cell cell = cells[slot];
        if (cell.selected()) return Reply.IGNORED;
        if (!matches(cell)) return new Reply(Outcome.FAIL_CLOSE, Map.of(), StartsWithTerminal.WRONG, 0);
        Reply reply = accept(slot, cell.withSelected(true));
        return remaining() == 0 ? complete(2) : reply;
    }

    public int remaining() {
        int left = 0;
        for (int slot : BOARD) if (matches(cells[slot]) && !cells[slot].selected()) left++;
        return left;
    }
}
