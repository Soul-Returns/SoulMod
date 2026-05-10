package com.soulreturns.data.location

import com.soulreturns.core.events.Events
import com.soulreturns.data.model.AreaChanged
import com.soulreturns.data.model.SublocationChanged

/**
 * Read-only access to the player's current SkyBlock location, cached from the last value
 * [LocationReader] published.
 *
 *  - [currentArea] = current Hypixel SkyBlock island ("Garden", "Hub", "Dwarven Mines", …),
 *    sourced from the tab list's `Area: <X>` entry.
 *  - [currentSublocation] = scoreboard sidebar's `⏣` line ("The Garden", "Ruins", …).
 *
 * Both are `null` when the relevant signal isn't available (e.g. not connected, tab list
 * not synced, off the SkyBlock server).
 *
 * Subscribe to [AreaChanged] / [SublocationChanged] to react to transitions instead of polling.
 */
object LocationApi {
    @Volatile
    var currentArea: String? = null
        private set

    @Volatile
    var currentSublocation: String? = null
        private set

    fun isInArea(name: String): Boolean = currentArea == name

    fun isInSublocation(name: String): Boolean = currentSublocation == name

    /** Called by [LocationReader] only. Publishes [AreaChanged] when the value transitions. */
    internal fun updateArea(new: String?) {
        if (new == currentArea) return
        val previous = currentArea
        currentArea = new
        Events.publish(AreaChanged(previous, new))
    }

    /** Called by [LocationReader] only. Publishes [SublocationChanged] when the value transitions. */
    internal fun updateSublocation(new: String?) {
        if (new == currentSublocation) return
        val previous = currentSublocation
        currentSublocation = new
        Events.publish(SublocationChanged(previous, new))
    }
}
