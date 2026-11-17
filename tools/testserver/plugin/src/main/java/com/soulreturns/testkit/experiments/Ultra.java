package com.soulreturns.testkit.experiments;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ultrasequencer (research §1.2). Metaphysical (board 9-44) exactly as recorded; Supreme 7x3 and Transcendent 7x4
 * use an ASSUMED 7-wide block (rows 1-3/1-4, columns 1-7).
 */
final class Ultra extends Game {
    /** Number n's item and name color: 1-8 recorded; 9+ ASSUMED (never seen). */
    static final String[] DYES = {"bone_meal", "light_blue_dye", "lapis_lazuli", "purple_dye", "magenta_dye", "pink_dye", "red_dye", "orange_dye",
        "yellow_dye", "lime_dye", "green_dye", "cyan_dye", "brown_dye", "gray_dye", "light_gray_dye", "black_dye"};
    static final char[] CODES = {'f', '9', '1', '5', 'd', 'd', '4', '6', 'e', 'a', '2', '3', '6', '8', '7', '0'};
    /** Board pane color per round: R1-R8 recorded, the newest number's dye color; 9+ ASSUMED alike. */
    static final String[] PANES = {"white", "light_blue", "blue", "purple", "magenta", "pink", "red", "orange",
        "yellow", "lime", "green", "cyan", "brown", "gray", "light_gray", "black"};
    /** Slot of number 1..N per round (fixtures exp-10 / exp-30, after every burst incl. unchanged slots). */
    static final Map<String, int[][]> RECORDED = Map.of(
        "c10", new int[][] {{31}, {44, 35}, {29, 12, 17}, {36, 29, 24, 39}, {10, 23, 31, 21, 32}, {16, 32, 31, 43, 41, 20}},
        "c30", new int[][] {{28}, {28, 37}, {26, 27, 23}, {37, 20, 10, 11}, {44, 14, 38, 40, 22}, {26, 11, 24, 38, 28, 14}, {27, 12, 42, 11, 38, 19, 18},
            {44, 14, 18, 36, 21, 11, 9, 29}});
    /** entity.item.pickup pitch per answer index in 63ths: container 10 rose 50/58/65/73/81, container 30 49/57/64/72/80/87/95; beyond ASSUMED +7.75. */
    static final int[] PITCH_10 = {50, 58, 65, 73, 81};
    static final int[] PITCH_30 = {49, 57, 64, 72, 80, 87, 95};

    static final int SHOW_BASE = 41, SHOW_STEP = 6, TIMER_EXTRA = 3, OVER_WRONG = 1, OVER_CLOSE = 4;

    final List<Integer> board = new ArrayList<>();
    private final int[][] recorded;
    private final int[] pitches;
    int round;
    int answered;
    List<Integer> positions = List.of();
    /** Highest completed series (the Over's "Highest series"). */
    int completed;
    /** Highest round whose input began: the rewards count this (captured: series 5 paid 6 x 7k and +3, series 7 paid 8 x 7k). */
    int reached;
    long acceptFrom = Long.MAX_VALUE;
    long hideAt;
    int timer = -1;
    private long timerAt;
    private boolean maxSaid;

    Ultra(Engine e, MenuItems.Tier tier, String seed, long rngSeed, int round, boolean practice) {
        super(e, tier, seed, rngSeed, practice);
        if (meta()) for (int s = 9; s <= 44; s++) board.add(s);
        else for (int r = 1; r <= (tier.name().equals("Supreme") ? 3 : 4); r++) for (int c = 1; c <= 7; c++) board.add(r * 9 + c);
        recorded = meta() ? RECORDED.get(seed) : null;
        pitches = "c10".equals(seed) ? PITCH_10 : PITCH_30;
        this.round = round;
        this.completed = round - 1;
        this.reached = round - 1;
    }

    @Override
    String title() {
        return "§rUltrasequencer (" + tier.name() + ")";
    }

    static Item number(int n) {
        int i = (n - 1) % DYES.length;
        return Item.of(DYES[i], Math.min(64, n), "§r§" + CODES[i] + n);
    }

    static Item pane(int round) {
        return Item.pane(PANES[(round - 1) % PANES.length]);
    }

