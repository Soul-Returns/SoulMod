package com.soulreturns.testkit.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * {@code §rWhat starts with: 'X'?}, {@code generic_9x5}, 3x7 board at slots 10-16, 19-25, 28-34. Matching is by
 * display name (Roast Chicken counts for R), not registry id. Accepted: the item gets the glint-override patch.
 * Wrong item: error sound, {@code Does not match!}, server close; the next open is a fresh board (captured).
 * Assumptions: a click on an already selected item or outside the board does nothing.
 */
public final class StartsWithTerminal extends Terminal {
    public static final int[] BOARD = rows(10, 28);
    public static final String WRONG = "§r§cDoes not match!";
    private static final int MIN_DISTINCT = 3;

    private final char letter;

    private StartsWithTerminal(char letter) {
        super(5);
        this.letter = Character.toUpperCase(letter);
    }

    static int[] rows(int first, int last) {
        List<Integer> slots = new ArrayList<>();
        for (int start = first; start <= last; start += 9) for (int i = 0; i < 7; i++) slots.add(start + i);
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Names a board may draw from: no select-terminal colors (those belong to the select terminal). */
    private static List<String> pool() {
        return Pools.items().keySet().stream().filter(n -> Pools.hueOfName(n) == null).toList();
    }

    /** Letters with enough distinct names to build a board; the captured L, E, A, R, M and the earlier G, N, D are among them. */
    public static List<Character> letters() {
        List<Character> out = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            final char letter = c;
            if (pool().stream().filter(n -> n.charAt(0) == letter).count() >= MIN_DISTINCT) out.add(c);
        }
        return out;
    }

    /** Random board: 6 to 10 matching items (captured: 6, 7, 8, 8, 12), the rest non-matching, duplicates allowed as captured. */
    public static StartsWithTerminal random(Random rng, char requested) {
        List<Character> letters = letters();
        char letter = requested != 0 ? Character.toUpperCase(requested) : letters.get(rng.nextInt(letters.size()));
        List<String> matching = pool().stream().filter(n -> n.charAt(0) == letter).toList();
        List<String> other = pool().stream().filter(n -> n.charAt(0) != letter).toList();
        if (matching.size() < 2) throw new IllegalArgumentException("not enough item names starting with " + letter + " (letters " + letters + ")");
        int hits = 6 + rng.nextInt(5);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < hits; i++) names.add(matching.get(rng.nextInt(matching.size())));
        while (names.size() < BOARD.length) names.add(other.get(rng.nextInt(other.size())));
        Collections.shuffle(names, rng);
        StartsWithTerminal terminal = new StartsWithTerminal(letter);
        for (int i = 0; i < BOARD.length; i++) terminal.cells[BOARD[i]] = Pools.item(names.get(i));
        return terminal;
    }

    /** A captured board: slot to display name. */
    public static StartsWithTerminal of(char letter, Map<Integer, String> names) {
        StartsWithTerminal terminal = new StartsWithTerminal(letter);
        names.forEach((slot, name) -> terminal.cells[slot] = Pools.item(name));
        return terminal;
    }

    public char letter() {
        return letter;
    }

    public boolean matches(Cell cell) {
        String name = cell.plainName();
        return !name.isEmpty() && Character.toUpperCase(name.charAt(0)) == letter;
    }

    @Override
    public Kind kind() {
        return Kind.STARTSWITH;
    }

    @Override
    public String title() {
        return "§rWhat starts with: '" + letter + "'?";
    }

    @Override
    public Reply click(int slot, Input input) {
        if (!inRange(slot, 10, 16, 19, 25, 28, 34)) return Reply.IGNORED;
        Cell cell = cells[slot];
        if (cell.selected()) return Reply.IGNORED;
        if (!matches(cell)) return new Reply(Outcome.FAIL_CLOSE, Map.of(), WRONG, 0);
        Reply reply = accept(slot, cell.withSelected(true));
        return remaining() == 0 ? complete(2) : reply;
    }

    public int remaining() {
        int left = 0;
        for (int slot : BOARD) if (matches(cells[slot]) && !cells[slot].selected()) left++;
        return left;
    }

    public String toString() {
        return String.format(Locale.ROOT, "startswith %s, %d to find", letter, remaining());
    }
}
