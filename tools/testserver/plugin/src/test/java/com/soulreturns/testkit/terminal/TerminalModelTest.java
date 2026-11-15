package com.soulreturns.testkit.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

class TerminalModelTest {
    private static final Terminal.Input L = Terminal.Input.LEFT;

    /** Scripted correct solution from the model's public state; returns the replies of every click. */
    private static List<Terminal.Reply> solve(Terminal t) {
        List<Terminal.Reply> replies = new ArrayList<>();
        switch (t.kind()) {
            case STARTSWITH -> {
                var s = (StartsWithTerminal) t;
                for (int slot : StartsWithTerminal.BOARD) if (s.matches(s.cell(slot))) replies.add(s.click(slot, L));
            }
            case SELECT -> {
                var s = (SelectTerminal) t;
                for (int slot : SelectTerminal.BOARD) if (s.matches(s.cell(slot))) replies.add(s.click(slot, L));
            }
            case PANES -> {
                for (int slot : PanesTerminal.GRID) if (t.cell(slot).id().equals("red_stained_glass_pane")) replies.add(t.click(slot, L));
            }
            case ORDER -> {
                for (int n = 1; n <= 10; n++) {
                    for (int slot : OrderTerminal.GRID) if (t.cell(slot).count() == n) replies.add(t.click(slot, L));
                }
            }
            case RUBIX -> {
                var r = (RubixTerminal) t;
                outer:
                for (int slot : RubixTerminal.GRID) {
                    int steps = Math.floorMod(0 - r.colorAt(slot), 5);
                    for (int k = 0; k < steps; k++) {
                        replies.add(r.click(slot, L));
                        // all nine may match at an intermediate color: that completes it, as on Hypixel
                        if (replies.get(replies.size() - 1).outcome() == Terminal.Outcome.COMPLETED) break outer;
                    }
                }
            }
            default -> {
                var m = (MelodyTerminal) t;
                for (int lane = 0; lane < 3; lane++) {
                    for (int guard = 0; guard < 500 && !(m.lane() == lane && m.limeX() == m.target()); guard++) m.tick();
                    replies.add(m.click(MelodyTerminal.buttonSlot(lane), L));
                    for (int i = 0; i < 6; i++) m.tick();
                }
            }
        }
        return replies;
    }

    private static Terminal random(Terminal.Kind kind, long seed) {
        Random rng = new Random(seed);
        return switch (kind) {
            case STARTSWITH -> StartsWithTerminal.random(rng, (char) 0);
            case SELECT -> SelectTerminal.random(rng, null);
            case PANES -> PanesTerminal.random(rng);
            case ORDER -> OrderTerminal.random(rng);
            case RUBIX -> RubixTerminal.random(rng);
            default -> MelodyTerminal.random(rng);
        };
    }

    @Test
    void everyRandomBoardIsSolvedByTheScript() {
        for (Terminal.Kind kind : Terminal.Kind.values()) {
            for (long seed = 1; seed <= 300; seed++) {
                Terminal t = random(kind, seed);
                List<Terminal.Reply> replies = solve(t);
                String what = kind + " seed " + seed;
                for (int i = 0; i < replies.size() - 1; i++) {
                    assertEquals(Terminal.Outcome.ACCEPTED, replies.get(i).outcome(), what + " click " + i);
                    assertEquals(1, replies.get(i).plings(), what);
                }
                Terminal.Reply last = replies.get(replies.size() - 1);
                assertEquals(Terminal.Outcome.COMPLETED, last.outcome(), what);
                assertEquals(kind == Terminal.Kind.MELODY ? 1 : 2, last.plings(), what + " plings");
                if (kind == Terminal.Kind.MELODY) assertEquals(3, replies.size());
            }
        }
    }

    @Test
    void sameSeedSameBoardAndRandomLettersAndColorsAreValid() {
        for (Terminal.Kind kind : Terminal.Kind.values()) {
            Terminal a = random(kind, 42);
            Terminal b = random(kind, 42);
            for (int i = 0; i < a.size(); i++) assertEquals(a.cell(i), b.cell(i), kind + " slot " + i);
            assertEquals(a.title(), b.title());
        }
        for (char c : "LEAGMNDR".toCharArray()) assertTrue(StartsWithTerminal.letters().contains(c), "captured letter " + c);
        for (Pools.Hue hue : Pools.HUES) {
            var t = SelectTerminal.random(new Random(7), hue);
            assertTrue(t.remaining() >= 5 && t.remaining() <= 6, hue.title());
        }
        for (long seed = 1; seed <= 300; seed++) {
            var s = (StartsWithTerminal) random(Terminal.Kind.STARTSWITH, seed);
            assertTrue(s.remaining() >= 6 && s.remaining() <= 10, "starts-with matches " + s.remaining());
            var r = (RubixTerminal) random(Terminal.Kind.RUBIX, seed);
            assertFalse(java.util.Arrays.stream(RubixTerminal.GRID).map(r::colorAt).distinct().count() == 1, "rubix never starts solved");
        }
    }

