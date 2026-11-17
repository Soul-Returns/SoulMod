package com.soulreturns.testkit.experiments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Hypixel's side of the Experimentation Table as a tick simulator, without Bukkit (research §1, §6; the mock's
 * {@code Hypixel} class in mocks/experiments/model.js is the same machine). Feed it clicks and closes, call
 * {@link #step()} once per server tick and send {@link #drain()} to the client in order.
 * <p>
 * Container slots work like a vanilla menu: games change {@link Screen#slots}, and at the end of every tick the
 * slots that differ from what the client was sent go out as single set_slot packets in slot order (that is why
 * a Chronomatron note's top cell precedes its bottom cell, and the last note's cells precede the clock). An open
 * sends every slot in its own tick and one full set_content 4-6 ticks later.
 */
public final class Engine {
    public enum Kind { TABLE, STAKES, CHRONO, ULTRA, SP, OVER, REWARDS, RNG }

    /** One opened container. A re-sent game screen ({@link #reopen}) is a new Screen with the same token. */
    public final class Screen {
        public final Kind kind;
        public final String title;
        public final int rows;
        public final int cid;
        final Item[] slots;
        final Item[] shown;
        final Object token;
        /** Stakes: the game key; RNG: the page number; Over/Rewards: what a claim pays. */
        String game;
        int page;
        boolean practice;
        Runnable claim;
        Runnable decline;

        Screen(Kind kind, String title, int rows, int cid, Item[] slots, Object token) {
            this.kind = kind;
            this.title = title;
            this.rows = rows;
            this.cid = cid;
            this.slots = slots;
            this.shown = new Item[slots.length];
            this.token = token;
        }

        public Item slot(int i) {
            return i >= 0 && i < slots.length ? slots[i] : null;
        }

        public int size() {
            return slots.length;
        }
    }

    public final Daily daily = new Daily();
    /** {@code lateclick on}: input opens one tick before the last note's pling / the hide, so a fast click races the display. */
    public boolean lateClick;
    public long tick;
    final Random rng;
    Screen screen;
    Game game;
    private int lastCid;
    private final List<Packet> out = new ArrayList<>();
    private final TreeMap<Long, List<Runnable>> queue = new TreeMap<>();
    private final ArrayDeque<Runnable> inbox = new ArrayDeque<>();
    private final Set<Long> resyncAt = new HashSet<>();
    private final List<String> clickLog = new ArrayList<>();

    public Engine(long seed) {
        rng = new Random(seed);
        lastCid = rng.nextInt(100);
    }

    // ---- driving ----

    /** One server tick: arrived clicks and closes, due steps, game timers, then the slot diff. */
    public void step() {
        tick++;
        while (!inbox.isEmpty()) inbox.poll().run();
        while (!queue.isEmpty() && queue.firstKey() <= tick) {
            for (Runnable r : queue.pollFirstEntry().getValue()) r.run();
        }
        if (game != null && screen != null && screen.token == game) game.tick();
        flush();
    }

    /** Everything sent since the last call, in wire order. */
    public List<Packet> drain() {
        List<Packet> copy = List.copyOf(out);
        out.clear();
        return copy;
    }

    /** A container_click arriving at the server; judged in the next {@link #step()}. */
    public void click(int cid, int slot, Click click) {
        inbox.add(() -> handleClick(cid, slot, click));
    }

    /** A container_close from the client (Esc); handled in the next {@link #step()}. */
    public void clientClose(int cid) {
        inbox.add(() -> {
            if (screen == null || screen.cid != cid) return;
            Screen s = screen;
            screen = null;
            logClick("close by client");
            if (game != null && s.token == game && !game.ended) game.clientClosed();
        });
    }

    public Screen screen() {
        return screen;
    }

    public List<String> clicks() {
        return clickLog;
    }

    // ---- primitives for menus and games ----

    void at(long when, Runnable r) {
        queue.computeIfAbsent(Math.max(when, tick + 1), k -> new ArrayList<>()).add(r);
    }

    /** Runs [r] n ticks from now while the same game/menu is still open (a re-sent game screen counts as the same). */
    void later(int n, Runnable r) {
        Object token = screen == null ? null : screen.token;
        at(tick + Math.max(1, n), () -> {
            if (screen != null && screen.token == token) r.run();
        });
    }

    /** Runs [r] at tick [when] while [token]'s screen is open. */
    void atFor(Object token, long when, Runnable r) {
        at(when, () -> {
            if (screen != null && screen.token == token) r.run();
        });
    }

    /** Runs [r] n ticks from now if nothing was opened since the client closed the menu. */
    void afterClose(int n, Runnable r) {
        at(tick + n, () -> {
            if (screen == null) r.run();
        });
    }

    void set(int slot, Item item) {
        if (screen != null && slot >= 0 && slot < screen.slots.length) screen.slots[slot] = item;
    }

    void sound(String id, String source, float volume, float pitch) {
        out.add(new Packet.Sound(id, source, volume, pitch));
    }

    void chat(String text) {
        out.add(new Packet.Chat(text));
    }

    private void flush() {
        Screen s = screen;
        if (s == null) return;
        for (int i = 0; i < s.slots.length; i++) {
            if (!Objects.equals(s.slots[i], s.shown[i])) {
                s.shown[i] = s.slots[i];
                out.add(new Packet.Slot(s.cid, i, s.slots[i]));
            }
        }
    }

    Screen open(Kind kind, String title, int rows, Item[] slots, Object token) {
        sound("block.lever.click", "BLOCKS", 0.5f, 1.0f);
        lastCid = lastCid % 100 + 1;
        Screen s = new Screen(kind, title, rows, lastCid, slots, token == null ? new Object() : token);
        screen = s;
        if (game != null && s.token != game) game = null;
        out.add(new Packet.Open(s.cid, rows, title));
        for (int i = 0; i < slots.length; i++) {
            s.shown[i] = slots[i];
            out.add(new Packet.Slot(s.cid, i, slots[i]));
        }
        out.add(new Packet.PlayerSlots(s.cid, slots.length));
        at(tick + 4 + rng.nextInt(3), () -> {
            if (screen == s) content();
        });
        return s;
    }

    /** One full set_content of the open screen, now. */
    public void content() {
        if (screen == null) return;
        Item[] copy = screen.slots.clone();
        System.arraycopy(copy, 0, screen.shown, 0, copy.length);
        out.add(new Packet.Content(screen.cid, Arrays.asList(copy)));
    }

    /** Server close of whatever is open. */
    public void close() {
        if (screen == null) return;
        out.add(new Packet.Close(screen.cid));
        screen = null;
        game = null;
    }

    /** Another menu replaced the screen on the client (a real server inventory): forget it without a close packet. */
    public void forget() {
        screen = null;
        game = null;
    }

    /** {@code reopen}: the open screen again (new container id, same title, every slot, contents 4-6 ticks later); a game goes on. */
    public boolean reopen() {
        Screen old = screen;
        if (old == null) return false;
        flush();
        Screen s = open(old.kind, old.title, old.rows, old.slots.clone(), old.token);
        s.game = old.game;
        s.page = old.page;
        s.practice = old.practice;
        s.claim = old.claim;
        s.decline = old.decline;
        return true;
    }

    private void scheduleResync() {
        Screen s = screen;
        long when = tick + 7 + rng.nextInt(2);
        if (!resyncAt.add(when)) return;
        at(when, () -> {
            resyncAt.remove(when);
            if (screen == s) content();
        });
    }

    private void logClick(String line) {
        clickLog.add("t" + tick + " " + line);
        if (clickLog.size() > 300) clickLog.remove(0);
    }

    // ---- clicks ----

    private void handleClick(int cid, int slot, Click click) {
        if (screen == null || screen.cid != cid) {
            logClick("cid " + cid + " slot " + slot + " " + click + ": not the open container, ignored");
            return;
        }
        Screen s = screen;
        String what;
        if (slot < 0 || slot >= s.slots.length) what = "outside the menu, ignored";
        else what = switch (s.kind) {
            case TABLE -> tableClick(slot, click);
            case STAKES -> stakesClick(s, slot, click);
            case OVER -> button(slot, click, OVER_SLOTS) ? overClick(s, slot) : "ignored";
            case REWARDS -> button(slot, click, REWARDS_SLOTS) && slot == MenuItems.REWARDS_HEAD ? claim(s) : "ignored";
            case RNG -> rngClick(s, slot, click);
            case CHRONO, ULTRA, SP -> game != null && s.token == game ? game.click(slot, click) : "no game";
        };
        logClick(s.title.replaceAll("§.", "") + " slot " + slot + " " + click + ": " + what);
        // Every click but CLONE that leaves the screen open is answered with a full resync (games: captured; menus: ASSUMED)
        if (click.resyncs() && screen == s) scheduleResync();
    }

    private static final int[] OVER_SLOTS = {MenuItems.OVER_HEAD, MenuItems.OVER_DECLINE};
    private static final int[] REWARDS_SLOTS = {MenuItems.REWARDS_HEAD};

    /** Menu buttons take a left click (captured) or a middle click (ASSUMED, unproven). */
    private static boolean button(int slot, Click click, int[] slots) {
        if (click != Click.LEFT && click != Click.MIDDLE) return false;
        for (int s : slots) if (s == slot) return true;
        return false;
    }

    private static boolean pressed(Click click) {
        return click == Click.LEFT || click == Click.MIDDLE;
    }

    private String tableClick(int slot, Click click) {
        Daily d = daily;
        switch (slot) {
            case MenuItems.TABLE_SP -> {
                if (!pressed(click)) return "ignored";
                if (d.charges > 0 && d.chrono.done && d.ultra.done) {
                    later(1, () -> openStakes("sp", false));
                    return "Superpairs stakes";
                }
                return "not playable (" + (d.charges == 0 ? "on cooldown" : "add-ons first") + "), nothing happens (ASSUMED)";
            }
            case MenuItems.TABLE_CHRONO, MenuItems.TABLE_ULTRA -> {
                String g = slot == MenuItems.TABLE_CHRONO ? "chrono" : "ultra";
                boolean cooldown = MenuItems.onCooldown(d, d.addon(g));
                if (pressed(click) && !cooldown) {
                    later(1, () -> openStakes(g, false));
                    return MenuItems.gameName(g) + " stakes";
                }
                if (click == Click.RIGHT && cooldown && d.practice) {
                    later(1, () -> openStakes(g, true));
                    return MenuItems.gameName(g) + " stakes, practice (ASSUMED)";
                }
                return "ignored";
            }
            case MenuItems.TABLE_RENEW -> {
                if (!pressed(click) || screen.slots[slot] == null || !screen.slots[slot].path().equals("blaze_powder")) return "ignored";
                d.renewed++;
                d.charges++;
                for (Daily.Addon a : List.of(d.chrono, d.ultra)) {
                    a.done = false;
                    a.bonus = 0;
                    a.declined = false;
                }
                chat(MenuItems.renewChat(d.renewed));
                later(1, this::openTable);
                return "renewed " + d.renewed + "/3";
            }
            case MenuItems.TABLE_RNG -> {
                if (!pressed(click)) return "ignored";
                later(1, () -> openRng(1));
                return "RNG page 1";
            }
            case MenuItems.TABLE_CLOSE -> {
                if (!pressed(click)) return "ignored";
                close();
                return "closed";
            }
            default -> {
                return "ignored";
            }
        }
    }

    private String stakesClick(Screen s, int slot, Click click) {
        if (!pressed(click)) return "ignored";
        int back = MenuItems.stakesBack(s.game);
        if (slot == back) {
            later(1, this::openTable);
            return "back";
        }
        if (slot == back + 1) {
            close();
            return "closed";
        }
        int[] slots = MenuItems.stakesSlots(s.game);
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != slot) continue;
            MenuItems.Tier t = MenuItems.tiers(s.game).get(i);
            String status = daily.status(s.game, t.name());
            if (!status.equals("play")) return t.name() + ": " + status + ", nothing happens (ASSUMED)";
            String seed = String.valueOf(1 + rng.nextInt(999_999));
            boolean practice = s.practice;
            later(1, () -> start(s.game, t.name(), seed, 0, practice, null));
            return "start " + t.name() + " seed " + seed;
        }
        return "ignored";
    }

    private String overClick(Screen s, int slot) {
        if (slot == MenuItems.OVER_HEAD) return claim(s);
        close();
        if (s.decline != null) s.decline.run();
        return "declined";
    }

    private String claim(Screen s) {
        close();
        if (s.claim != null) s.claim.run();
        return "claimed";
    }

    private String rngClick(Screen s, int slot, Click click) {
        if (!pressed(click)) return "ignored";
        int pages = Captured.get().rng.size();
        if (slot == 53 && s.page < pages) {
            int next = s.page + 1;
            later(1, () -> openRng(next));
            return "page " + next;
        }
        if (slot == 45 && s.page > 1) {
            int prev = s.page - 1;
            later(1, () -> openRng(prev));
            return "page " + prev;
        }
        if (slot == 48) {
            later(1, this::openTable);
            return "Go Back: Experimentation Table (ASSUMED; the RNG Meter root was not captured)";
        }
        if (slot == 49) {
            close();
            return "closed";
        }
        return "ignored (select/search/reset not captured)";
    }

    // ---- openers ----

    public void openTable() {
        open(Kind.TABLE, "§rExperimentation Table", 6, MenuItems.table(daily), null);
    }

    public void openStakes(String game, boolean practice) {
        Screen s = open(Kind.STAKES, "§r" + MenuItems.gameName(game) + " ➜ Stakes", MenuItems.stakesRows(game), MenuItems.stakes(daily, game), null);
        s.game = game;
        s.practice = practice;
    }

    public void openRng(int page) {
        List<Captured.RngPage> pages = Captured.get().rng;
        if (page < 1 || page > pages.size()) throw new IllegalArgumentException("RNG pages are 1-" + pages.size());
        Screen s = open(Kind.RNG, pages.get(page - 1).title(), 6, Captured.get().rngPage(page, daily.rngSelected), null);
        s.page = page;
    }

    /**
     * Starts a game. seed: a number, or a recorded seed (chrono c6|c22|c26, ultra c10|c30, sp c17|c36|books|instant|plus3);
     * extra: round (chrono/ultra, 0 = 1) or clicks (sp, 0 = from the stakes lore); rare: sp only.
     */
    public void start(String gameKey, String tierName, String seed, int extra, boolean practice, String rare) {
        MenuItems.Tier t = MenuItems.tier(gameKey, tierName);
        long rngSeed = seed.matches("\\d+") ? Long.parseLong(seed) : seed.replaceAll("\\D", "").isEmpty() ? seed.hashCode() : Long.parseLong(seed.replaceAll("\\D", ""));
        Game g = switch (gameKey) {
            case "chrono" -> new Chrono(this, t, seed, rngSeed, Math.max(1, extra), practice);
            case "ultra" -> new Ultra(this, t, seed, rngSeed, Math.max(1, extra), practice);
            default -> new Superpairs(this, t, seed, rngSeed, extra, rare == null ? "none" : rare, practice);
        };
        game = g;
        open(gameKey.equals("chrono") ? Kind.CHRONO : gameKey.equals("ultra") ? Kind.ULTRA : Kind.SP, g.title(), 6, initial(g), g);
        started(g);
    }

    private static Item[] initial(Game g) {
        return switch (g) {
            case Chrono c -> c.initialSlots();
            case Ultra u -> u.initialSlots();
            case Superpairs sp -> sp.initialSlots();
            default -> throw new IllegalStateException();
        };
    }

    private static void started(Game g) {
        switch (g) {
            case Chrono c -> c.opened();
            case Ultra u -> u.opened();
            case Superpairs sp -> sp.opened();
            default -> throw new IllegalStateException();
        }
    }

    /** {@code Experiment Over} for Chronomatron/Ultrasequencer; claim pays into {@link #daily} (practice: nothing, ASSUMED). */
    public void openOver(String gameKey, MenuItems.Tier tier, String reason, int chain, int rewardRounds, boolean practice) {
        int xp = rewardRounds * tier.xp();
        int clicks = bonusClicks(gameKey, tier, rewardRounds);
        Screen s = open(Kind.OVER, "§rExperiment Over", 3, MenuItems.over(gameKey, tier, reason, chain, xp, clicks), null);
        s.game = gameKey;
        s.claim = () -> {
            for (String line : MenuItems.overChat(gameKey, xp, clicks)) chat(line);
            if (practice) return;
            Daily.Addon a = daily.addon(gameKey);
            a.done = true;
            a.bonus = clicks;
        };
        s.decline = () -> {
            if (practice) return;
            Daily.Addon a = daily.addon(gameKey);
            a.done = false;
            a.bonus = 0;
            a.declined = true;
        };
    }

    /** Superpairs clicks by chain/series: 2 → 1, 6 (Chronomatron) / 4 (Ultrasequencer) → 2, Metaphysical 9 / 6 → 3. */
    static int bonusClicks(String gameKey, MenuItems.Tier tier, int n) {
        boolean meta = tier.name().equals("Metaphysical");
        int[] steps = gameKey.equals("chrono") ? new int[] {2, 6, 9} : new int[] {2, 4, 6};
        int clicks = 0;
        for (int i = 0; i < (meta ? 3 : 2); i++) if (n >= steps[i]) clicks = i + 1;
        return clicks;
    }

    static int maxBonusAt(String gameKey, MenuItems.Tier tier) {
        boolean meta = tier.name().equals("Metaphysical");
        return gameKey.equals("chrono") ? (meta ? 9 : 6) : (meta ? 6 : 4);
    }

    public void openRewards(MenuItems.Tier tier, int used, int pairs, int pairsXp, List<String> items, List<Integer> xpPairs, boolean practice) {
        Screen s = open(Kind.REWARDS, "§rSuperpairs Rewards", 3, MenuItems.rewards(tier, used, pairs, pairsXp, items), null);
        s.claim = () -> {
            for (String line : MenuItems.rewardsChat(tier, items, xpPairs)) chat(line);
            if (practice) return;
            daily.charges = Math.max(0, daily.charges - 1);
            for (Daily.Addon a : List.of(daily.chrono, daily.ultra)) {
                a.done = false;
                a.bonus = 0;
                a.declined = false;
            }
        };
    }

    // ---- status ----

    public String describe() {
        if (screen == null) return "nothing open (tick " + tick + ")";
        StringBuilder sb = new StringBuilder(screen.title.replaceAll("§.", "") + " [cid " + screen.cid + ", tick " + tick + "]");
        if (game != null && screen.token == game) sb.append(": ").append(game.status());
        return sb.toString();
    }

    public String knobs() {
        return "charges " + daily.charges + "/3, renewed " + daily.renewed + "/3, chrono " + addon(daily.chrono) + ", ultra " + addon(daily.ultra)
            + ", practice " + (daily.practice ? "on" : "off") + ", lateclick " + (lateClick ? "on" : "off");
    }

    private static String addon(Daily.Addon a) {
        return (a.done ? "done +" + a.bonus : "pending") + (a.declined ? " declined" : "");
    }

    /** Stakes status knob: {@code <game>:<Tier>} → play | low | xp. */
    public void stakesStatus(String game, String tier, String status) {
        if (!List.of("play", "low", "xp").contains(status)) throw new IllegalArgumentException("status play|low|xp");
        daily.stakesStatus.put(game + ":" + MenuItems.tier(game, tier).name(), status);
    }
}
