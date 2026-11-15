package com.soulreturns.testkit;

import com.soulreturns.testkit.terminal.Cell;
import com.soulreturns.testkit.terminal.Fixtures;
import com.soulreturns.testkit.terminal.MelodyTerminal;
import com.soulreturns.testkit.terminal.OrderTerminal;
import com.soulreturns.testkit.terminal.Pools;
import com.soulreturns.testkit.terminal.RubixTerminal;
import com.soulreturns.testkit.terminal.Terminal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.world.inventory.ContainerInput;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /hx terminal selftest}: drives the real click path (container click packets of the open menu) of the connected
 * player, solving every terminal type from what the open inventory shows, then each documented wrong input, then
 * the render of every captured Appendix A board against its own cells, then the response delay. Reads only the
 * inventory, never the model, to decide its clicks.
 */
final class TerminalSelfTest {
    private final SoulTestKit plugin;
    private final Terminals terminals;
    private final Player player;
    private final CommandSender sender;
    private int checks;
    private int failures;
    private String section = "";

    TerminalSelfTest(SoulTestKit plugin, Terminals terminals, Player player, CommandSender sender) {
        this.plugin = plugin;
        this.terminals = terminals;
        this.player = player;
        this.sender = sender;
    }

    void run() {
        int lag = terminals.lagMs;
        int total = terminals.total;
        int pause = MelodyTerminal.pauseTicks;
        terminals.sync = true;
        try {
            player.closeInventory();
            terminals.setCounter(player, 0, 7);
            MelodyTerminal.pauseTicks = 20;
            section("solutions");
            for (Terminal.Kind kind : Terminal.Kind.values()) {
                for (long seed : new long[] {1, 2, 3, 4, 5}) solve(kind, seed);
            }
            section("wrong input");
            wrongInput();
            section("captured boards");
            capturedBoards();
        } catch (RuntimeException e) {
            fail("exception " + e);
            plugin.getLogger().warning("terminal selftest: " + e);
        } finally {
            terminals.sync = false;
            player.closeInventory();
        }
        terminals.lagMs = lag;
        terminals.total = total;
        MelodyTerminal.pauseTicks = pause;
        lagTest(lag);
    }

    // ---- helpers ----

    private void section(String name) {
        section = name;
    }

    private void check(boolean ok, String what) {
        checks++;
        if (!ok) fail(what);
    }

    private void fail(String what) {
        failures++;
        say("§cFAIL [" + section + "] " + what);
    }

    private void say(String line) {
        sender.sendMessage(line);
        plugin.getLogger().info("terminal selftest: " + Txt.strip(line));
    }

    private Inventory inv() {
        return player.getOpenInventory().getTopInventory();
    }

    private boolean open(Terminals.Session s) {
        return s.open && player.getOpenInventory().getTopInventory() == s.inventory;
    }

    /** A real container click packet, through the same entry point the Netty tap uses. */
    private void click(int slot, ClickType type) {
        Terminals.Session s = terminals.session(player);
        check(s != null && s.open, "click " + slot + " needs an open terminal");
        if (s == null) return;
        ContainerInput kind = switch (type) {
            case RIGHT, LEFT -> ContainerInput.PICKUP;
            case DOUBLE_CLICK -> ContainerInput.PICKUP_ALL;
            case MIDDLE -> ContainerInput.CLONE;
            default -> ContainerInput.QUICK_MOVE;
        };
        terminals.onClickPacket(player, terminals.packet(s, slot, kind, type == ClickType.RIGHT ? 1 : type == ClickType.MIDDLE ? 2 : 0));
    }

    /** The three packets of a drag over one slot: start, add slot (header 1), end. */
    private void drag(int slot) {
        Terminals.Session s = terminals.session(player);
        for (int[] phase : new int[][] {{-999, 0}, {slot, 4}, {-999, 8}}) {
            terminals.onClickPacket(player, terminals.packet(s, phase[0], ContainerInput.QUICK_CRAFT, phase[1]));
        }
    }

