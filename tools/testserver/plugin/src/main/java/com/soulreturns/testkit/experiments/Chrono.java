package com.soulreturns.testkit.experiments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chronomatron (research §1.1). Metaphysical exactly as recorded; the other tiers' layout is ASSUMED (one row of
 * single cells, Skyblocker's guess / model.js GUESS_ROW; the mod falls back to vanilla for them).
 */
final class Chrono extends Game {
    /** A color: glass/terracotta color, name code, top and bottom slot (-1 = single cell), pling pitch in 63ths. */
    record Color(String name, String block, char code, int top, int bottom, int pitch) {
        Item glass() {
            return Item.of(block + "_stained_glass", "§r§" + code + name);
        }

        Item lit() {
            return Item.of(block + "_terracotta", "§r§" + code + name).withGlint(true);
        }

        boolean at(int slot) {
            return slot == top || slot == bottom;
        }
    }

    /** Metaphysical: two stacked cells per color; pitches n/63 as heard. Pink was never heard: 44/63 is ASSUMED (model.js). */
    static final List<Color> META = List.of(
        new Color("Red", "red", 'c', 11, 20, 35), new Color("Blue", "blue", '9', 12, 21, 39), new Color("Lime", "lime", 'a', 13, 22, 47),
        new Color("Yellow", "yellow", 'e', 14, 23, 52), new Color("Aqua", "light_blue", 'b', 15, 24, 63), new Color("Pink", "pink", 'd', 29, 38, 44),
        new Color("Green", "green", '2', 30, 39, 79), new Color("Cyan", "cyan", '3', 31, 40, 94), new Color("Orange", "orange", '6', 32, 41, 105),
        new Color("Purple", "purple", '5', 33, 42, 126));
    /** ASSUMED single-row layouts of the uncaptured tiers (model.js GUESS_ROW), colors in Metaphysical order. */
    static final Map<String, int[]> GUESS_ROW = Map.of(
        "High", new int[] {21, 22, 23}, "Grand", new int[] {20, 21, 22, 23, 24}, "Supreme", new int[] {19, 20, 21, 22, 23, 24, 25},
        "Transcendent", new int[] {18, 19, 20, 21, 22, 23, 24, 25});
    /** The displayed sequences of containers 6, 22 and 26 (fixtures exp-6/22/26, all notes the game showed). */
    static final Map<String, List<String>> RECORDED = Map.of(
        "c6", List.of("Yellow", "Yellow", "Purple", "Blue", "Orange", "Blue"),
        "c22", List.of("Aqua", "Cyan", "Lime", "Purple", "Yellow"),
        "c26", List.of("Yellow", "Lime", "Orange", "Green", "Red", "Lime", "Purple", "Yellow", "Red"));

    static final int FIRST_NOTE = 16, NOTE_GAP = 18, LIT = 8, TIMER_EXTRA = 2, OVER_WRONG = 3, OVER_CLOSE = 4;

    final List<Color> layout = new ArrayList<>();
    final List<Color> seq = new ArrayList<>();
    private final List<String> recorded;
    private final Map<Color, Integer> lit = new HashMap<>();
    int round;
    int answered;
    /** Longest completed chain. */
    int chain;
    long acceptFrom = Long.MAX_VALUE;
    int timer = -1;
    private long timerAt;
    private boolean maxSaid;

    Chrono(Engine e, MenuItems.Tier tier, String seed, long rngSeed, int round, boolean practice) {
        super(e, tier, seed, rngSeed, practice);
        if (meta()) layout.addAll(META);
        else {
            int[] row = GUESS_ROW.get(tier.name());
            for (int i = 0; i < row.length; i++) {
                Color c = META.get(i);
                layout.add(new Color(c.name, c.block, c.code, row[i], -1, c.pitch));
            }
        }
        recorded = meta() ? RECORDED.getOrDefault(seed, List.of()) : List.of();
        this.round = round;
        this.chain = round - 1;
    }

    @Override
    String title() {
        return "§rChronomatron (" + tier.name() + ")";
    }

    Color note(int i) {
        while (seq.size() <= i) {
            int n = seq.size();
            seq.add(n < recorded.size() ? byName(recorded.get(n)) : layout.get(rng.nextInt(layout.size())));
        }
        return seq.get(i);
    }

    private Color byName(String name) {
        for (Color c : layout) if (c.name.equals(name)) return c;
        throw new IllegalArgumentException(name);
    }

    Item[] initialSlots() {
        Item[] s = MenuItems.black(6);
        for (Color c : layout) {
            s[c.top] = c.glass();
            if (c.bottom >= 0) s[c.bottom] = c.glass();
        }
        s[4] = roundItem(round);
        s[49] = glowstone();
        return s;
    }

