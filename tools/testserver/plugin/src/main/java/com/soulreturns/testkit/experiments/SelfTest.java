package com.soulreturns.testkit.experiments;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * {@code /hx exp selftest} (and the plugin unit test): plays the captured games to their recorded ends through a
 * {@link Driver}'s click path, deciding every click from what the client was sent ({@link ClientView}), and checks
 * the recorded menus, timings, sounds and end screens. Server state is read only to set up the lateclick case and
 * to look at the rare cards' board.
 */
public final class SelfTest {
    /** The click path under test: the plugin builds real container_click / container_close packets. */
    public interface Driver {
        Session session();

        void click(int slot, Click click);

        void close();
    }

    private static final String[] TOP_CELLS_NAMES = {"Red", "Blue", "Lime", "Yellow", "Aqua", "Pink", "Green", "Cyan", "Orange", "Purple"};

    private final Driver d;
    private final Session s;
    private final Engine e;
    private final ClientView v;
    private int checks;
    private final List<String> failures = new ArrayList<>();
    private String section = "";

    public SelfTest(Driver driver) {
        this.d = driver;
        this.s = driver.session();
        this.e = s.engine;
        this.v = s.view;
    }

    public List<String> failures() {
        return failures;
    }

    /** @return "N checks, F failed". */
    public String run() {
        List<Runnable> sections = List.of(this::open, this::navigation, this::renew, this::stakes, this::rngSelected, this::chronoTiming, this::lateClick,
            this::chrono26, this::chrono22, this::chrono6, this::ultra30, this::ultra10, this::superpairs36, this::superpairsEnd, this::rare, this::faults);
        for (Runnable r : sections) {
            reset();
            try {
                r.run();
            } catch (RuntimeException ex) {
                StackTraceElement at = ex.getStackTrace().length > 0 ? ex.getStackTrace()[0] : null;
                fail("exception " + ex + " at " + at);
            }
        }
        reset();
        return checks + " checks, " + failures.size() + " failed";
    }

    // ---- helpers ----

    private void reset() {
        e.close();
        e.daily.reset();
        e.lateClick = false;
        s.wire.lagMs = 0;
        s.wire.split = false;
        step(12);
        s.wire.clear();
    }

    private void section(String name) {
        section = name;
    }

    private boolean check(boolean ok, String what) {
        checks++;
        if (!ok) fail(what);
        return ok;
    }

    private void fail(String what) {
        failures.add("[" + section + "] " + what);
    }

    private void step() {
        s.tick();
    }

    private void step(int n) {
        for (int i = 0; i < n; i++) step();
    }

    private boolean until(BooleanSupplier cond, int max) {
        for (int i = 0; i < max && !cond.getAsBoolean(); i++) step();
        return cond.getAsBoolean();
    }

    private List<ClientView.Heard> heard(int mark, Predicate<Packet> what) {
        return v.since(mark).stream().filter(h -> what.test(h.packet())).toList();
    }

    private static boolean sound(Packet p, String id) {
        return p instanceof Packet.Sound snd && snd.id().equals(id);
    }

    private static boolean sound(Packet p, String id, int pitch63) {
        return p instanceof Packet.Sound snd && snd.id().equals(id) && Math.round(snd.pitch() * 63) == pitch63;
    }

    private static boolean isOpen(Packet p) {
        return p instanceof Packet.Open;
    }

    private static boolean isContent(Packet p) {
        return p instanceof Packet.Content;
    }

    private static boolean isChat(Packet p) {
        return p instanceof Packet.Chat;
    }

    private List<String> chats(int mark) {
        return heard(mark, SelfTest::isChat).stream().map(h -> ((Packet.Chat) h.packet()).text()).toList();
    }

    private String lastLore(int slot) {
        Item item = v.slot(slot);
        return item == null || item.lore().isEmpty() ? "" : item.plainLore().get(item.lore().size() - 1);
    }

    private boolean loreHas(int slot, String plain) {
        Item item = v.slot(slot);
        return item != null && item.plainLore().contains(plain);
    }

    /** Clicks and waits until a new screen opens; @return ticks from the click's arrival (lag 0: the next tick) to the open. */
    private long clickToOpen(int slot, Click click, int max) {
        int mark = v.size();
        long sent = e.tick;
        d.click(slot, click);
        until(() -> !heard(mark, SelfTest::isOpen).isEmpty(), max);
        List<ClientView.Heard> opens = heard(mark, SelfTest::isOpen);
        return opens.isEmpty() ? -1 : opens.get(0).tick() - (sent + 1);
    }

    private void startGame(String game, String tier, String seed, int extra, String rare) {
        e.start(game, tier, seed, extra, false, rare);
        s.flush();
    }

    // ---- menus ----

