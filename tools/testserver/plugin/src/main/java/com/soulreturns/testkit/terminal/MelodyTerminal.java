package com.soulreturns.testkit.terminal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * {@code §rClick the button on time!}, {@code generic_9x6}. A magenta pane marks the target column (top slots 1-5,
 * mirrored at 37-41); three lanes (slots 10-14, 19-23, 28-32) with lock-in buttons at 16, 25, 34. The active lane
 * is red with one lime pane that steps every 10 ticks and bounces at the ends; inactive lanes are white. A lock
 * while the lime pane is in the target column is accepted (pling) and the next lane activates with a new target;
 * the third lock completes (one completion pling, no accepted-click pling: captured).
 *
 * <p>Wrong lock: error sound (volume 8, pitch 0), no chat, the menu stays open and the pane stands still for
 * {@link #pauseTicks}; the 10-tick step loop runs on and the pane moves again on its first step after that
 * (recording 2026-11-12 22:42: errors at ticks 39 and 53, next moves at 60 and 80). Lane 1 starts at column 1
 * moving right (34 of 34 openings). An accepted lock hands over at once: the next lane is active from that tick
 * with its pane in the locked column and a new target, but the board is only redrawn on the next step, the pane
 * by then one column on (54 of 54 lane changes, 2026-11-12). A lock on the new lane before that step is judged
 * against the locked column: the "skip" (22:52:12, cid 29). The finished lane turns back to white panes with a
 * {@code Row Not Active} button. Assumption, not captured: locks during the pause, on an inactive row, a pane or
 * a filler do nothing. {@link #alignNext} gives every next lane the locked column as target, so a lock on it
 * right after the previous one skips it (Hypixel: target directly below about one time in five).
 */
public final class MelodyTerminal extends Terminal {
    public static final int STEP_TICKS = 10;
    public static final int LIME_START = 1;
    public static final Cell PURPLE = Cell.of("magenta_stained_glass_pane", "");
    public static final Cell LIME = Cell.of("lime_stained_glass_pane", "");
    public static final Cell RED = Cell.of("red_stained_glass_pane", "");
    public static final Cell WHITE = Cell.of("white_stained_glass_pane", "");
    private static final List<String> LORE = List.of("§r§7Click this button when the Green", "§r§7lines up with the Purple!");
    public static final Cell LOCK = new Cell("lime_terracotta", "§r§aLock In Slot", 1, LORE, false);
    public static final Cell INACTIVE = new Cell("red_terracotta", "§r§cRow Not Active", 1, LORE, false);

    /** Ticks the animation stands still after a wrong lock. */
    public static volatile int pauseTicks = 20;
    /** Test aid: the next lane's target is the locked column, so locking it before its first step skips it. */
    public static volatile boolean alignNext = false;

    private final Random rng;
    private int lane;
    private int target;
    private int limeX;
    private int dir = 1;
    private int stepIn = STEP_TICKS;
    private int pauseLeft;
    private boolean finished;

    private MelodyTerminal(Random rng) {
        super(6);
        this.rng = rng;
    }

    public static MelodyTerminal random(Random rng) {
        MelodyTerminal terminal = new MelodyTerminal(rng);
        terminal.target = 1 + rng.nextInt(5);
        terminal.limeX = LIME_START;
        terminal.draw(terminal.cells, null);
        return terminal;
    }

    /** A captured opening: target column 1-5 and the lime pane's column in lane 1. */
    public static MelodyTerminal of(int target, int limeX, Random rng) {
        MelodyTerminal terminal = new MelodyTerminal(rng);
        terminal.target = target;
        terminal.limeX = limeX;
        terminal.draw(terminal.cells, null);
        return terminal;
    }

    public int lane() {
        return lane;
    }

    public int target() {
        return target;
    }

    public int limeX() {
        return limeX;
    }

    public boolean paused() {
        return pauseLeft > 0;
    }

    public boolean finished() {
        return finished;
    }

    public static int laneSlot(int lane, int x) {
        return (lane + 1) * WIDTH + x;
    }

    public static int buttonSlot(int lane) {
        return (lane + 1) * WIDTH + 7;
    }

    /** Draws the whole board of the current state into {@code out}; {@code diff} (nullable) collects slots that changed. */
    private void draw(Cell[] out, Map<Integer, Cell> diff) {
        for (int slot = 0; slot < out.length; slot++) {
            Cell cell = Cell.FILLER;
            int x = slot % WIDTH;
            int row = slot / WIDTH;
            if (x >= 1 && x <= 5 && (row == 0 || row == 4) && x == target) cell = PURPLE;
            else if (row >= 1 && row <= 3) {
                int l = row - 1;
                boolean active = l == lane && !finished;
                if (x >= 1 && x <= 5) cell = !active ? WHITE : x == limeX ? LIME : RED;
                else if (x == 7) cell = active ? LOCK : INACTIVE;
            }
            if (diff != null && !cell.equals(out[slot])) diff.put(slot, cell);
            out[slot] = cell;
        }
    }

    @Override
    public Kind kind() {
        return Kind.MELODY;
    }

    @Override
    public String title() {
        return "§rClick the button on time!";
    }

    @Override
    public Reply click(int slot, Input input) {
        if (finished || pauseLeft > 0 || slot != buttonSlot(lane)) return Reply.IGNORED;
        if (limeX != target) {
            pauseLeft = pauseTicks;
            return new Reply(Outcome.FAIL_STAY, Map.of(), null, 0);
        }
        if (lane == 2) {
            finished = true;
            return complete(1);
        }
        // Shown on the next step only.
        lane++;
        target = alignNext ? limeX : 1 + rng.nextInt(5);
        return new Reply(Outcome.ACCEPTED, Map.of(), null, 1);
    }

    @Override
    public Reply tick() {
        if (finished) return Reply.IGNORED;
        Map<Integer, Cell> diff = new LinkedHashMap<>();
        boolean still = pauseLeft > 0;
        if (still) pauseLeft--;
        if (--stepIn == 0) {
            stepIn = STEP_TICKS;
            if (!still) {
                if (limeX + dir < 1 || limeX + dir > 5) dir = -dir;
                limeX += dir;
            }
            draw(cells, diff);
        }
        return diff.isEmpty() ? Reply.IGNORED : new Reply(Outcome.UPDATE, diff, null, 0);
    }
}
