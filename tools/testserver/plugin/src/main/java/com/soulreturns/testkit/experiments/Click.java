package com.soulreturns.testkit.experiments;

/** A container click as the server reads it off the wire (research §1.4). */
public enum Click {
    /** PICKUP button 0. */
    LEFT,
    /** PICKUP button 1 (right click; unproven in games). */
    RIGHT,
    /** CLONE button 2 (middle click, SkyHanni's makePickblock). */
    MIDDLE,
    /** PICKUP_ALL (double click). */
    DOUBLE,
    /** QUICK_CRAFT phase 1 (a drag adds this slot). */
    DRAG,
    /** QUICK_CRAFT phase 0/2 (drag start/end, slot -999). */
    DRAG_EDGE,
    /** QUICK_MOVE (shift click). */
    SHIFT,
    /** SWAP, THROW and anything else. */
    OTHER;

    /**
     * Counted as one press on a game cell. CLONE, PICKUP and PICKUP_ALL are proven (5625); a drag's phase-1 slot is
     * ASSUMED to count like the terminals' (never shown in a game); right/shift/number keys/throw are ASSUMED ignored.
     */
    public boolean press() {
        return this == LEFT || this == MIDDLE || this == DOUBLE || this == DRAG;
    }

    /** Followed by Hypixel's full set_content 6-9 ticks later (every click but CLONE; 117 of 119 CLONE had none). */
    public boolean resyncs() {
        return this != MIDDLE;
    }
}