    private void open() {
        section("open");
        int mark = v.size();
        e.openTable();
        s.flush();
        long t0 = e.tick;
        List<ClientView.Heard> h = v.since(mark);
        check(h.size() > 2 && h.get(0).packet() instanceof Packet.Sound snd && snd.id().equals("block.lever.click") && snd.volume() == 0.5f && snd.pitch() == 1f,
            "lever click 0.5/1.0 before the open");
        check(h.size() > 2 && h.get(1).packet() instanceof Packet.Open o && o.title().equals("§rExperimentation Table") && o.rows() == 6, "open_screen Experimentation Table 9x6");
        check(heard(mark, p -> p instanceof Packet.Slot).stream().filter(x -> x.tick() == t0).count() == 54, "all 54 slots as single set_slot in the open's tick");
        until(() -> !heard(mark, SelfTest::isContent).isEmpty(), 10);
        List<ClientView.Heard> content = heard(mark, SelfTest::isContent);
        check(content.size() == 1 && content.get(0).tick() - t0 >= 4 && content.get(0).tick() - t0 <= 6, "one full set_content 4-6 ticks after the open");
        check(lastLore(22).equals("Play add-ons first!") && loreHas(22, "Charges: 1/3"), "Superpairs head: 1/3 charges, Play add-ons first!");
        check(v.name(20).equals("Pending experiment...") && v.name(24).equals("Pending experiment..."), "status panes pending");
        check(lastLore(29).equals("Click to play!") && lastLore(33).equals("Click to play!"), "add-ons playable");
        check(v.slot(31) != null && v.slot(31).path().equals("black_stained_glass_pane"), "no Renew while charges are left");
        check(v.slot(0).path().equals("purple_stained_glass_pane") && v.slot(10).path().equals("black_stained_glass_pane"), "purple border, black inside");
    }

    private void navigation() {
        section("navigation");
        e.openTable();
        s.flush();
        int cid = v.cid;
        check(clickToOpen(29, Click.LEFT, 5) == 1, "Chronomatron head pages to its stakes in the next tick");
        check(v.plainTitle().equals("Chronomatron ➜ Stakes") && v.rows == 5 && v.cid != cid && v.open, "Chronomatron ➜ Stakes 9x5, new container, no close");
        check(lastLore(24).equals("Click to play!") && v.name(24).equals("Metaphysical Experiment"), "Metaphysical tier at 24");
        clickToOpen(39, Click.LEFT, 5);
        check(v.plainTitle().equals("Experimentation Table"), "Go Back opens the table");
        clickToOpen(33, Click.LEFT, 5);
        check(v.plainTitle().equals("Ultrasequencer ➜ Stakes") && v.name(23).equals("Metaphysical Experiment"), "Ultrasequencer stakes, Metaphysical at 23");
        clickToOpen(39, Click.LEFT, 5);
        clickToOpen(48, Click.LEFT, 5);
        check(v.plainTitle().equals("(1/3) Experimentation Table RNG") && v.name(20).equals("Thunderbolt VI"), "RNG Meter head opens page 1");
        check(v.name(53).equals("Next Page") && v.slot(45).path().equals("black_stained_glass_pane"), "page 1: Next Page, no Previous Page");
        clickToOpen(53, Click.LEFT, 5);
        check(v.plainTitle().equals("(2/3) Experimentation Table RNG") && v.slot(20).lore().get(0).contains("§r§8§m0.0263%") && lastLore(20).equals("SELECTED"),
            "page 2: Looting V selected, base odds struck through");
        clickToOpen(53, Click.LEFT, 5);
        check(v.plainTitle().equals("(3/3) Experimentation Table RNG") && v.name(45).equals("Previous Page") && loreHas(45, "Page 2") && v.slot(14) == null,
            "page 3: Previous Page, empty entry slots");
        clickToOpen(45, Click.LEFT, 5);
        check(v.plainTitle().equals("(2/3) Experimentation Table RNG"), "Previous Page");
        int mark = v.size();
        d.click(49, Click.LEFT);
        step(2);
        check(!heard(mark, p -> p instanceof Packet.Close).isEmpty() && !v.open, "Close closes from the server");
    }

    private void renew() {
        section("renew");
        e.daily.charges = 0;
        e.openTable();
        s.flush();
        check(v.slot(31) != null && v.slot(31).path().equals("blaze_powder") && loreHas(31, "You've renewed 0/3 charges today!") && loreHas(31, "50 XP Levels"),
            "Renew at 0 charges, 0/3, 50 levels");
        check(lastLore(29).equals("On cooldown!") && loreHas(29, "Right-Click to practice!") && lastLore(22).equals("Experiments on cooldown!"), "cooldown lore at 0 charges");
        int cid = v.cid, mark = v.size();
        clickToOpen(31, Click.LEFT, 5);
        check(chats(mark).equals(List.of("§r§d☕ §r§eYou bought a bonus charge for the Experimentation Table! §r§b(1/3)")), "renew chat (1/3)");
        check(v.cid != cid && loreHas(22, "Charges: 1/3") && v.slot(31).path().equals("black_stained_glass_pane"), "the table re-opens with 1/3 charges");
    }

    private void stakes() {
        section("stakes");
        e.daily.addons("done");
        e.openTable();
        s.flush();
        check(lastLore(22).equals("Click to play!") && v.name(20).equals("Experiment completed!") && loreHas(23, "Bonus: +3 clicks"), "add-ons done: Superpairs playable");
        clickToOpen(22, Click.LEFT, 5);
        check(v.plainTitle().equals("Superpairs ➜ Stakes") && v.rows == 6, "Superpairs ➜ Stakes 9x6");
        check(loreHas(31, "Clicks: 23") && loreHas(31, " +5 add-ons"), "Transcendent: 16 + 5 + 2 = 23 clicks");
        check(lastLore(32).equals("Enchanting level too low!"), "Metaphysical: Enchanting level too low!");
        int cid = v.cid;
        d.click(32, Click.LEFT);
        step(4);
        check(v.cid == cid, "a locked tier opens nothing");
        clickToOpen(31, Click.LEFT, 5);
        check(v.plainTitle().equals("Superpairs (Transcendent)") && v.name(4).equals("Remaining Clicks: 23"), "Transcendent game with 23 clicks");
        long closed = e.tick;
        d.close();
        int mark = v.size();
        until(() -> !heard(mark, SelfTest::isOpen).isEmpty(), 10);
        check(v.plainTitle().equals("Superpairs Rewards") && heard(mark, SelfTest::isOpen).get(0).tick() - (closed + 1) == 4, "Esc: Superpairs Rewards 4 ticks later");
        check(loreHas(13, "Clicks used: 0") && loreHas(13, "Pairs found: 0"), "nothing used, nothing found");
    }

