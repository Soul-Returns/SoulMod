package com.soulreturns.testkit.terminal;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side model of one F7 terminal: the board, the rules, nothing about Bukkit. A click yields a
 * {@link Reply}; the melody terminal also advances on {@link #tick()}. Facts come from
 * docs/research/f7-native-terminals-2026-11-12.md; what that capture does not show is marked "assumption".
 */
public abstract class Terminal {
    public enum Kind {
        STARTSWITH("startswith"), SELECT("select"), PANES("panes"), ORDER("order"), RUBIX("rubix"), MELODY("melody");

        public final String key;

        Kind(String key) {
            this.key = key;
        }

        public static Kind of(String key) {
            for (Kind kind : values()) if (kind.key.equalsIgnoreCase(key)) return kind;
            throw new IllegalArgumentException("unknown terminal type " + key + " (" + String.join(", ", keys()) + ")");
        }

        public static String[] keys() {
            return Arrays.stream(values()).map(k -> k.key).toArray(String[]::new);
        }
    }

    /**
     * How the click arrived. Captured: left PICKUP, right PICKUP, PICKUP_ALL (double click), QUICK_CRAFT drag
     * phase (accepted as a forward action) and, in the earlier run, CLONE (middle). Shift, number key and
     * drop clicks were never seen: they count as forward (assumption).
     */
    public enum Input { LEFT, RIGHT, DOUBLE, DRAG, MIDDLE, OTHER;
        public boolean backward() {
            return this == RIGHT;
        }
    }

    public enum Outcome {
        /** Nothing happens (filler, outside the board, already-done slot): no sound, no echo. */
        IGNORED,
        /** Accepted click: success pling and the slot echo. */
        ACCEPTED,
        /** Last action done: accepted pling (not for melody), completion pling, server close, chat and title. */
        COMPLETED,
        /** Wrong input that closes the menu: error sound, chat, server close. */
        FAIL_CLOSE,
        /** Wrong input that keeps the menu open: error sound and chat (chat may be absent). */
        FAIL_STAY,
        /** Server-side board change without a click (melody animation): slots only, no sound. */
        UPDATE
    }

    /** {@code changes}: slot to new cell after this event; {@code chat}: literal chat line or null; {@code plings}: success plings. */
    public record Reply(Outcome outcome, Map<Integer, Cell> changes, String chat, int plings) {
        public static final Reply IGNORED = new Reply(Outcome.IGNORED, Map.of(), null, 0);
    }

    public static final int WIDTH = 9;
    protected final Cell[] cells;
    private final int rows;

    protected Terminal(int rows) {
        this.rows = rows;
        this.cells = new Cell[rows * WIDTH];
        Arrays.fill(cells, Cell.FILLER);
    }

    public abstract Kind kind();

    /** Literal window title, leading {@code §r} included. */
    public abstract String title();

    public int rows() {
        return rows;
    }

    public int size() {
        return cells.length;
    }

    public Cell cell(int slot) {
        return cells[slot];
    }

    public abstract Reply click(int slot, Input input);

    /** One server tick; only the melody terminal animates. */
    public Reply tick() {
        return Reply.IGNORED;
    }

    protected static Map<Integer, Cell> changed(int slot, Cell cell) {
        Map<Integer, Cell> map = new LinkedHashMap<>();
        map.put(slot, cell);
        return map;
    }

    protected Reply accept(int slot, Cell cell) {
        cells[slot] = cell;
        return new Reply(Outcome.ACCEPTED, changed(slot, cell), null, 1);
    }

    protected Reply complete(int plings) {
        return new Reply(Outcome.COMPLETED, Map.of(), null, plings);
    }

    protected static boolean inRange(int slot, int... ranges) {
        for (int i = 0; i < ranges.length; i += 2) if (slot >= ranges[i] && slot <= ranges[i + 1]) return true;
        return false;
    }
}
