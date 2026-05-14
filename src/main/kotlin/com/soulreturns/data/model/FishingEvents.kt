package com.soulreturns.data.model

import com.soulreturns.core.events.Event
import com.soulreturns.data.fishing.SeaCreature

/**
 * Fishing-festival lifecycle events published by
 * [com.soulreturns.data.skyblock.FishingFestivalState].
 *
 * The festival's "start" boundary is the first underway-chat message we observe in this
 * mod-session — which may be the real festival start, a server-join echo while one is
 * already running, or a Hypixel-bug duplicate within an active festival. The state object
 * deduplicates duplicates; only the first occurrence per festival publishes [Started].
 *
 * The "end" boundary is whichever comes first: the concluded chat message
 * ([EndReason.CONCLUDED_MESSAGE]) or the 1-hour safety cap ([EndReason.ONE_HOUR_CAP]) from
 * `firstSeenStartAt`.
 */
sealed class FishingFestivalEvent : Event {
    /** Festival became active. [firstSeenStartAt] is epoch ms of the underway chat we saw. */
    data class Started(val firstSeenStartAt: Long) : FishingFestivalEvent()

    /** Festival ended (concluded message or 1h cap). */
    data class Ended(
        val firstSeenStartAt: Long,
        val endedAt: Long,
        val reason: EndReason,
    ) : FishingFestivalEvent()

    enum class EndReason { CONCLUDED_MESSAGE, ONE_HOUR_CAP }
}

/**
 * Published by [com.soulreturns.features.fishing.FishingTracker] whenever a sea creature
 * catch message is matched against [com.soulreturns.data.fishing.SeaCreatureCatalog].
 *
 * `doubleHook` is the sticky-flag state at consume time (set by a preceding "It's a Double
 * Hook!" line; reset after this event fires). `duringFestival` is a snapshot of
 * [com.soulreturns.data.skyblock.FishingFestivalState.active] at the moment of the catch.
 */
data class SeaCreatureCaught(
    val creature: SeaCreature,
    val doubleHook: Boolean,
    val duringFestival: Boolean,
    val timestampMs: Long,
) : Event