    private void rngSelected() {
        section("rng selected");
        e.daily.rngSelected = "Growth VI";
        e.openRng(1);
        s.flush();
        Item growth = v.slot(21);
        check(growth != null && growth.plainName().equals("Growth VI") && growth.lore().get(0).equals("§r§7Odds: §r§bRare §r§7(§r§8§m5.3191%§r §r§77.3599%)")
            && lastLore(21).equals("SELECTED"), "Growth VI selected: struck 5.3191 %, SELECTED");
        check(loreHas(4, "Growth VI") && loreHas(4, "Progress: 19.2%"), "meter head names Growth VI at 19.2 %");
        clickToOpen(53, Click.LEFT, 5);
        check(lastLore(20).equals("Click to select!") && v.slot(20).lore().get(0).equals("§r§7Odds: §r§cRNGesus Incarnate §r§7(§r§70.0263%)"), "Looting V unselected");
        e.close();
        startGame("sp", "Transcendent", "c17", 23, "selected");
        Superpairs sp = (Superpairs) e.game;
        long n = sp.board.values().stream().filter(c -> c.plainName().equals("Enchanted Book") && c.lore().size() > 2 && c.plainLore().get(2).equals("Growth VI")).count();
        check(n == 2, "rare=selected deals a Growth VI book pair");
    }

    // ---- Chronomatron ----

    /** One round as a client: notes from glass → terracotta changes during the display, then the answer. */
    private List<String> chronoRound(boolean answer) {
        List<String> notes = new ArrayList<>();
        int mark = v.size();
        int round = v.slot(4) == null ? 0 : v.slot(4).count();
        long[] start = {-1};
        boolean ok = until(() -> {
            notes.clear();
            boolean display = true;
            long roundTick = -1;
            for (ClientView.Heard h : v.since(mark)) {
                if (!(h.packet() instanceof Packet.Slot sl) || sl.item() == null) continue;
                if (sl.slot() == 4) roundTick = h.tick();
                if (sl.slot() == 49) display = sl.item().path().equals("glowstone");
                if (display && sl.item().path().endsWith("_terracotta") && h.tick() != roundTick && isTop(sl.slot())) notes.add(sl.item().plainName());
            }
            return !display;
        }, 1200);
        check(ok && notes.size() == round, "round " + round + ": " + notes.size() + " notes before the clock");
        if (!answer) return notes;
        int m = v.size();
        for (String note : notes) {
            int slot = topSlot(note);
            int mp = v.size();
            d.click(slot, Click.MIDDLE);
            until(() -> heard(mp, p -> sound(p, "block.note_block.pling")).size() > 0, 6);
        }
        until(() -> v.slot(4) != null && v.slot(4).count() == round + 1, 6);
        check(!heard(m, p -> sound(p, "entity.player.levelup")).isEmpty(), "round " + round + ": levelup on the last answer");
        return notes;
    }

    private boolean isTop(int slot) {
        Item item = v.slot(slot);
        return item != null && topSlot(item.plainName()) == slot;
    }

    private int topSlot(String color) {
        for (int i = 0; i < v.slots.length; i++) {
            Item item = v.slots[i];
            if (item != null && item.plainName().equals(color) && (item.path().endsWith("_stained_glass") || item.path().endsWith("_terracotta"))) return i;
        }
        return -1;
    }

