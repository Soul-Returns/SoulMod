package com.soulreturns.features.fishing

import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ActionBarMessage
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.util.MessageDetector

/**
 * Fishing XP, read from the **action bar** — the only place the real number appears.
 *
 * ### Why not the catalog's `fishingExperience`
 * Because that is the base value, and almost nobody earns the base value. Wisdom, the pet, the
 * Fishing Festival, event buffs and Bestiary all multiply it, so a total accumulated from the
 * catalog is a plausible-looking number that is wrong for every real account and wrong by a
 * different factor for each one — which is worse than not showing it at all. Hypixel prints what
 * actually landed in the action bar, so that is what gets counted.
 *
 * ### Dedupe, because the action bar repeats
 * Hypixel resends the action bar every tick while the message is up, so the same `+12.5 Fishing`
 * would otherwise be counted twenty times a second. The whole matched fragment is the dedupe key:
 * the progress part (`1,234.5/5,000` or `34.5%`) advances with every gain, so a genuine second gain
 * of the same size still produces a different fragment. Two identical gains landing inside one
 * percentage-rounding step is the one case this under-counts, and it is a far better failure than
 * counting each frame.
 *
 * ### It is tolerant on purpose
 * The fragment is matched anywhere in the bar, in either progress form, with or without thousands
 * separators. Hypixel reworded skill-XP action bars before and will again; `/soul dev fishing`
 * prints the raw bar and the last fragment matched, so a rewording is one paste away from a fix
 * rather than a counter that silently stopped.
 */
object FishingXpTracker {
    /**
     * `+12.5 Fishing (1,234.5/5,000)` or `+12.5 Fishing (34.5%)`, anywhere in the bar, colour codes
     * already stripped. `\+` is required — a bare "Fishing" appears in other contexts.
     */
    private val FISHING_XP = Regex("""\+([0-9][0-9,.]*) Fishing \(([^)]+)\)""")

    /** Last fragment counted, so a repeated action bar does not count twice. */
    @Volatile private var lastFragment: String? = null

    /** The most recent action bar seen while on SkyBlock, for `/soul dev fishing`. */
    @Volatile
    var lastActionBar: String = ""
        private set

    /** The most recent fishing-XP fragment matched, or null if none ever was. */
    @Volatile
    var lastMatch: String? = null
        private set

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        Events.subscribe<ActionBarMessage> { onActionBar(it.text) }
    }

    private fun onActionBar(raw: String) {
        if (!SkyblockState.onSkyblock) return
        val text = MessageDetector.stripColorCodes(raw)
        lastActionBar = text

        val match = FISHING_XP.find(text) ?: run {
            // The bar moved on to something else, so the next XP fragment is a new gain even if it
            // happens to read identically to the last one.
            lastFragment = null
            return
        }
        val fragment = match.value
        if (fragment == lastFragment) return
        lastFragment = fragment
        lastMatch = fragment

        val gained = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return
        if (gained <= 0.0) return
        FishingTracker.recordXp(gained)
    }
}
