package com.soulreturns.features.mining.mineshaft

/**
 * Menu clicks play the talisman's harp at the player (rule://mineshaft, recordings: the Bazaar's
 * Confirm Sell Offer bytes 69 then 88, up to 395 ms after the click and 187 ms after the menu closed;
 * every Fossil Excavator click 126): no ding counts while a container is open or for [QUIET_TICKS]
 * after it closed. Pure: [CorpseLocator] feeds it each client tick, the replay test from recordings.
 */
class MenuQuiet {
    private var tick = 0L
    private var lastOpen = -QUIET_TICKS - 1L

    /** One client tick; [open] = a container was open on it. */
    fun tick(open: Boolean) {
        if (open) lastOpen = tick
        tick++
    }

    /** True when a harp heard now may come from a menu ([openNow]: a container is open right now). */
    fun blocks(openNow: Boolean): Boolean = openNow || tick - lastOpen <= QUIET_TICKS

    companion object {
        const val QUIET_TICKS = 10
    }
}