    private void chronoTiming() {
        section("chrono timing");
        startGame("chrono", "Metaphysical", "7", 0, null);
        long open = e.tick;
        int mark = v.size();
        check(v.name(4).equals("Round: 1") && v.name(49).equals("Remember the pattern!") && v.name(11).equals("Red") && v.name(20).equals("Red") && v.name(15).equals("Aqua")
            && v.slot(15).path().equals("light_blue_stained_glass"), "board: Round 1, glowstone, stacked color cells (Aqua = light_blue)");
        // during the display (before the first pling): a PICKUP is ignored but resynced 7-8 ticks after it arrived; a CLONE gets nothing
        step(6); // past the open's own set_content
        int m1 = v.size();
        long sent = e.tick;
        d.click(11, Click.LEFT);
        step();
        d.click(11, Click.MIDDLE);
        until(() -> !heard(m1, SelfTest::isContent).isEmpty(), 12);
        step(2);
        List<ClientView.Heard> rs = heard(m1, SelfTest::isContent);
        check(rs.size() == 1 && rs.get(0).tick() - (sent + 1) >= 7 && rs.get(0).tick() - (sent + 1) <= 8, "PICKUP during the display: one resync 7-8 ticks later, CLONE none");
        check(heard(m1, p -> sound(p, "block.note_block.pling")).isEmpty() || heard(m1, p -> sound(p, "block.note_block.pling")).get(0).tick() >= open + 14,
            "clicks during the display: no echo");
        until(() -> v.slot(49).path().equals("clock"), 40);
        List<ClientView.Heard> slots = heard(mark, p -> p instanceof Packet.Slot sl && sl.item() != null && sl.item().path().endsWith("_terracotta"));
        check(!slots.isEmpty() && slots.get(0).tick() == open + 16, "first note lit at +16");
        List<ClientView.Heard> plings = heard(mark, p -> sound(p, "block.note_block.pling"));
        long lead = slots.isEmpty() || plings.isEmpty() ? -1 : slots.get(0).tick() - plings.get(0).tick();
        check(lead == 1 || lead == 2, "pling 1-2 ticks before the lit cells (" + lead + ")");
        long clockTick = heard(mark, p -> p instanceof Packet.Slot sl && sl.slot() == 49 && sl.item() != null && sl.item().path().equals("clock")).get(0).tick();
        List<ClientView.Heard> last = v.since(mark).stream().filter(h -> h.tick() == clockTick && h.packet() instanceof Packet.Slot).toList();
        check(last.size() == 3 && ((Packet.Slot) last.get(2).packet()).slot() == 49 && v.name(49).equals("Timer: 3s") && v.slot(49).count() == 3,
            "last note: both cells then the clock Timer: 3s in one tick");
        int top = ((Packet.Slot) last.get(0).packet()).slot();
        int m3 = v.size();
        step(8);
        check(v.slot(top).path().endsWith("_stained_glass"), "lit for 8 ticks");
        d.click(top, Click.DOUBLE);
        step();
        check(!heard(m3, p -> sound(p, "block.note_block.pling")).isEmpty() && !heard(m3, p -> sound(p, "entity.player.levelup", 111)).isEmpty(),
            "PICKUP_ALL counted: pling + levelup");
        step();
        check(v.name(4).equals("Round: 2") && v.slot(4).count() == 2 && v.name(49).equals("Remember the pattern!") && v.slot(top).path().endsWith("_terracotta"),
            "next tick: Round 2 + glowstone, the echo lit");
        // timer: one per 20 ticks
        chronoRound(false);
        int m4 = v.size();
        step(20);
        check(v.name(49).equals("Timer: 3s"), "the clock counts down once per 20 ticks");
        check(heard(m4, SelfTest::isOpen).isEmpty(), "still playing");
    }

    private void lateClick() {
        section("lateclick");
        for (boolean late : new boolean[] {false, true}) {
            reset();
            e.lateClick = late;
            startGame("chrono", "Metaphysical", "11", 0, null);
            Chrono c = (Chrono) e.game;
            long pling = c.acceptFrom + (late ? 1 : 0);
            until(() -> e.tick >= pling - 2, 40);
            int mark = v.size();
            d.click(c.note(0).top(), Click.MIDDLE); // arrives at pling - 1
            step(3);
            boolean counted = heard(mark, p -> sound(p, "entity.player.levelup")).size() == 1;
            check(counted == late, "a click arriving one tick before the last pling is " + (late ? "counted with lateclick on" : "ignored with lateclick off"));
        }
        reset();
        startGame("chrono", "Metaphysical", "11", 0, null);
        Chrono c = (Chrono) e.game;
        long pling = c.acceptFrom;
        until(() -> e.tick >= pling - 1, 40);
        int mark = v.size();
        d.click(c.note(0).top(), Click.MIDDLE); // arrives in the pling's tick, before the client sees the clock
        step(2);
        check(heard(mark, p -> sound(p, "entity.player.levelup")).size() == 1, "a click arriving in the last pling's tick counts (5625)");
    }

    private static final List<String> OVER_26 = List.of("§r§8Claim", "", "§r§cYou closed the game!", "", "§r§7Stakes: §r§dMetaphysical", "", "§r§7Longest Chain: §r§a9 notes", "",
        "§r§7Rewards:", "§r§8 +§r§354,000 Enchanting Exp", "§r§8 +§r§d3 Superpairs clicks", "", "§r§eClick to claim rewards!");
    private static final List<String> OVER_22 = List.of("§r§8Claim", "", "§r§cYou picked the wrong color!", "", "§r§7Stakes: §r§dMetaphysical", "", "§r§7Longest Chain: §r§a4 notes", "",
        "§r§7Rewards:", "§r§8 +§r§324,000 Enchanting Exp", "§r§8 +§r§d1 Superpairs click", "", "§r§eClick to claim rewards!");
    private static final List<String> OVER_6 = List.of("§r§8Claim", "", "§r§cYou closed the game!", "", "§r§7Stakes: §r§dMetaphysical", "", "§r§7Longest Chain: §r§a6 notes", "",
        "§r§7Rewards:", "§r§8 +§r§336,000 Enchanting Exp", "§r§8 +§r§d2 Superpairs clicks", "", "§r§eClick to claim rewards!");
    private static final List<String> OVER_10 = List.of("§r§8Game closed", "", "§r§7Stakes: §r§dMetaphysical", "§r§7Highest series: §r§a5 numbers", "", "§r§7Rewards:",
        "§r§8 +§r§342,000 Enchanting Exp", "§r§8 +§r§d3 Superpairs clicks", "", "§r§eClick to claim rewards!");
    private static final List<String> OVER_30 = List.of("§r§8Clicked wrong number", "", "§r§7Stakes: §r§dMetaphysical", "§r§7Highest series: §r§a7 numbers", "", "§r§7Rewards:",
        "§r§8 +§r§356,000 Enchanting Exp", "§r§8 +§r§d3 Superpairs clicks", "", "§r§eClick to claim rewards!");

