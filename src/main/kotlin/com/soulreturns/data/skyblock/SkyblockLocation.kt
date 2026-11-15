package com.soulreturns.data.skyblock

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import com.soulreturns.util.SoulLogger

/**
 * Where inside SkyBlock the player is, as SkyBlock itself reports it.
 *
 *  - [area] — the island ("Hub", "Dwarven Mines", "Dungeon"). From the tab list's `Area: <X>` entry,
 *    falling back to the location packet's `map` where the tab list has no such entry — which is every
 *    dungeon run. Both use the same vocabulary, and the fallback only ever fills a gap.
 *  - [subLocation] — the finer-grained place, from the scoreboard sidebar's `⏣ <X>` line
 *    ("Village", "Royal Mines", "Glacite Mineshafts"). Changes *without* a server switch, which is
 *    why it is polled rather than derived from a packet.
 *
 * Both are `null` whenever the signal is unavailable — off SkyBlock, or in the first seconds after a
 * join while Hypixel is still populating the tab list. `null` therefore means "unknown", **not**
 * "left"; [SkyblockStateReader] is what decides when a missing signal has lasted long enough to be a
 * real departure.
 *
 * Distinct from [com.soulreturns.api.LocationApi], which is the Hypixel *instance* API (`serverId`,
 * `mode`): it knows which server you were routed to, not which island the game thinks you are on.
 *
 * Subscribe to [AreaChanged] / [SubLocationChanged] instead of polling — they fire on transition only.
 */
object SkyblockLocation {
    private val logger = SoulLogger("Soul/Skyblock")

    /** Written on the client thread by the reader; read from coroutines and the realtime thread. */
    @Volatile
    var area: String? = null
        private set

    @Volatile
    var subLocation: String? = null
        private set

    fun isInArea(name: String): Boolean = area == name

    fun isInSubLocation(name: String): Boolean = subLocation == name

    /** [SkyblockStateReader] only. Publishes [AreaChanged] when the value actually transitions. */
    internal fun updateArea(new: String?) {
        if (new == area) return
        val previous = area
        area = new
        logger.debug("Area: ${previous ?: "-"} -> ${new ?: "-"}")
        Events.publish(AreaChanged(previous, new))
    }

    /** [SkyblockStateReader] only. Publishes [SubLocationChanged] when the value actually transitions. */
    internal fun updateSubLocation(new: String?) {
        if (new == subLocation) return
        val previous = subLocation
        subLocation = new
        logger.debug("Sublocation: ${previous ?: "-"} -> ${new ?: "-"}")
        Events.publish(SubLocationChanged(previous, new))
    }
}

/**
 * The island changed. Fires once per real transition, including to/from `null`.
 *
 * These arrive in a **burst** on join (server → onSkyblock → area → sublocation within a few ticks),
 * so a handler must be cheap — debounce anything expensive.
 */
data class AreaChanged(val previous: String?, val current: String?) : Event

/** The sidebar's `⏣` sublocation changed. Same burst caveat as [AreaChanged]. */
data class SubLocationChanged(val previous: String?, val current: String?) : Event