    private List<Integer> place(int n) {
        if (recorded != null && n <= recorded.length) {
            List<Integer> out = new ArrayList<>();
            for (int s : recorded[n - 1]) out.add(s);
            return out;
        }
        List<Integer> free = new ArrayList<>(board);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n && !free.isEmpty(); i++) out.add(free.remove(rng.nextInt(free.size())));
        return out;
    }

    Item[] initialSlots() {
        Item[] s = MenuItems.black(6);
        positions = place(round);
        paint(s);
        return s;
    }

    private void paint(Item[] s) {
        for (int slot : board) {
            int i = positions.indexOf(slot);
            s[slot] = i >= 0 ? number(i + 1) : pane(round);
        }
        s[49] = glowstone();
    }

    void opened() {
        scheduleHide();
    }

    /** The whole new board in one tick (slots that already show the right item are not re-sent). */
    private void burst(int n) {
        round = n;
        answered = 0;
        timer = -1;
        positions = place(n);
        Item[] s = new Item[54];
        paint(s);
        for (int slot : board) e.set(slot, s[slot]);
        e.set(49, s[49]);
        scheduleHide();
    }

    /** Numbers shown for 41 + 6(N-1) ticks; then all panes and the clock N+3 in one tick. Input from that tick. */
    private void scheduleHide() {
        hideAt = e.tick + SHOW_BASE + (long) SHOW_STEP * (round - 1);
        acceptFrom = hideAt - (e.lateClick ? 1 : 0);
        int n = round;
        e.atFor(this, hideAt, () -> {
            for (int i = answered; i < positions.size(); i++) e.set(positions.get(i), pane(n));
            timer = n + TIMER_EXTRA;
            timerAt = e.tick;
            e.set(49, clock(timer));
            reached = Math.max(reached, n);
            if (reached >= Engine.maxBonusAt("ultra", tier) && !maxSaid) {
                maxSaid = true;
                e.chat(MenuItems.MAX_CLICKS_CHAT);
            }
        });
    }

    @Override
    String click(int slot, Click click) {
        if (ended) return "game over";
        if (!board.contains(slot)) return "not on the board, ignored (ASSUMED)";
        if (!click.press()) return click + " is not a press, ignored (ASSUMED)";
        if (e.tick < acceptFrom || answered >= round) return "display, ignored";
        if (slot != positions.get(answered)) {
            ended = true;
            acceptFrom = Long.MAX_VALUE;
            timer = -1;
            reached = Math.max(reached, round);
            e.later(OVER_WRONG, () -> over("§r§8Clicked wrong number"));
            return "wrong slot (expected " + positions.get(answered) + " for " + (answered + 1) + ")";
        }
        int n = ++answered;
        e.sound("entity.item.pickup", "PLAYERS", 1.0f, pitch(n - 1) / 63f);
        e.later(1, () -> e.set(slot, number(n)));
        if (n < round) return "correct " + n + "/" + round;
        e.sound("entity.player.levelup", "PLAYERS", 0.7f, 111 / 63f);
        completed = round;
        acceptFrom = Long.MAX_VALUE;
        timer = -1;
        int next = round + 1;
        e.later(1, () -> burst(next));
        return "correct " + n + ", round " + round + " done";
    }

    private int pitch(int index) {
        if (index < pitches.length) return pitches[index];
        return pitches[pitches.length - 1] + (int) Math.round((index - pitches.length + 1) * 7.75);
    }

    @Override
    void tick() {
        if (ended || timer < 0 || e.tick == timerAt || (e.tick - timerAt) % 20 != 0) return;
        timer--;
        if (timer > 0) {
            e.set(49, clock(timer));
            return;
        }
        ended = true; // ASSUMED: timer expiry was never captured
        over("§r§8Time ran out");
    }

    @Override
    void clientClosed() {
        ended = true;
        e.afterClose(OVER_CLOSE, () -> over("§r§8Game closed"));
    }

    private void over(String reason) {
        e.openOver("ultra", tier, reason, completed, Math.max(completed, reached), practice);
    }

    @Override
    String status() {
        StringBuilder sb = new StringBuilder("round " + round + ", ");
        sb.append(e.tick < acceptFrom ? "display until t" + hideAt : "input " + answered + "/" + round);
        if (timer >= 0) sb.append(", timer ").append(timer).append('s');
        sb.append(", series ").append(completed).append(", seed ").append(seed).append(", slots ").append(positions);
        return sb.toString();
    }
}