    private void chrono26() {
        section("chrono c26");
        startGame("chrono", "Metaphysical", "c26", 0, null);
        List<String> seen = new ArrayList<>();
        for (int r = 1; r <= 9; r++) {
            int mark = v.size();
            seen = chronoRound(true);
            if (r == 9) check(chats(mark).contains(MenuItems.MAX_CLICKS_CHAT), "max clicks chat at round 10's start");
        }
        check(seen.equals(Chrono.RECORDED.get("c26")), "c26 replays the recorded sequence " + seen);
        step(20);
        long closed = e.tick;
        d.close();
        int mark = v.size();
        until(() -> !heard(mark, SelfTest::isOpen).isEmpty(), 10);
        check(v.plainTitle().equals("Experiment Over") && v.rows == 3 && heard(mark, SelfTest::isOpen).get(0).tick() - (closed + 1) == 4, "Esc: Experiment Over 4 ticks later");
        check(v.slot(11) != null && v.slot(11).lore().equals(OVER_26), "Over head as captured (container 27)");
        int m2 = v.size();
        d.click(11, Click.LEFT);
        step(2);
        check(!v.open && chats(m2).equals(List.of("§r§eYou claimed the §r§dChronomatron §r§erewards!", "§r §r§8+§r§354,000 Enchanting Exp", "§r§8 +§r§d3 Superpairs clicks")),
            "claim: server close + the captured chat");
        e.openTable();
        s.flush();
        check(v.name(20).equals("Experiment completed!") && loreHas(20, "Bonus: +3 clicks") && Boolean.TRUE.equals(v.slot(20).glint()) && lastLore(29).equals("On cooldown!"),
            "table: Chronomatron completed +3, on cooldown");
    }

    private void chrono22() {
        section("chrono c22");
        startGame("chrono", "Metaphysical", "c22", 0, null);
        for (int r = 1; r <= 4; r++) chronoRound(true);
        List<String> notes = chronoRound(false);
        check(notes.equals(Chrono.RECORDED.get("c22")), "c22 replays the recorded sequence " + notes);
        String wrong = notes.get(0).equals("Red") ? "Blue" : "Red";
        long sent = e.tick;
        int mark = v.size();
        d.click(topSlot(wrong), Click.MIDDLE);
        until(() -> !heard(mark, SelfTest::isOpen).isEmpty(), 10);
        check(!heard(mark, SelfTest::isOpen).isEmpty() && heard(mark, SelfTest::isOpen).get(0).tick() - (sent + 1) == 3, "wrong color: Experiment Over paged in 3 ticks later");
        check(heard(mark, p -> p instanceof Packet.Close).isEmpty(), "no close before the Over (a page)");
        check(v.slot(11) != null && v.slot(11).lore().equals(OVER_22), "Over head as captured (container 23)");
        int m2 = v.size();
        d.click(15, Click.LEFT);
        step(3);
        check(!v.open && chats(m2).isEmpty(), "Decline: server close, no chat");
        e.openStakes("chrono", false);
        s.flush();
        check(loreHas(24, "Starting cost: 40 XP Levels") && loreHas(20, "Starting cost: 5 XP Levels"), "after a decline every tier shows its starting cost");
    }

    private void chrono6() {
        section("chrono c6");
        startGame("chrono", "Metaphysical", "c6", 0, null);
        List<String> notes = List.of();
        for (int r = 1; r <= 6; r++) notes = chronoRound(true);
        check(notes.equals(Chrono.RECORDED.get("c6")), "c6 replays the recorded sequence " + notes);
        step(30);
        d.close();
        int mark = v.size();
        until(() -> !heard(mark, SelfTest::isOpen).isEmpty(), 10);
        check(v.slot(11) != null && v.slot(11).lore().equals(OVER_6), "Over head as captured (container 7)");
    }

    // ---- Ultrasequencer ----

