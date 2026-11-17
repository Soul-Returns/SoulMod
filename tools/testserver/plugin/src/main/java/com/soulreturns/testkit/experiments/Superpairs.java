package com.soulreturns.testkit.experiments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Superpairs (research §1.3). The 7x4 tiers exactly as recorded; Beginner 7x2 and High/Grand 5x4 grids are ASSUMED
 * (model.js) and always get random boards. Every answer's sound goes out at the click, the slots one tick later.
 */
final class Superpairs extends Game {
    static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    static final int[] GRID_7X2 = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    static final int[] GRID_5X4 = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24, 29, 30, 31, 32, 33, 38, 39, 40, 41, 42};
    static final int TIMER = 90, HIDE = 30, REWARDS_AFTER_CLOSE = 4;
    /** Final board clicks were ignored 3/21/24 ticks after it showed and taken 36 ticks after (containers 17, 36): 30 is ASSUMED. */
    static final int PROCEED_AFTER = 30;
    static final List<String> SEEDS = List.of("c17", "c36", "books", "instant", "plus3");
    static final List<String> RARE = List.of("none", "meter", "header", "both", "selected");
    private static final Pattern XP = Pattern.compile("^(\\d+)k Enchanting Exp$");

    enum State {
        IDLE("§r§eClick any button!"), FIRST("§r§bClick a second button!"), MISMATCH("§r§8?"), INSTANT("§r§dNext button is instantly rewarded!"), END("§r§8?");

        final String glass;

        State(String glass) {
            this.glass = glass;
        }
    }

    final int[] grid;
    final Map<Integer, Item> board = new LinkedHashMap<>();
    final int startClicks;
    int remaining;
    State state = State.IDLE;
    Integer open;
    int[] mismatch;
    private long hideAt;
    final Set<Integer> faceUp = new HashSet<>();
    /** Matched pairs and revealed powerups: glinted on the final board. */
    final Set<Integer> used = new HashSet<>();
    final List<Item> rewards = new ArrayList<>();
    int clicksUsed;
    int pairs;
    int timer = TIMER;
    private long timerAt;
    boolean finalBoard;
    private long finalAt;
    private final String rare;

    Superpairs(Engine e, MenuItems.Tier tier, String seed, long rngSeed, int clicks, String rare, boolean practice) {
        super(e, tier, seed, rngSeed, practice);
        this.rare = rare;
        grid = tier.board().equals("7x2") ? GRID_7X2 : tier.board().equals("5x4") ? GRID_5X4 : GRID;
        List<Item> cards = cards(seed, grid.length == GRID.length);
        for (int i = 0; i < grid.length; i++) board.put(grid[i], cards.get(i));
        int addons = (e.daily.chrono.bonus + e.daily.ultra.bonus) * (meta() ? 2 : 1);
        startClicks = clicks > 0 ? clicks : tier.xp() + addons + e.daily.serum;
        remaining = startClicks;
    }

    @Override
    String title() {
        return "§rSuperpairs (" + tier.name() + ")";
    }

    // ---- boards ----

    private List<Item> cards(String seed, boolean full) {
        Captured cap = Captured.get();
        List<Item> cards;
        if (full && SEEDS.contains(seed)) {
            cards = new ArrayList<>(seed.equals("c17") ? cap.board17 : cap.board36);
            switch (seed) {
                // container 36 with its 30k pair (slots 10, 33) replaced by container 17's Thunderbolt VI: two different "Enchanted Book"s
                case "books" -> {
                    cards.set(0, cap.board17.get(8));
                    cards.set(19, cap.board17.get(8));
                }
                case "instant" -> Collections.swap(cards, 0, 15);
                case "plus3" -> Collections.swap(cards, 0, 5);
                default -> {
                }
            }
        } else cards = randomBoard(grid.length);
        if (rare.equals("meter") || rare.equals("both")) replacePair(cards, meterRare());
        if (rare.equals("header") || rare.equals("both")) replacePair(cards, headerRare());
        if (rare.equals("selected")) {
            Item card = selectedCard(e.daily.rngSelected);
            if (cards.stream().noneMatch(c -> c.cardKey().equals(card.cardKey()))) replacePair(cards, card);
        }
        return cards;
    }

    /**
     * The selected RNG drop as a card: a recorded card whose enchant line names it (Growth VI: container 36's card,
     * leather_chestplate #667f33 "Enchanted Book", "Rare Book!", "", "Growth VI", …); otherwise ASSUMED: a book card made
     * from the meter entry (its item, count and dye; the entry's name as the enchant line).
     */
    static Item selectedCard(String selected) {
        Captured cap = Captured.get();
        for (Item c : concat(cap.board17, cap.board36)) {
            if (c.lore().size() > 2 && Item.strip(c.lore().get(2)).equals(selected)) return c;
        }
        Item entry = cap.rngEntry(selected);
        return new Item(entry.id(), entry.count(), "§r§9Enchanted Book", List.of("§r§9Rare Book!", "", entry.name()), null, entry.dye(), null, null, null, null);
    }

    /** 2 powerups + pairs drawn from every distinct card of the two recorded boards. */
    private List<Item> randomBoard(int size) {
        Captured cap = Captured.get();
        Map<String, Item> pool = new LinkedHashMap<>();
        Item instant = null, plus3 = null;
        for (Item c : concat(cap.board17, cap.board36)) {
            if (c.plainName().equals("Instant Find")) instant = c;
            else if (c.plainName().equals("Gained +3 Clicks")) plus3 = c;
            else pool.putIfAbsent(c.cardKey(), c);
        }
        List<Item> distinct = new ArrayList<>(pool.values());
        Collections.shuffle(distinct, rng);
        List<Item> cards = new ArrayList<>(List.of(instant, plus3));
        for (Item c : distinct.subList(0, size / 2 - 1)) {
            cards.add(c);
            cards.add(c);
        }
        Collections.shuffle(cards, rng);
        return cards;
    }

    private static List<Item> concat(List<Item> a, List<Item> b) {
        List<Item> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** Both cards of the first XP pair in grid order become [card]. */
    private static void replacePair(List<Item> cards, Item card) {
        for (int i = 0; i < cards.size(); i++) {
            if (!XP.matcher(cards.get(i).plainName()).matches()) continue;
            String key = cards.get(i).cardKey();
            for (int j = i + 1; j < cards.size(); j++) {
                if (cards.get(j).cardKey().equals(key)) {
                    cards.set(i, card);
                    cards.set(j, card);
                    return;
                }
            }
        }
    }

    /**
     * ASSUMED (no rare card was ever captured): a 0.0263 % RNG meter entry (Thunderbolt VII) as a book card made like
     * the recorded Thunderbolt VI card (item, count and dye of the meter entry, its display name as the enchant line), no
     * ULTRA-RARE header.
     */
    static Item meterRare() {
        Item entry = Captured.get().rngEntry("Thunderbolt VII");
        return new Item(entry.id(), entry.count(), "§r§9Enchanted Book", List.of("§r§9Rare Book!", "", entry.name(), "§r§7Every §r§c3 §r§7hits on a monster, strike",
            "§r§elightning§r§7, dealing §r§a30% §r§7of the hit's", "§r§7damage to up to 10 monsters within 2", "§r§7blocks."), null, entry.dye(), null, null, null, null);
    }

    /** ASSUMED: a book that is not on the RNG meter, with SkyHanni's expected header (enchant at lore index 2). */
    static Item headerRare() {
        return Item.of("enchanted_book", 7, "§r§9Enchanted Book", "§r§d§kXX§5 ULTRA-RARE BOOK! §d§kXX", "", "§r§5Smite VII",
            "§r§7Increases damage dealt to §r§aSkeletons§r§7,", "§r§aZombies§r§7, §r§aWithers §r§7and §r§aZombie Pigmen", "§r§7by §r§a80%§r§7.");
    }

    // ---- open ----

    Item[] initialSlots() {
        Item[] s = MenuItems.black(6);
        for (int slot : grid) s[slot] = glass(State.IDLE);
        s[4] = remainingItem(remaining);
        s[49] = clock(timer);
        return s;
    }

    void opened() {
        timerAt = e.tick;
    }

    static Item glass(State state) {
        return Item.of("cyan_stained_glass", state.glass);
    }

    static Item remainingItem(int n) {
        return n > 0 ? Item.of("bookshelf", Math.min(64, n), "§r§7Remaining Clicks: §r§e" + n) : Item.of("cauldron", "§r§7Remaining Clicks: §r§e0");
    }

    private boolean hidden(int slot) {
        return !faceUp.contains(slot) && (open == null || open != slot) && (mismatch == null || mismatch[0] != slot && mismatch[1] != slot);
    }

    private boolean power(Item card) {
        return card.plainLore().stream().anyMatch(l -> l.contains("owerup"));
    }

    /** Every grid slot and slot 4 from the model; the tick's diff sends only what changed (the glass renames as single slots). */
    private void render() {
        for (int slot : grid) {
            Item card = board.get(slot);
            if (finalBoard) {
                List<String> lore = new ArrayList<>(card.lore());
                lore.add("");
                lore.add("§r§eClick any to proceed!");
                e.set(slot, card.withLore(lore).withGlint(used.contains(slot) ? Boolean.TRUE : card.glint()));
            } else e.set(slot, hidden(slot) ? glass(state) : card);
        }
        e.set(4, remainingItem(remaining));
    }

    private void renderLater() {
        e.later(1, this::render);
    }

    private void levelup() {
        e.sound("entity.player.levelup", "PLAYERS", 1.0f, 111 / 63f);
    }

    // ---- clicks ----

    @Override
    String click(int slot, Click click) {
        if (ended) return "game over";
        if (board.get(slot) == null) return "not a card, ignored (ASSUMED)";
        if (!click.press()) return click + " is not a press, ignored (ASSUMED)";
        if (finalBoard) {
            if (e.tick - finalAt < PROCEED_AFTER) return "final board shown " + (e.tick - finalAt) + " ticks, ignored";
            ended = true;
            e.sound("block.wood.break", "BLOCKS", 0.2f, 1.0f);
            openRewards();
            return "proceed: rewards";
        }
        if (state == State.END) {
            finalBoard = true;
            finalAt = e.tick + 1;
            mismatch = null;
            open = null;
            renderLater();
            return "final board";
        }
        if (state == State.MISMATCH) {
            mismatch = null;
            state = State.IDLE;
            // Container 17 (4185→4212): a click arriving as the auto-hide is decided (its frame goes out a tick later) is a first click
            if (e.tick < hideAt - 1) {
                renderLater();
                return "dismissed the mismatch (no click used)";
            }
        }
        if (!hidden(slot)) return "face-up card, ignored (ASSUMED)";
        Item card = board.get(slot);
        String name = card.plainName();
        return switch (state) {
            case INSTANT -> instantReveal(slot, card);
            case FIRST -> second(slot, card);
            default -> first(slot, card, name);
        };
    }

    private String first(int slot, Item card, String name) {
        if (name.equals("Instant Find")) {
            levelup();
            e.sound("block.note_block.hat", "BLOCKS", 1.0f, 107 / 63f);
            e.later(3, () -> e.sound("block.note_block.hat", "BLOCKS", 1.0f, 113 / 63f));
            e.later(6, () -> e.sound("block.note_block.hat", "BLOCKS", 1.0f, 119 / 63f));
            clicksUsed++;
            faceUp.add(slot);
            used.add(slot);
            state = State.INSTANT;
            renderLater();
            return "Instant Find (+1 click, armed)";
        }
        if (power(card)) {
            // ASSUMED: +3 Clicks as a first card (only seen as the second card)
            levelup();
            clicksUsed++;
            faceUp.add(slot);
            used.add(slot);
            remaining += 2;
            renderLater();
            return name + " as first card (ASSUMED +3 -1)";
        }
        clicksUsed++;
        e.sound("block.wooden_pressure_plate.click_on", "BLOCKS", 0.8f, pitch63(0.15 * remaining));
        int before = remaining;
        remaining = Math.max(0, remaining - 1);
        open = slot;
        state = before <= 1 ? State.END : State.FIRST;
        renderLater();
        return "first " + name + (state == State.END ? ", out of clicks" : "");
    }

    private String second(int slot, Item card) {
        Item first = board.get(open);
        clicksUsed++;
        if (power(card)) {
            // +3 Clicks captured (8194): +3 -1, the first card stays open, glass stays "second". Instant Find here: ASSUMED +1 -1 alike.
            levelup();
            faceUp.add(slot);
            used.add(slot);
            remaining += card.plainName().equals("Instant Find") ? 0 : 2;
            renderLater();
            return card.plainName() + " as second card, first stays open";
        }
        remaining = Math.max(0, remaining - 1);
        if (card.cardKey().equals(first.cardKey())) {
            levelup();
            faceUp.add(slot);
            faceUp.add(open);
            used.add(slot);
            used.add(open);
            pairs++;
            rewards.add(card);
            open = null;
            state = State.IDLE;
            renderLater();
            return "match " + card.plainName();
        }
        e.sound("entity.player.burp", "PLAYERS", 0.8f, 94 / 63f);
        int[] pair = {open, slot};
        mismatch = pair;
        open = null;
        state = remaining == 0 ? State.END : State.MISMATCH;
        renderLater();
        hideAt = e.tick + 1 + HIDE;
        if (state == State.MISMATCH) e.later(1 + HIDE, () -> {
            if (mismatch != pair) return;
            mismatch = null;
            state = State.IDLE;
            render();
        });
        return "mismatch " + first.plainName() + " / " + card.plainName();
    }

    private String instantReveal(int slot, Item card) {
        clicksUsed++;
        levelup();
        remaining = Math.max(0, remaining - 1);
        faceUp.add(slot);
        Integer partner = null;
        for (int s : grid) if (s != slot && hidden(s) && board.get(s).cardKey().equals(card.cardKey())) partner = s;
        if (partner != null) {
            faceUp.add(partner);
            used.add(partner);
            used.add(slot);
            pairs++;
            rewards.add(card);
        } else if (power(card)) used.add(slot);
        state = State.IDLE;
        renderLater();
        return "instant: " + card.plainName() + (partner != null ? " + partner " + partner : "");
    }

    @Override
    void tick() {
        if (ended || timer <= 0 || e.tick == timerAt || (e.tick - timerAt) % 20 != 0) return;
        timer--;
        e.set(49, clock(timer));
        if (timer == 0 && !finalBoard) {
            // ASSUMED: the timer running out was never captured; the board turns face-up as at 0 clicks
            finalBoard = true;
            finalAt = e.tick;
            mismatch = null;
            open = null;
            render();
        }
    }

    @Override
    void clientClosed() {
        ended = true;
        e.afterClose(REWARDS_AFTER_CLOSE, this::openRewards);
    }

    private void openRewards() {
        int xp = 0;
        List<String> items = new ArrayList<>();
        List<Integer> xpPairs = new ArrayList<>();
        for (Item card : rewards) {
            Matcher m = XP.matcher(card.plainName());
            if (m.matches()) {
                int k = Integer.parseInt(m.group(1)) * 1000;
                xp += k;
                xpPairs.add(k);
            } else items.add(card.plainName().equals("Enchanted Book") && card.lore().size() > 2 ? card.lore().get(2) : card.name());
        }
        e.openRewards(tier, clicksUsed, pairs, xp, items, xpPairs, practice);
    }

    @Override
    String status() {
        return state.name().toLowerCase() + (finalBoard ? " (final board)" : "") + ", remaining " + remaining + ", used " + clicksUsed + ", pairs " + pairs
            + ", timer " + timer + "s, seed " + seed + (rare.equals("none") ? "" : ", rare " + rare) + (open != null ? ", open " + open : "");
    }
}