    private static String plain(ItemStack item) {
        if (item == null || item.getItemMeta().customName() == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().customName()).replaceAll("§.", "");
    }

    private static String id(ItemStack item) {
        return item == null ? "air" : item.getType().getKey().getKey();
    }

    private List<Integer> slotsWhere(java.util.function.Predicate<ItemStack> test) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < inv().getSize(); i++) if (test.test(inv().getItem(i))) out.add(i);
        return out;
    }

    private static boolean filler(ItemStack item) {
        return id(item).equals("black_stained_glass_pane");
    }

    private Terminals.Session opened(Terminal.Kind kind, String argument, long seed) {
        player.closeInventory();
        Terminals.Session s = terminals.openRandom(player, kind, argument, seed);
        check(open(s), kind.key + " " + seed + " opens as the player's menu");
        return s;
    }

    private static long count(Terminals.Session s, String event) {
        return s.events.stream().filter(e -> e.equals(event)).count();
    }

    private static boolean has(Terminals.Session s, String prefix) {
        return s.events.stream().anyMatch(e -> e.startsWith(prefix));
    }

    // ---- scripted correct solutions, decided from the inventory only ----

    private void solve(Terminal.Kind kind, long seed) {
        Terminals.Session s = opened(kind, null, seed);
        int before = terminals.count(player);
        int accepted = 0;
        switch (kind) {
            case STARTSWITH -> {
                Matcher m = Pattern.compile("'(.)'").matcher(Txt.strip(s.terminal.title()));
                check(m.find(), "starts-with title carries the letter");
                char letter = m.group(1).charAt(0);
                for (int slot : slotsWhere(i -> !filler(i) && plain(i).charAt(0) == letter)) {
                    click(slot, ClickType.LEFT);
                    accepted++;
                }
            }
            case SELECT -> {
                Matcher m = Pattern.compile("Select all the (.+) items!").matcher(Txt.strip(s.terminal.title()));
                check(m.find(), "select title carries the color");
                Pools.Hue hue = Pools.hueByTitle(m.group(1));
                List<String> ids = java.util.stream.IntStream.range(0, Pools.FORMS).mapToObj(f -> hue.item(f).id()).toList();
                for (int slot : slotsWhere(i -> ids.contains(id(i)))) {
                    click(slot, ClickType.LEFT);
                    accepted++;
                }
            }
            case PANES -> {
                for (int slot : slotsWhere(i -> id(i).equals("red_stained_glass_pane"))) {
                    click(slot, ClickType.LEFT);
                    accepted++;
                }
            }
            case ORDER -> {
                List<Integer> slots = slotsWhere(i -> id(i).equals("red_stained_glass_pane"));
                slots.sort(Comparator.comparingInt(slot -> inv().getItem(slot).getAmount()));
                check(slots.size() == 10, "order shows ten red panes");
                for (int slot : slots) {
                    click(slot, ClickType.LEFT);
                    accepted++;
                }
            }
            case RUBIX -> accepted = solveRubix();
            default -> accepted = solveMelody(s);
        }
        boolean melody = kind == Terminal.Kind.MELODY;
        check(!open(s), kind.key + " " + seed + ": completion closes the menu from the server");
        check(has(s, "complete "), kind.key + " " + seed + ": completed");
        check(terminals.count(player) == (before >= terminals.total ? 1 : before + 1), kind.key + " " + seed + ": counter advanced (wraps after the total)");
        check(s.events.stream().anyMatch(e -> e.startsWith("chat ") && e.contains("activated a terminal! (")), kind.key + " " + seed + ": completion chat line");
        long expectedPlings = melody ? 3 : accepted + 1;
        check(count(s, "pling") == expectedPlings, kind.key + " " + seed + ": plings " + count(s, "pling") + " expected " + expectedPlings);
        check(count(s, "error") == 0, kind.key + " " + seed + ": no error on a correct solution");
    }

    private int solveRubix() {
        int[] slots = {12, 13, 14, 21, 22, 23, 30, 31, 32};
        int[] color = new int[slots.length];
        for (int i = 0; i < slots.length; i++) color[i] = RubixTerminal.indexOf(plain(inv().getItem(slots[i])));
        int best = 0;
        int bestSteps = Integer.MAX_VALUE;
        for (int target = 0; target < 5; target++) {
            int steps = 0;
            for (int c : color) steps += Math.min(Math.floorMod(target - c, 5), Math.floorMod(c - target, 5));
            if (steps < bestSteps) {
                bestSteps = steps;
                best = target;
            }
        }
        int clicks = 0;
        for (int i = 0; i < slots.length; i++) {
            int forward = Math.floorMod(best - color[i], 5);
            int backward = Math.floorMod(color[i] - best, 5);
            for (int k = 0; k < Math.min(forward, backward); k++) {
                click(slots[i], forward <= backward ? ClickType.LEFT : ClickType.RIGHT);
                clicks++;
            }
        }
        return clicks;
    }

    /** Reads the target column and the lime pane from the inventory, ticks until they meet, locks. */
    private int solveMelody(Terminals.Session s) {
        int locks = 0;
        for (int lane = 0; lane < 3; lane++) {
            int guard = 0;
            while (open(s) && guard++ < 2000) {
                int target = -1;
                for (int x = 1; x <= 5; x++) if (id(inv().getItem(x)).equals("magenta_stained_glass_pane")) target = x;
                check(target > 0, "melody shows a target column");
                check(id(inv().getItem(36 + target)).equals("magenta_stained_glass_pane"), "melody mirrors the target at the bottom");
                int active = -1;
                int lime = -1;
                for (int l = 0; l < 3; l++) {
                    for (int x = 1; x <= 5; x++) if (id(inv().getItem((l + 1) * 9 + x)).equals("lime_stained_glass_pane")) {
                        active = l;
                        lime = x;
                    }
                }
                if (active == lane && lime == target) break;
                terminals.tick(s);
            }
            check(open(s), "melody lane " + lane + " still open before its lock");
            if (!open(s)) return locks;
            check(id(inv().getItem((lane + 1) * 9 + 7)).equals("lime_terracotta"), "active lane's button is the lime Lock In Slot");
            click((lane + 1) * 9 + 7, ClickType.LEFT);
            locks++;
            // The next lane shows on the lane's next step.
            for (int t = 0; t < MelodyTerminal.STEP_TICKS && open(s); t++) terminals.tick(s);
        }
        return locks;
    }

    // ---- documented wrong inputs and unobserved assumptions ----

    private void wrongInput() {
        // starts-with and select: a wrong item closes with the error sound and "Does not match!"
        for (Terminal.Kind kind : new Terminal.Kind[] {Terminal.Kind.STARTSWITH, Terminal.Kind.SELECT}) {
            Terminals.Session s = opened(kind, null, 11);
            int done = terminals.count(player);
            Terminal t = s.terminal;
            int wrong = -1;
            for (int slot = 0; slot < t.size() && wrong < 0; slot++) {
                Cell cell = t.cell(slot);
                boolean match = t instanceof com.soulreturns.testkit.terminal.StartsWithTerminal sw ? sw.matches(cell)
                    : t instanceof com.soulreturns.testkit.terminal.SelectTerminal se && se.matches(cell);
                if (!cell.equals(Cell.FILLER) && !match) wrong = slot;
            }
            check(wrong >= 0, kind.key + " board has a wrong item");
            click(wrong, ClickType.LEFT);
            check(!open(s), kind.key + ": a wrong item closes the menu");
            check(count(s, "error") == 1 && s.events.contains("chat " + Txt.strip(com.soulreturns.testkit.terminal.StartsWithTerminal.WRONG)), kind.key + ": error sound and 'Does not match!'");
            check(!has(s, "complete") && terminals.count(player) == done, kind.key + ": no counter progress after a failure");
            Terminals.Session again = opened(kind, null, 12);
            check(open(again) && again != s, kind.key + ": reopening gives a fresh board");
        }
        // starts-with: a correct pick, then the same item again and a filler: nothing
        {
            Terminals.Session s = opened(Terminal.Kind.STARTSWITH, null, 21);
            Matcher m = Pattern.compile("'(.)'").matcher(Txt.strip(s.terminal.title()));
            m.find();
            char letter = m.group(1).charAt(0);
            int slot = slotsWhere(i -> !filler(i) && plain(i).charAt(0) == letter).get(0);
            click(slot, ClickType.LEFT);
            check(open(s) && count(s, "pling") == 1, "starts-with: a correct pick plings once and stays open");
            check(TerminalItems.mismatches(withSelected(s, slot), inv().getItem(slot)).isEmpty(), "starts-with: the accepted item shows the glint-override patch");
            click(slot, ClickType.LEFT);
            click(1, ClickType.LEFT);
            check(open(s) && count(s, "pling") == 1 && count(s, "error") == 0, "starts-with: re-click and filler click do nothing (assumption)");
        }
        // an initially glowing item is accepted without gaining a patch (captured: Enchanted Book on the E board)
        {
            player.closeInventory();
            Terminals.Session s = terminals.openCaptured(player, 19);
            int book = 24;
            check(plain(inv().getItem(book)).equals("Enchanted Book") && org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(inv().getItem(book)).hasFoil(), "E board: the book glows before any click");
            click(book, ClickType.LEFT);
            check(open(s) && count(s, "pling") == 1, "E board: the glowing book is still accepted");
            check(TerminalItems.mismatches(s.terminal.cell(book), inv().getItem(book)).isEmpty() && s.terminal.cell(book).selected(), "E board: the book keeps its glint with no new patch");
        }
        // panes: toggling an On pane turns it Off, pling, menu open
        {
            Terminals.Session s = opened(Terminal.Kind.PANES, null, 31);
            int on = slotsWhere(i -> id(i).equals("lime_stained_glass_pane")).get(0);
            click(on, ClickType.LEFT);
            check(open(s) && id(inv().getItem(on)).equals("red_stained_glass_pane") && plain(inv().getItem(on)).equals("Off"), "panes: an On pane toggles back to Off");
            check(count(s, "pling") == 1 && count(s, "error") == 0, "panes: toggling off plings like any accepted click");
            click(on, ClickType.LEFT);
            check(id(inv().getItem(on)).equals("lime_stained_glass_pane"), "panes: and back On");
            click(0, ClickType.LEFT);
            check(count(s, "pling") == 2, "panes: a filler click does nothing (assumption)");
        }
        // order: a wrong number errors but keeps progress and the menu
        {
            Terminals.Session s = opened(Terminal.Kind.ORDER, null, 41);
            int[] bySlot = new int[inv().getSize()];
            for (int slot : slotsWhere(i -> id(i).equals("red_stained_glass_pane"))) bySlot[slot] = inv().getItem(slot).getAmount();
            java.util.function.IntFunction<Integer> slotOf = n -> {
                for (int slot = 0; slot < bySlot.length; slot++) if (bySlot[slot] == n) return slot;
                return -1;
            };
            for (int n = 1; n <= 4; n++) click(slotOf.apply(n), ClickType.LEFT);
            click(slotOf.apply(9), ClickType.LEFT);
            check(open(s), "order: a wrong number does not close");
            check(count(s, "error") == 1 && s.events.contains("chat " + Txt.strip(OrderTerminal.WRONG)), "order: error sound and 'Wrong number!'");
            check(id(inv().getItem(slotOf.apply(9))).equals("red_stained_glass_pane"), "order: the wrong pane stays red");
            for (int n = 1; n <= 4; n++) check(id(inv().getItem(slotOf.apply(n))).equals("lime_stained_glass_pane"), "order: pane " + n + " stays lime, no reset");
            check(inv().getItem(slotOf.apply(3)).getAmount() == 3 && plain(inv().getItem(slotOf.apply(3))).equals("3"), "order: lime pane keeps count and name");
            click(slotOf.apply(9), ClickType.LEFT);
            check(count(s, "error") == 2 && open(s), "order: a repeated wrong number errors again");
            click(slotOf.apply(5), ClickType.LEFT);
            check(id(inv().getItem(slotOf.apply(5))).equals("lime_stained_glass_pane"), "order: the right number is then accepted");
            click(slotOf.apply(1), ClickType.LEFT);
            check(count(s, "pling") == 5 && count(s, "error") == 2, "order: a done pane does nothing (assumption)");
        }
        // rubix: left forward, right backward, drag phase forward, double click forward
        {
            Terminals.Session s = opened(Terminal.Kind.RUBIX, null, 51);
            int slot = 22;
            int c0 = RubixTerminal.indexOf(plain(inv().getItem(slot)));
            click(slot, ClickType.LEFT);
            check(RubixTerminal.indexOf(plain(inv().getItem(slot))) == (c0 + 1) % 5, "rubix: left click goes forward");
            click(slot, ClickType.RIGHT);
            check(RubixTerminal.indexOf(plain(inv().getItem(slot))) == c0, "rubix: right click goes backward");
            click(slot, ClickType.RIGHT);
            check(RubixTerminal.indexOf(plain(inv().getItem(slot))) == Math.floorMod(c0 - 1, 5), "rubix: backward wraps");
            drag(slot);
            check(RubixTerminal.indexOf(plain(inv().getItem(slot))) == c0, "rubix: the QUICK_CRAFT drag phase goes forward");
            click(slot, ClickType.DOUBLE_CLICK);
            check(RubixTerminal.indexOf(plain(inv().getItem(slot))) == (c0 + 1) % 5, "rubix: PICKUP_ALL goes forward");
            click(slot, ClickType.MIDDLE);
            check(RubixTerminal.indexOf(plain(inv().getItem(slot))) == (c0 + 2) % 5, "rubix: middle click goes forward");
            check(open(s) && count(s, "error") == 0, "rubix: no failure response");
            click(0, ClickType.LEFT);
            check(count(s, "pling") == 6, "rubix: a filler click does nothing (assumption)");
        }
        melodyWrongLock();
    }

    private static Cell withSelected(Terminals.Session s, int slot) {
        return s.terminal.cell(slot);
    }

    private void melodyWrongLock() {
        // melody seed with a lime pane away from the target at the start
        Terminals.Session s = null;
        for (long seed = 61; seed < 100; seed++) {
            Terminals.Session candidate = opened(Terminal.Kind.MELODY, null, seed);
            if (((MelodyTerminal) candidate.terminal).target() != 1) {
                s = candidate;
                break;
            }
        }
        check(s != null, "melody: a board whose lime pane starts away from the target");
        if (s == null) return;
        int pause = MelodyTerminal.pauseTicks;
        MelodyTerminal t = (MelodyTerminal) s.terminal;
        click(MelodyTerminal.buttonSlot(0), ClickType.LEFT);
        check(open(s) && count(s, "error") == 1 && count(s, "pling") == 0 && !has(s, "chat"), "melody: a wrong lock errors without chat and keeps the menu open");
        check(t.paused() && t.lane() == 0, "melody: the animation pauses, no lane progress");
        int limeBefore = t.limeX();
        for (int i = 0; i < pause - 1; i++) terminals.tick(s);
        check(t.limeX() == limeBefore && t.paused(), "melody: the lime pane stands still through the pause");
        click(MelodyTerminal.buttonSlot(0), ClickType.LEFT);
        check(count(s, "error") == 1, "melody: locks during the pause do nothing (assumption)");
        terminals.tick(s);
        check(!t.paused(), "melody: the pause ends after " + pause + " ticks");
        for (int i = 0; i < MelodyTerminal.STEP_TICKS; i++) terminals.tick(s);
        check(t.limeX() != limeBefore, "melody: the lime pane moves again");
        click(MelodyTerminal.buttonSlot(1), ClickType.LEFT);
        click(MelodyTerminal.laneSlot(0, 2), ClickType.LEFT);
        check(count(s, "error") == 1 && count(s, "pling") == 0, "melody: inactive row buttons and panes do nothing (assumption)");
    }

    // ---- every captured board against Appendix A ----

    private void capturedBoards() {
        for (Fixtures.Board board : Fixtures.all().values()) {
            player.closeInventory();
            Terminals.Session s = terminals.openCaptured(player, board.cid());
            String what = "cid " + board.cid() + " " + Txt.strip(board.title());
            check(open(s), what + " opens");
            Inventory top = inv();
            check(PlainTextComponentSerializer.plainText().serialize(player.getOpenInventory().title()).equals(Terminals.wire(board.title())), what + ": title " + player.getOpenInventory().title());
            int rows = board.title().contains("starts with") || board.title().contains("panes") || board.title().contains("same color") ? 5
                : board.title().contains("in order") ? 4 : 6;
            check(top.getSize() == rows * 9, what + ": size " + top.getSize() + " expected " + rows * 9);
            for (int slot = 0; slot < top.getSize(); slot++) {
                ItemStack item = top.getItem(slot);
                String token = board.tokens().get(slot);
                List<String> problems = new ArrayList<>();
                if (token == null) {
                    problems.addAll(TerminalItems.mismatches(Cell.FILLER, item));
                } else {
                    Cell expected = expected(board, token);
                    problems.addAll(TerminalItems.mismatches(expected, item));
                    if (Fixtures.glintMarked(token) != org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(item).hasFoil()) problems.add("glint flag [g] mismatch");
                }
                if (!problems.isEmpty()) fail(what + " slot " + slot + " (" + token + "): " + problems);
                else checks++;
            }
        }
    }

    /** Appendix A token to the cell it stands for, without going through the model. */
    private Cell expected(Fixtures.Board board, String token) {
        String t = token.replace(" [g]", "");
        String title = board.title();
        if (title.contains("panes")) return t.equals("ON") ? Cell.of("lime_stained_glass_pane", "§r§aOn") : Cell.of("red_stained_glass_pane", "§r§cOff");
        if (title.contains("in order")) return Cell.of("red_stained_glass_pane", "§r§a" + t, Integer.parseInt(t));
        if (title.contains("same color")) return Cell.of(t + "_stained_glass_pane", "§r§a" + t.substring(0, 1).toUpperCase() + t.substring(1));
        if (title.contains("on time")) {
            return switch (t) {
                case "P" -> MelodyTerminal.PURPLE;
                case "G" -> MelodyTerminal.LIME;
                case "R" -> MelodyTerminal.RED;
                case "W" -> MelodyTerminal.WHITE;
                case "LOCK" -> MelodyTerminal.LOCK;
                default -> MelodyTerminal.INACTIVE;
            };
        }
        return Pools.item(t);
    }

    // ---- response delay (asynchronous) ----

    private void lagTest(int originalLag) {
        section("response delay");
        player.closeInventory();
        terminals.lagMs = 150;
        Terminals.Session s = terminals.openRandom(player, Terminal.Kind.PANES, null, 77);
        int off = slotsWhere(i -> id(i).equals("red_stained_glass_pane")).get(0);
        click(off, ClickType.LEFT);
        check(id(inv().getItem(off)).equals("red_stained_glass_pane") && count(s, "pling") == 0, "150 ms lag: nothing answered in the click's own tick");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            check(id(inv().getItem(off)).equals("red_stained_glass_pane") && count(s, "pling") == 0, "150 ms lag: still unanswered after 2 ticks");
        }, 2);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            check(id(inv().getItem(off)).equals("lime_stained_glass_pane") && count(s, "pling") == 1, "150 ms lag: pling and slot echo after 3 ticks");
            terminals.lagMs = 0;
            int next = slotsWhere(i -> id(i).equals("red_stained_glass_pane")).get(0);
            click(next, ClickType.LEFT);
            check(id(inv().getItem(next)).equals("lime_stained_glass_pane") && count(s, "pling") == 2, "lag 0: answered in the same tick");
            terminals.lagMs = originalLag;
            player.closeInventory();
            say((failures == 0 ? "§a" : "§c") + "terminal selftest: " + checks + " checks, " + failures + " failed");
        }, 4);
    }
}