    @Test
    void startsWithAndSelectFailCloseWithTheCapturedLine() {
        var s = StartsWithTerminal.random(new Random(3), 'L');
        int wrong = java.util.Arrays.stream(StartsWithTerminal.BOARD).filter(i -> !s.matches(s.cell(i))).findFirst().getAsInt();
        var reply = s.click(wrong, L);
        assertEquals(Terminal.Outcome.FAIL_CLOSE, reply.outcome());
        assertEquals("§r§cDoes not match!", reply.chat());
        var sel = SelectTerminal.random(new Random(3), Pools.HUES.get(0));
        int bad = java.util.Arrays.stream(SelectTerminal.BOARD).filter(i -> !sel.matches(sel.cell(i))).findFirst().getAsInt();
        assertEquals(Terminal.Outcome.FAIL_CLOSE, sel.click(bad, L).outcome());
        // a selected item: patch flag set, name kept; second click does nothing
        int good = java.util.Arrays.stream(StartsWithTerminal.BOARD).filter(i -> s.matches(s.cell(i))).findFirst().getAsInt();
        var accepted = s.click(good, L);
        assertTrue(accepted.changes().get(good).selected());
        assertEquals(Terminal.Outcome.IGNORED, s.click(good, L).outcome());
        assertEquals(Terminal.Outcome.IGNORED, s.click(0, L).outcome());
    }

