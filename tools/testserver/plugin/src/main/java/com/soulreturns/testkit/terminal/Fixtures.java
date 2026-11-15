package com.soulreturns.testkit.terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The initial boards of the native capture (Appendix A of docs/research/f7-native-terminals-2026-11-12.md,
 * bundled as terminals/boards.txt), keyed by the capture's window id. {@link #build} rebuilds one exactly.
 */
public final class Fixtures {
    /** One captured board: its title and the cell tokens by absolute slot (filler cells left out). */
    public record Board(int cid, String title, Map<Integer, String> tokens) {}

    private static final Map<Integer, Board> BOARDS = load();
    private static final Pattern STARTS = Pattern.compile("^§rWhat starts with: '(.)'\\?$");
    private static final Pattern SELECT = Pattern.compile("^§rSelect all the (.+) items!$");

    private Fixtures() {}

    public static Map<Integer, Board> all() {
        return BOARDS;
    }

    public static Board board(int cid) {
        Board board = BOARDS.get(cid);
        if (board == null) throw new IllegalArgumentException("no captured board " + cid + " (" + String.join(", ", BOARDS.keySet().stream().map(String::valueOf).toList()) + ")");
        return board;
    }

    /** The terminal exactly as captured in that opening, no progress. {@code rng} only drives melody's later targets. */
    public static Terminal build(int cid, Random rng) {
        Board board = board(cid);
        Matcher starts = STARTS.matcher(board.title);
        Matcher select = SELECT.matcher(board.title);
        Map<Integer, String> tokens = board.tokens;
        if (starts.matches()) return StartsWithTerminal.of(starts.group(1).charAt(0), withoutGlint(tokens));
        if (select.matches()) return SelectTerminal.of(Pools.hueByTitle(select.group(1)), withoutGlint(tokens));
        switch (board.title) {
            case "§rCorrect all the panes!" -> {
                var on = new TreeSet<Integer>();
                tokens.forEach((slot, token) -> { if (token.equals("ON")) on.add(slot); });
                return PanesTerminal.of(on);
            }
            case "§rClick in order!" -> {
                Map<Integer, Integer> numbers = new LinkedHashMap<>();
                tokens.forEach((slot, token) -> numbers.put(slot, Integer.parseInt(token)));
                return OrderTerminal.of(numbers);
            }
            case "§rChange all to same color!" -> {
                Map<Integer, Integer> colors = new LinkedHashMap<>();
                tokens.forEach((slot, token) -> colors.put(slot, RubixTerminal.indexOf(token)));
                return RubixTerminal.of(colors);
            }
            case "§rClick the button on time!" -> {
                int target = 0;
                int lime = 0;
                for (var e : tokens.entrySet()) {
                    if (e.getValue().equals("P") && e.getKey() < 9) target = e.getKey();
                    if (e.getValue().equals("G")) lime = e.getKey() % 9;
                }
                return MelodyTerminal.of(target, lime, rng);
            }
            default -> throw new IllegalStateException("unknown title " + board.title);
        }
    }

    /** Whether a captured cell carried the initial glint flag ({@code [g]}). */
    public static boolean glintMarked(String token) {
        return token.endsWith(" [g]");
    }

    private static Map<Integer, String> withoutGlint(Map<Integer, String> tokens) {
        Map<Integer, String> out = new LinkedHashMap<>();
        tokens.forEach((slot, token) -> out.put(slot, glintMarked(token) ? token.substring(0, token.length() - 4) : token));
        return out;
    }

    private static Map<Integer, Board> load() {
        try (InputStream in = Fixtures.class.getResourceAsStream("/terminals/boards.txt")) {
            if (in == null) throw new IllegalStateException("missing resource terminals/boards.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<Integer, Board> boards = new LinkedHashMap<>();
            Board current = null;
            Pattern head = Pattern.compile("^\\[(\\d+)] (.*)$");
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                Matcher m = head.matcher(line);
                if (m.matches()) {
                    current = new Board(Integer.parseInt(m.group(1)), m.group(2), new LinkedHashMap<>());
                    boards.put(current.cid, current);
                    continue;
                }
                int colon = line.indexOf(':');
                int first = Integer.parseInt(line.substring(0, colon));
                String[] cells = line.substring(colon + 1).split(" \\| ", -1);
                for (int i = 0; i < cells.length; i++) {
                    String token = cells[i].trim();
                    if (!token.equals("·")) current.tokens.put(first + i, token);
                }
            }
            return boards;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
