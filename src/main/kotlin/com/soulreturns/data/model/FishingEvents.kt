package com.soulreturns.data.model

import com.soulreturns.core.events.Event
import com.soulreturns.data.fishing.SeaCreature

/**
 * A `It's a Double Hook!` line was seen, published by
 * [com.soulreturns.features.fishing.FishingTracker] — the mod's **only** detector for that line.
 *
 * [count] is normally 1, but a receive-side chat compactor coalesces repeats into
 * `It's a Double Hook! (N)`, in which case this carries the decoded delta. A consumer that reacts
 * once per event (the party announcement) should ignore it; a consumer that counts must add it.
 */
data class DoubleHookDetected(val count: Int, val timestampMs: Long) : Event

/**
 * A sea creature was caught. `doubleHook` is the sticky-flag state consumed by this catch, and
 * `duringFestival` is a snapshot of the festival state at that moment — both are recorded here
 * rather than re-derived, because either can change before a handler runs.
 */
data class SeaCreatureCaught(
    val creature: SeaCreature,
    val doubleHook: Boolean,
    val duringFestival: Boolean,
    val timestampMs: Long,
) : Event

/**
 * Fishing-festival lifecycle, published by
 * [com.soulreturns.data.skyblock.FishingFestivalState].
 *
 * "Start" is the **first** underway message this mod-session observes for a given festival — which
 * may be the real start, a server-join echo while one is already running, or a Hypixel-bug duplicate
 * mid-festival. The state object deduplicates; only the first publishes [Started].
 *
 * "End" is whichever comes first: the concluded message, or the one-hour cap from the first-seen
 * start.
 */
sealed class FishingFestivalEvent : Event {
    data class Started(val firstSeenStartAt: Long) : FishingFestivalEvent()

    data class Ended(
        val firstSeenStartAt: Long,
        val endedAt: Long,
        val reason: EndReason,
    ) : FishingFestivalEvent()

    enum class EndReason { CONCLUDED_MESSAGE, ONE_HOUR_CAP }
}