    /** One round as a client: the burst's numbers, the hide, then the answer (or [wrong]). */
    private List<Integer> ultraRound(int n, boolean answer, boolean wrong) {
        List<Integer> slots = new ArrayList<>();
        until(() -> v.slot(49) != null && v.slot(49).path().equals("glowstone") && v.slot(4) != null, 5);
        long burst = e.tick;
        for (int k = 1; k <= n; k++) {
            for (int i = 9; i <= 44; i++) {
                Item item = v.slot(i);
                if (item != null && item.plainName().equals(String.valueOf(k)) && item.count() == k) slots.add(i);
            }
        }
        check(slots.size() == n, "round " + n + ": numbers 1-" + n + " on the board");
        String pane = Ultra.PANES[n - 1] + "_stained_glass_pane";
        boolean panes = true;
        for (int i = 9; i <= 44; i++) if (!slots.contains(i) && !v.slot(i).path().equals(pane)) panes = false;
        check(panes && v.slot(4).path().equals("black_stained_glass_pane"), "round " + n + ": " + pane + " board, no round item");
        until(() -> v.slot(49).path().equals("clock"), 200);
        check(e.tick - burst == 41 + 6L * (n - 1), "round " + n + ": numbers shown " + (e.tick - burst) + " ticks");
        check(v.name(49).equals("Timer: " + (n + 3) + "s") && slots.stream().allMatch(i -> v.slot(i).path().equals(pane)), "round " + n + ": hide + Timer: " + (n + 3) + "s in one tick");
        if (wrong) {
            int bad = 9;
            while (slots.get(0) == bad) bad++;
            d.click(bad, Click.MIDDLE);
            return slots;
        }
        if (!answer) return slots;
        int prev = 0;
        boolean rising = true;
        for (int i = 0; i < n; i++) {
            int m = v.size();
            d.click(slots.get(i), Click.MIDDLE);
            until(() -> !heard(m, p -> sound(p, "entity.item.pickup")).isEmpty(), 5);
            List<ClientView.Heard> pick = heard(m, p -> sound(p, "entity.item.pickup"));
            int pitch = pick.isEmpty() ? 0 : Math.round(((Packet.Sound) pick.get(0).packet()).pitch() * 63);
            if (pitch <= prev) rising = false;
            prev = pitch;
            if (i < n - 1) {
                step();
                if (!v.name(slots.get(i)).equals(String.valueOf(i + 1))) rising = false;
            } else check(!heard(m, p -> sound(p, "entity.player.levelup")).isEmpty(), "round " + n + ": levelup on the last number");
        }
        check(rising, "round " + n + ": pickup pitch rises, every answer re-shows its number");
        step();
        return slots;
    }

    private void ultra30() {
        section("ultra c30");
        startGame("ultra", "Metaphysical", "c30", 0, null);
        boolean recorded = true;
        int mark = v.size();
        for (int n = 1; n <= 7; n++) {
            List<Integer> slots = ultraRound(n, true, false);
            int[] rec = Ultra.RECORDED.get("c30")[n - 1];
            for (int i = 0; i < rec.length; i++) if (i >= slots.size() || slots.get(i) != rec[i]) recorded = false;
        }
        check(recorded, "c30 replays the recorded slots");
        check(chats(mark).contains(MenuItems.MAX_CLICKS_CHAT), "max clicks chat (at round 6's hide)");
        long sent;
        int m = v.size();
        ultraRound(8, false, true);
        sent = e.tick;
        until(() -> !heard(m, SelfTest::isOpen).isEmpty(), 6);
        List<ClientView.Heard> opens = heard(m, SelfTest::isOpen);
        check(!opens.isEmpty() && opens.get(0).tick() - (sent + 1) == 1, "wrong number: Over in the next tick");
        check(v.slot(11) != null && v.slot(11).lore().equals(OVER_30), "Over head as captured (container 31)");
        int m2 = v.size();
        d.click(11, Click.LEFT);
        step(2);
        check(chats(m2).equals(List.of("§r§eYou claimed the §r§dUltrasequencer §r§erewards!", "§r §r§8+§r§356,000 Enchanting Exp", "§r§8 +§r§d3 Superpairs clicks")),
            "claim chat as captured");
    }

    private void ultra10() {
        section("ultra c10");
        startGame("ultra", "Metaphysical", "c10", 0, null);
        for (int n = 1; n <= 5; n++) ultraRound(n, true, false);
        List<Integer> r6 = ultraRound(6, false, false);
        int[] rec = Ultra.RECORDED.get("c10")[5];
        boolean same = r6.size() == rec.length;
        for (int i = 0; same && i < rec.length; i++) same = r6.get(i) == rec[i];
        check(same, "c10 round 6 as recorded (3 stays at 31, never re-sent)");
        long closed = e.tick;
        d.close();
        int mark = v.size();
        until(() -> !heard(mark, SelfTest::isOpen).isEmpty(), 10);
        check(!heard(mark, SelfTest::isOpen).isEmpty() && heard(mark, SelfTest::isOpen).get(0).tick() - (closed + 1) == 4, "Esc: Over 4 ticks later");
        check(v.slot(11) != null && v.slot(11).lore().equals(OVER_10), "Over head as captured (container 11): series 5 pays 6 x 7k and +3");
    }

    // ---- Superpairs ----

    private int glassCount(String name) {
        int n = 0;
        for (int slot : Superpairs.GRID) if (v.slot(slot) != null && v.slot(slot).path().equals("cyan_stained_glass") && v.name(slot).equals(name)) n++;
        return n;
    }

    /** Clicks a card; @return ticks from the click's arrival to [sound] (-1: none within 4 ticks). */
    private long spClick(int slot, String sound) {
        int m = v.size();
        long sent = e.tick;
        d.click(slot, Click.LEFT);
        step(2);
        List<ClientView.Heard> h = sound == null ? List.of() : heard(m, p -> sound(p, sound));
        return h.isEmpty() ? -1 : h.get(0).tick() - (sent + 1);
    }

