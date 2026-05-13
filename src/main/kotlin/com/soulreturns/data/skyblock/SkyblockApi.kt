package com.soulreturns.data.skyblock

import com.soulreturns.core.events.Events
import com.soulreturns.data.model.OnSkyblockChanged

/**
 * Read-only access to "is the player currently on Hypixel SkyBlock?" — fed by
 * [SkyblockReader] which polls the scoreboard sidebar title.
 *
 * Distinct from [LocationApi][com.soulreturns.data.location.LocationApi]:
 *   - `LocationApi.currentArea` / `currentSublocation` describe **where** inside SkyBlock.
 *   - `SkyblockApi.isOnSkyblock` answers **whether** we're in SkyBlock at all.
 *
 * Subscribe to [OnSkyblockChanged] to react to transitions.
 */
object SkyblockApi {
    @Volatile
    var isOnSkyblock: Boolean = false
        private set

    /** Called by [SkyblockReader] only. Publishes [OnSkyblockChanged] on transition. */
    internal fun update(new: Boolean) {
        if (new == isOnSkyblock) return
        val previous = isOnSkyblock
        isOnSkyblock = new
        Events.publish(OnSkyblockChanged(previous, new))
    }
}
