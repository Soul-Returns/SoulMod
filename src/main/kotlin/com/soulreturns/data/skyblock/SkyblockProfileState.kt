package com.soulreturns.data.skyblock

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.SoulLogger

/**
 * The active SkyBlock profile name ("Banana", "Coconut", …) and its exact profile id.
 *
 * The name comes from the tab list and from Hypixel's name line ([ProfileChatLines]: `You are playing
 * on profile: Banana (Co-op)` on a server join, `Your profile was changed to: Banana (Co-op)` after a
 * switch); the id only from the line right after it, `Profile ID: <uuid>`. `null` when off SkyBlock or
 * before either has arrived. Switching profiles with `/profile <name>` moves you to another server
 * instance, so a change here is always accompanied by a
 * [com.soulreturns.api.HypixelModApi.LocationChangeEvent].
 *
 * The *flavour* (co-op / bingo / ironman) is [SkyblockState.profileType].
 *
 * Subscribe to [ProfileChanged] rather than polling — it fires on transition only.
 */
object SkyblockProfileState {
    private val logger = SoulLogger("Soul/Skyblock")

    @Volatile
    var current: String? = null
        private set

    /**
     * The active profile's id, dashed lowercase (Hypixel's `profile_id` form), or null until its
     * `Profile ID:` line arrived for [current]. Cleared whenever the name moves to another profile.
     */
    @Volatile
    var profileId: String? = null
        private set

    fun isOnProfile(name: String): Boolean = current == name

    /** [SkyblockStateReader] only. Publishes [ProfileChanged] when the value actually transitions. */
    internal fun updateProfile(new: String?) {
        if (new == current) return
        val previous = current
        current = new
        // An id belongs to one profile. Hypixel prints the name line before the id line, so the
        // new profile's id lands right after this.
        updateProfileId(null)
        logger.debug("Profile: ${previous ?: "-"} -> ${new ?: "-"}")
        Events.publish(ProfileChanged(previous, new))
    }

    /** [SkyblockStateReader] only. [raw] in any uuid form; normalized to dashed lowercase. */
    internal fun updateProfileId(raw: String?) {
        val new = MojangUuid.parse(raw)?.toString()
        if (new == profileId) return
        profileId = new
        logger.debug("Profile id: ${new ?: "-"}")
    }
}

/** The active profile changed. Fires once per real transition, including to/from `null`. */
data class ProfileChanged(val previous: String?, val current: String?) : Event