    private void superpairs36() {
        section("superpairs c36");
        startGame("sp", "Transcendent", "c36", 24, null);
        step(8); // past the open's own set_content
        check(glassCount("Click any button!") == 28 && v.name(4).equals("Remaining Clicks: 24") && v.slot(4).count() == 24
            && v.name(49).equals("Timer: 90s") && v.slot(49).count() == 64, "board: 28 hidden, 24 clicks, Timer 90s (count 64)");
        int m = v.size();
        long sent = e.tick;
        d.click(10, Click.LEFT);
        step();
        check(heard(m, p -> sound(p, "block.wooden_pressure_plate.click_on", 226)).size() == 1, "first card: pressure plate at 0.15 x 24 (226/63)");
        check(!v.name(10).equals("30k Enchanting Exp"), "card not shown in the click's tick");
        step();
        check(v.name(10).equals("30k Enchanting Exp") && v.name(4).equals("Remaining Clicks: 23") && glassCount("Click a second button!") == 27, "next tick: card, 23, Click a second button!");
        until(() -> !heard(m, SelfTest::isContent).isEmpty(), 10);
        long rs = heard(m, SelfTest::isContent).isEmpty() ? -1 : heard(m, SelfTest::isContent).get(0).tick() - (sent + 1);
        check(rs == 7 || rs == 8, "PICKUP: full resync 7-8 ticks later (" + rs + ")");
        long missSent = e.tick;
        check(spClick(11, "entity.player.burp") == 0 && glassCount("?") == 26 && v.name(11).equals("25k Enchanting Exp"), "mismatch: burp, both shown, glass ?");
        until(() -> glassCount("Click any button!") == 28, 40);
        check(glassCount("Click any button!") == 28 && e.tick - (missSent + 1) == 31, "mismatch hides itself 31 ticks after the click (" + (e.tick - missSent - 1) + ")");
        spClick(10, "block.wooden_pressure_plate.click_on");
        check(spClick(33, "entity.player.levelup") == 0 && v.name(33).equals("30k Enchanting Exp") && v.name(10).equals("30k Enchanting Exp")
            && glassCount("Click any button!") == 26, "match: levelup, both stay, Click any button!");
        spClick(13, "block.wooden_pressure_plate.click_on");
        spClick(16, "entity.player.burp");
        String remaining = v.name(4);
        int m2 = v.size();
        d.click(19, Click.LEFT);
        step(2);
        check(heard(m2, p -> p instanceof Packet.Sound).isEmpty() && glassCount("Click any button!") == 26 && v.name(4).equals(remaining),
            "a click during ? only hides the pair (no sound, no click)");
        int m3 = v.size();
        long t3 = e.tick;
        d.click(29, Click.LEFT);
        step(8);
        List<ClientView.Heard> hats = heard(m3, p -> sound(p, "block.note_block.hat"));
        check(hats.size() == 3 && Math.round(((Packet.Sound) hats.get(0).packet()).pitch() * 63) == 107 && hats.get(1).tick() - hats.get(0).tick() == 3
            && hats.get(2).tick() - hats.get(1).tick() == 3 && !heard(m3, p -> sound(p, "entity.player.levelup")).isEmpty(), "Instant Find: levelup + three hats 3 ticks apart");
        check(v.name(4).equals(remaining) && glassCount("Next button is instantly rewarded!") == 25 && v.name(29).equals("Instant Find") && t3 > 0,
            "Instant Find: clicks unchanged, glass armed");
        check(spClick(14, "entity.player.levelup") == 0 && v.name(14).equals("Titanic Experience Bottle") && v.name(28).equals("Titanic Experience Bottle")
            && glassCount("Click any button!") == 23, "armed click: the card and its partner");
        spClick(16, "block.wooden_pressure_plate.click_on");
        int before = v.slot(4).count();
        check(spClick(15, "entity.player.levelup") == 0 && v.slot(4).count() == before + 2 && v.name(16).equals("44k Enchanting Exp")
            && glassCount("Click a second button!") == 21, "+3 Clicks as second card: +3 -1, the first card stays open");
        check(spClick(34, "entity.player.levelup") == 0, "the open first card still matches");
        // burn the clicks on known-different cards until the board runs out
        Superpairs sp = (Superpairs) e.game;
        int guard = 0;
        while (!v.name(4).equals("Remaining Clicks: 0") && guard++ < 60) {
            List<Integer> hidden = new ArrayList<>();
            for (int slot : Superpairs.GRID) if (v.slot(slot).path().equals("cyan_stained_glass")) hidden.add(slot);
            int a = hidden.get(0), b = -1;
            for (int x : hidden) if (x != a && !sp.board.get(x).cardKey().equals(sp.board.get(a).cardKey())) { b = x; break; }
            spClick(a, null);
            if (v.name(4).equals("Remaining Clicks: 0")) break;
            spClick(b, null);
            if (glassCount("?") > 0 && !v.name(4).equals("Remaining Clicks: 0")) spClick(a, null);
        }
        check(v.slot(4).path().equals("cauldron") && glassCount("?") > 0, "out of clicks: cauldron, glass ?");
        int hiddenSlot = -1;
        for (int slot : Superpairs.GRID) if (hiddenSlot < 0 && v.name(slot).equals("?")) hiddenSlot = slot;
        spClick(hiddenSlot, null);
        check(lastLore(10).equals("Click any to proceed!") && lastLore(43).equals("Click any to proceed!"), "final board: every card face-up with Click any to proceed!");
        check(Boolean.TRUE.equals(v.slot(10).glint()) && Boolean.TRUE.equals(v.slot(14).glint()) && Boolean.TRUE.equals(v.slot(29).glint()) && Boolean.TRUE.equals(v.slot(15).glint())
            && v.slot(13).glint() == null, "glint on matched cards and used powerups only");
        int m4 = v.size();
        d.click(11, Click.LEFT);
        step(3);
        check(heard(m4, SelfTest::isOpen).isEmpty(), "a click right after the final board shows is ignored");
        step(30);
        int m5 = v.size();
        d.click(11, Click.LEFT);
        step(2);
        check(!heard(m5, p -> sound(p, "block.wood.break")).isEmpty() && v.plainTitle().equals("Superpairs Rewards"), "a later click: wood.break + Superpairs Rewards");
        check(loreHas(13, "Pairs found: 3") && loreHas(13, "Stakes: Transcendent") && loreHas(13, " +300,000 Enchanting Exp (Stakes)")
            && loreHas(13, " + Titanic Experience Bottle"), "Rewards head: 3 pairs (powerups are not pairs), stakes XP, the Titanic bottle");
        int m6 = v.size();
        d.click(13, Click.LEFT);
        step(2);
        List<String> chat = chats(m6);
        check(!v.open && chat.size() >= 3 && chat.get(0).equals("§r§eYou claimed the §r§cSuperpairs §r§erewards!") && chat.get(1).equals("§r§8 +§r§3300k Enchanting Exp")
            && chat.contains("§r §r§8+§r§9Titanic Experience Bottle"), "claim: server close + chat");
    }

