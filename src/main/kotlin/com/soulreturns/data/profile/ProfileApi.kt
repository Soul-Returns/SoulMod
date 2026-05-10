package com.soulreturns.data.profile

import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ProfileChanged

/**
 * Read-only access to the player's active Hypixel SkyBlock profile name (e.g. "Banana"),
 * cached from the last value [ProfileReader] published.
 *
 *  - `null` when not on SkyBlock, when the tab list has not yet synced a `Profile:` entry,
 *    or when the player is in a non-profile menu (Bazaar lobby, etc.).
 *  - Sourced from the tab list — same place [com.soulreturns.data.location.LocationApi]
 *    reads `Area:` from.
 *
 * Subscribe to [ProfileChanged] to react to transitions instead of polling.
 */
object ProfileApi {
    @Volatile
    var currentProfile: String? = null
        private set

    fun isOnProfile(name: String): Boolean = currentProfile == name

    /** Called by [ProfileReader] only. Publishes [ProfileChanged] on transition. */
    internal fun updateProfile(new: String?) {
        if (new == currentProfile) return
        val previous = currentProfile
        currentProfile = new
        Events.publish(ProfileChanged(previous, new))
    }
}