    void opened() {
        startRound(round, false);
    }

    static Item roundItem(int n) {
        return Item.of("bookshelf", Math.min(64, n), "§r§7Round: §r§e" + n);
    }

    /**
     * Round n: (announced: slot 4 + glowstone in this tick), notes lit at +16 + 18i for 8 ticks, each pling 1 tick
     * (2 of 3) or 2 ticks (1 of 3) before its cells; the last note's cells and the clock in one tick. Input is open
     * from the last note's pling tick.
     */
    void startRound(int n, boolean announce) {
        round = n;
        answered = 0;
        timer = -1;
        if (announce) {
            e.set(4, roundItem(n));
            e.set(49, glowstone());
        }
        if (chain >= Engine.maxBonusAt("chrono", tier) && !maxSaid) {
            maxSaid = true;
            e.chat(MenuItems.MAX_CLICKS_CHAT);
        }
        long base = e.tick;
        for (int i = 0; i < n; i++) {
            Color c = note(i);
            boolean last = i == n - 1;
            long litAt = base + FIRST_NOTE + (long) NOTE_GAP * i;
            long pling = litAt - (rng.nextInt(3) == 0 ? 2 : 1);
            if (last) acceptFrom = pling - (e.lateClick ? 1 : 0);
            e.atFor(this, pling, () -> pling(c));
            e.atFor(this, litAt, () -> {
                light(c, true);
                if (last) {
                    timer = n + TIMER_EXTRA;
                    timerAt = e.tick;
                    e.set(49, clock(timer));
                }
            });
            e.atFor(this, litAt + LIT, () -> light(c, false));
        }
    }

    private void pling(Color c) {
        e.sound("block.note_block.pling", "BLOCKS", 1.0f, c.pitch / 63f);
    }

    /** Lit while any note or echo of that color is (an echo can overlap the next round's start). */
    private void light(Color c, boolean on) {
        int n = lit.merge(c, on ? 1 : -1, Integer::sum);
        if (on && n != 1 || !on && n > 0) return;
        Item item = on ? c.lit() : c.glass();
        e.set(c.top, item);
        if (c.bottom >= 0) e.set(c.bottom, item);
    }

    private Color colorAt(int slot) {
        for (Color c : layout) if (c.at(slot)) return c;
        return null;
    }

    @Override
    String click(int slot, Click click) {
        if (ended) return "game over";
        Color c = colorAt(slot);
        if (c == null) return "not a color, ignored (ASSUMED)";
        if (!click.press()) return click + " is not a press, ignored (ASSUMED)";
        if (e.tick < acceptFrom || answered >= round) return "display, ignored";
        if (c != note(answered)) {
            ended = true;
            acceptFrom = Long.MAX_VALUE;
            timer = -1;
            e.later(OVER_WRONG, () -> over("§r§cYou picked the wrong color!"));
            return "wrong " + c.name + " (expected " + note(answered).name + ")";
        }
        answered++;
        pling(c);
        e.later(1, () -> light(c, true));
        e.later(1 + LIT, () -> light(c, false));
        if (answered < round) return "correct " + c.name + " " + answered + "/" + round;
        e.sound("entity.player.levelup", "PLAYERS", 0.7f, 111 / 63f);
        chain = round;
        acceptFrom = Long.MAX_VALUE;
        timer = -1;
        int next = round + 1;
        e.later(1, () -> startRound(next, true));
        return "correct " + c.name + ", round " + round + " done";
    }

    @Override
    void tick() {
        if (ended || timer < 0 || e.tick == timerAt || (e.tick - timerAt) % 20 != 0) return;
        timer--;
        if (timer > 0) {
            e.set(49, clock(timer));
            return;
        }
        // ASSUMED: timer expiry was never captured
        ended = true;
        over("§r§cTime ran out!");
    }

    @Override
    void clientClosed() {
        ended = true;
        e.afterClose(OVER_CLOSE, () -> over("§r§cYou closed the game!"));
    }

    private void over(String reason) {
        e.openOver("chrono", tier, reason, chain, chain, practice);
    }

    @Override
    String status() {
        StringBuilder sb = new StringBuilder("round " + round + ", ");
        if (e.tick < acceptFrom) sb.append("display");
        else sb.append("input ").append(answered).append('/').append(round);
        if (timer >= 0) sb.append(", timer ").append(timer).append('s');
        sb.append(", chain ").append(chain).append(", seed ").append(seed).append(", sequence ");
        for (int i = 0; i < round; i++) sb.append(i == 0 ? "" : " ").append(note(i).name);
        return sb.toString();
    }
}