    private void superpairsEnd() {
        section("superpairs end");
        startGame("sp", "Transcendent", "c17", 1, null);
        spClick(11, "block.wooden_pressure_plate.click_on");
        check(v.slot(4).path().equals("cauldron") && glassCount("?") == 27 && v.name(11).equals("42k Enchanting Exp"), "the last click: card shown, cauldron, glass ?");
        spClick(12, null);
        check(lastLore(12).equals("Click any to proceed!") && v.slot(11).glint() == null, "a click on ?: the final board, nothing glinted");
        long closed = e.tick;
        d.close();
        int mark = v.size();
        until(() -> !heard(mark, SelfTest::isOpen).isEmpty(), 10);
        check(v.plainTitle().equals("Superpairs Rewards") && heard(mark, SelfTest::isOpen).get(0).tick() - (closed + 1) == 4 && loreHas(13, "Clicks used: 1"),
            "closing the final board: Rewards 4 ticks later");
    }

    private void rare() {
        section("rare cards");
        startGame("sp", "Transcendent", "c36", 24, "both");
        Superpairs sp = (Superpairs) e.game;
        Set<String> rngNames = new HashSet<>();
        for (Captured.RngPage page : Captured.get().rng) for (Item item : page.slots()) if (item != null) rngNames.add(item.plainName());
        int meter = 0, header = 0, meterSlot = -1;
        for (var entry : sp.board.entrySet()) {
            Item c = entry.getValue();
            if (c.plainName().equals("Enchanted Book") && c.lore().size() > 2 && c.plainLore().get(2).equals("Thunderbolt VII") && c.count() == 7
                && c.path().equals("diamond_block") && !c.plainLore().get(0).contains("ULTRA-RARE")) {
                meter++;
                meterSlot = entry.getKey();
            }
            if (c.lore().get(0).equals("§r§d§kXX§5 ULTRA-RARE BOOK! §d§kXX") && !rngNames.contains(c.plainLore().get(2))) header++;
        }
        check(meter == 2 && Captured.get().rngEntry("Thunderbolt VII").lore().get(0).contains("0.0263%"), "rare=meter: a Thunderbolt VII pair (0.0263 % on the meter), no header");
        check(header == 2, "rare=header: an ULTRA-RARE BOOK! pair that is not on the meter");
        spClick(meterSlot, null);
        check(v.slot(meterSlot) != null && v.slot(meterSlot).equals(sp.board.get(meterSlot)), "the rare card reaches the client as dealt");
    }

    private void faults() {
        section("faults");
        startGame("chrono", "Metaphysical", "5", 0, null);
        step(3);
        int m = v.size();
        e.content();
        s.flush();
        check(heard(m, SelfTest::isContent).size() == 1, "resync: one extra set_content now");
        int cid = v.cid;
        String title = v.title;
        int m2 = v.size();
        e.reopen();
        s.flush();
        check(v.cid != cid && v.title.equals(title) && heard(m2, p -> p instanceof Packet.Slot).size() == 54, "reopen: open_screen again with the same title, every slot");
        chronoRound(true);
        check(v.name(4).equals("Round: 2"), "the game goes on in the re-sent screen");
        e.close();
        s.wire.split = true;
        int m3 = v.size();
        e.openTable();
        s.flush();
        long t = e.tick;
        step();
        long first = heard(m3, p -> p instanceof Packet.Slot).stream().filter(h -> h.tick() == t).count();
        long second = heard(m3, p -> p instanceof Packet.Slot).stream().filter(h -> h.tick() == t + 1).count();
        check(first == 27 && second == 27, "split: one tick's 54 slot packets over two ticks (" + first + "+" + second + ")");
        s.wire.split = false;
        e.close();
        step(3);
        s.wire.lagMs = 120;
        startGame("sp", "Transcendent", "c36", 24, null);
        until(() -> v.plainTitle().startsWith("Superpairs ("), 5);
        int m4 = v.size();
        long sent = e.tick;
        d.click(10, Click.LEFT);
        until(() -> !heard(m4, p -> sound(p, "block.wooden_pressure_plate.click_on")).isEmpty(), 8);
        List<ClientView.Heard> plate = heard(m4, p -> sound(p, "block.wooden_pressure_plate.click_on"));
        check(!plate.isEmpty() && plate.get(0).tick() - sent == 3, "lag 120 ms: the answer reaches the client 3 ticks after the click");
    }
}