    @Test
    void roastChickenCountsForRBecauseMatchingIsByDisplayName() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(10, "Roast Chicken");
        names.put(11, "Lava Bucket");
        var t = StartsWithTerminal.of('R', names);
        assertEquals("cooked_chicken", t.cell(10).id());
        assertEquals(Terminal.Outcome.COMPLETED, t.click(10, L).outcome());
    }

    @Test
    void orderWrongNumberKeepsProgress() {
        var t = (OrderTerminal) random(Terminal.Kind.ORDER, 9);
        int[] slotOf = new int[11];
        for (int slot : OrderTerminal.GRID) slotOf[t.cell(slot).count()] = slot;
        for (int n = 1; n <= 4; n++) assertEquals(Terminal.Outcome.ACCEPTED, t.click(slotOf[n], L).outcome());
        var wrong = t.click(slotOf[9], L);
        assertEquals(Terminal.Outcome.FAIL_STAY, wrong.outcome());
        assertEquals("§r§cWrong number!", wrong.chat());
        assertEquals(Terminal.Outcome.FAIL_STAY, t.click(slotOf[7], L).outcome());
        for (int n = 1; n <= 4; n++) assertEquals("lime_stained_glass_pane", t.cell(slotOf[n]).id());
        assertEquals(n(5), t.next());
        var ok = t.click(slotOf[5], L);
        assertEquals("lime_stained_glass_pane", ok.changes().get(slotOf[5]).id());
        assertEquals(5, ok.changes().get(slotOf[5]).count());
        assertEquals("§r§a5", ok.changes().get(slotOf[5]).name());
    }

    private static int n(int v) {
        return v;
    }

    @Test
    void panesToggleBothWays() {
        var t = PanesTerminal.of(java.util.Set.of(12, 14));
        assertEquals(13, t.remaining());
        assertEquals("red_stained_glass_pane", t.click(12, L).changes().get(12).id());
        assertEquals("lime_stained_glass_pane", t.click(12, L).changes().get(12).id());
        assertEquals(Terminal.Outcome.IGNORED, t.click(0, L).outcome());
    }

    @Test
    void rubixCyclesForwardLeftBackwardRightAndDragForward() {
        Map<Integer, Integer> colors = new LinkedHashMap<>();
        for (int slot : RubixTerminal.GRID) colors.put(slot, 4);
        colors.put(22, 0);
        var t = RubixTerminal.of(colors);
        assertEquals("§r§aOrange", t.click(22, L).changes().get(22).name());
        assertEquals("§r§aRed", t.click(22, Terminal.Input.RIGHT).changes().get(22).name());
        // Blue everywhere now: that change completes it
        assertEquals(Terminal.Outcome.COMPLETED, t.click(22, Terminal.Input.RIGHT).outcome());
        var drag = RubixTerminal.of(colors);
        assertEquals("§r§aOrange", drag.click(22, Terminal.Input.DRAG).changes().get(22).name());
        var dbl = RubixTerminal.of(colors);
        assertEquals("§r§aOrange", dbl.click(22, Terminal.Input.DOUBLE).changes().get(22).name());
        assertEquals("green_stained_glass_pane", RubixTerminal.of(Map.of(12, 3)).cell(12).id());
    }

    @Test
    void melodyStepsEveryTenTicksBouncesAndPausesAfterAWrongLock() {
        var t = MelodyTerminal.of(1, 3, new Random(1));
        // lime 3 -> 4 -> 5 -> 4 -> 3 -> 2 -> 1, a step every 10 ticks, like cid21's 21, 22, 23, 22, 21, 20, 19
        int[] expected = {3, 4, 5, 4, 3, 2, 1};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], t.limeX(), "step " + i);
            for (int k = 0; k < 9; k++) assertEquals(Terminal.Outcome.IGNORED, t.tick().outcome());
            if (i < expected.length - 1) assertEquals(Terminal.Outcome.UPDATE, t.tick().outcome());
        }
        var m = MelodyTerminal.of(5, 1, new Random(1));
        var wrong = m.click(16, L);
        assertEquals(Terminal.Outcome.FAIL_STAY, wrong.outcome());
        assertTrue(m.paused());
        for (int i = 0; i < MelodyTerminal.pauseTicks; i++) m.tick();
        assertFalse(m.paused());
        assertEquals(1, m.limeX());
        assertEquals(Terminal.Outcome.IGNORED, m.click(25, L).outcome(), "inactive row");
        // An accepted lock hands over at once: lane 2 is live with its pane in the locked column, but the board is
        // redrawn only on the next step, the pane one column on by then (same direction, bouncing)
        var ok = MelodyTerminal.of(4, 4, new Random(1));
        ok.tick();
        assertEquals(Terminal.Outcome.ACCEPTED, ok.click(16, L).outcome());
        assertEquals(1, ok.lane());
        assertEquals(4, ok.limeX());
        assertEquals("lime_terracotta", ok.cell(16).id(), "not redrawn before the step");
        assertEquals("red_terracotta", ok.cell(25).id());
        assertEquals(Terminal.Outcome.IGNORED, ok.click(16, L).outcome(), "the old lane is inactive now");
        for (int i = 0; i < MelodyTerminal.STEP_TICKS - 2; i++) assertEquals(Terminal.Outcome.IGNORED, ok.tick().outcome());
        assertEquals(Terminal.Outcome.UPDATE, ok.tick().outcome());
        assertEquals(1, ok.lane());
        assertEquals(5, ok.limeX());
        assertEquals("lime_terracotta", ok.cell(25).id());
        assertEquals("red_terracotta", ok.cell(16).id());
        assertEquals("lime_stained_glass_pane", ok.cell(23).id());
        // it bounces back: lane 2 steps 5 -> 4
        for (int i = 0; i < MelodyTerminal.STEP_TICKS; i++) ok.tick();
        assertEquals(4, ok.limeX());
    }

    @Test
    void melodyLockOnTheNextLaneBeforeItsFirstStepIsJudgedOnTheLockedColumn() {
        // 2026-11-12 22:50:15 (cid 16) and 22:51:11 (cid 22): lane 1 locked on column 4, lane 2 clicked a few ticks
        // later with its target elsewhere: error; lane 2 is first drawn still in column 4, then stands the pause.
        MelodyTerminal wrong = null;
        for (long seed = 1; wrong == null; seed++) {
            var m = MelodyTerminal.of(4, 4, new Random(seed));
            m.tick();
            m.click(16, L);
            if (m.target() != 4) wrong = m;
        }
        assertEquals(Terminal.Outcome.FAIL_STAY, wrong.click(25, L).outcome());
        for (int i = 0; i < MelodyTerminal.STEP_TICKS - 1; i++) wrong.tick();
        assertEquals("lime_terracotta", wrong.cell(25).id(), "lane 2 shown on the step");
        assertEquals("lime_stained_glass_pane", wrong.cell(22).id(), "still in the locked column");
        for (int i = 0; i < MelodyTerminal.pauseTicks - MelodyTerminal.STEP_TICKS; i++) wrong.tick();
        assertEquals(4, wrong.limeX());
        for (int i = 0; i < MelodyTerminal.STEP_TICKS; i++) wrong.tick();
        assertEquals(5, wrong.limeX(), "moves on the first step after the pause");
        // 22:52:10 (cid 29): the same with lane 2's target directly below: accepted, lane 2 skipped unseen.
        MelodyTerminal.alignNext = true;
        try {
            var skip = MelodyTerminal.of(4, 4, new Random(1));
            skip.tick();
            assertEquals(Terminal.Outcome.ACCEPTED, skip.click(16, L).outcome());
            assertEquals(Terminal.Outcome.ACCEPTED, skip.click(25, L).outcome(), "the skip");
            assertEquals(2, skip.lane());
            for (int i = 0; i < MelodyTerminal.STEP_TICKS - 1; i++) skip.tick();
            assertEquals("lime_terracotta", skip.cell(34).id());
            assertEquals("white_stained_glass_pane", skip.cell(22).id(), "lane 2 never drawn active");
            assertEquals(5, skip.limeX());
        } finally {
            MelodyTerminal.alignNext = false;
        }
    }

    // ---- Appendix A, parsed straight from the research doc ----

    @Test
    void everyCapturedBoardMatchesAppendixAOfTheDoc() throws IOException {
        Path doc = Path.of("../../../docs/research/f7-native-terminals-2026-11-12.md");
        Assumptions.assumeTrue(Files.exists(doc), "research doc not found next to the plugin");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        String a = text.substring(text.indexOf("## Appendix A"), text.indexOf("## Appendix B"));
        Matcher m = Pattern.compile("### cid (\\d+): `(.*?)`\n.*?```text\n(.*?)```", Pattern.DOTALL).matcher(a);
        int boards = 0;
        while (m.find()) {
            boards++;
            int cid = Integer.parseInt(m.group(1));
            String title = m.group(2);
            Terminal t = Fixtures.build(cid, new Random(1));
            assertEquals(title, t.title(), "cid " + cid);
            Map<Integer, String> doc2 = new LinkedHashMap<>();
            for (String row : m.group(3).split("\n")) {
                int first = Integer.parseInt(row.substring(0, row.indexOf(':')));
                String[] cells = row.substring(row.indexOf(':') + 1).split(" \\| ");
                for (int i = 0; i < cells.length; i++) if (!cells[i].trim().equals("·")) doc2.put(first + i, cells[i].trim());
            }
            for (int slot = 0; slot < t.size(); slot++) {
                String token = doc2.get(slot);
                Cell cell = t.cell(slot);
                String where = "cid " + cid + " slot " + slot;
                if (token == null) {
                    assertEquals(Cell.FILLER, cell, where);
                    continue;
                }
                boolean glint = token.endsWith(" [g]");
                String name = glint ? token.substring(0, token.length() - 4) : token;
                assertEquals(glint, cell.intrinsicGlint(), where + " initial glint");
                assertFalse(cell.selected(), where);
                if (title.contains("panes")) {
                    assertEquals(name.equals("ON") ? "§r§aOn" : "§r§cOff", cell.name(), where);
                    assertEquals(name.equals("ON") ? "lime_stained_glass_pane" : "red_stained_glass_pane", cell.id(), where);
                } else if (title.contains("in order")) {
                    assertEquals("§r§a" + name, cell.name(), where);
                    assertEquals(Integer.parseInt(name), cell.count(), where);
                    assertEquals("red_stained_glass_pane", cell.id(), where);
                } else if (title.contains("same color")) {
                    assertEquals("§r§a" + Character.toUpperCase(name.charAt(0)) + name.substring(1), cell.name(), where);
                    assertEquals(name + "_stained_glass_pane", cell.id(), where);
                } else if (title.contains("on time")) {
                    String id = switch (name) {
                        case "P" -> "magenta_stained_glass_pane";
                        case "G" -> "lime_stained_glass_pane";
                        case "R" -> "red_stained_glass_pane";
                        case "W" -> "white_stained_glass_pane";
                        case "LOCK" -> "lime_terracotta";
                        default -> "red_terracotta";
                    };
                    assertEquals(id, cell.id(), where);
                    assertEquals(name.equals("LOCK") ? "§r§aLock In Slot" : name.equals("inactive") ? "§r§cRow Not Active" : "", cell.name(), where);
                    assertEquals(name.equals("LOCK") || name.equals("inactive") ? 2 : 0, cell.lore().size(), where);
                } else {
                    assertEquals("§r§a" + name, cell.name(), where);
                    assertEquals(1, cell.count(), where);
                    assertEquals(Pools.items().containsKey(name) ? Pools.items().get(name) : cell.id(), cell.id(), where);
                }
            }
        }
        assertEquals(20, boards);
        assertEquals(20, Fixtures.all().size());
    }
}
