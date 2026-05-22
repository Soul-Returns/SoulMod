package com.soulreturns.data.model

import com.soulreturns.core.events.Event

/**
 * Published after [com.soulreturns.features.sacks.SackChatReader] parses a `[Sacks] ±N items.`
 * line and applies the per-item deltas to [com.soulreturns.features.sacks.SackState].
 *
 * Subscribers see the same `Map<skyblockId, Long>` the state received — positive entries are
 * items the sack absorbed, negative entries are items removed. Hypixel batches these every
 * ~5 s while sack-touching activity continues, so this event is the canonical "per-item sack
 * deltas just happened" signal for any feature that needs to react.
 *
 * **Catalog misses** are NOT in the map — `SackChatReader` drops them when the display name
 * doesn't resolve to a known item id, same as it does for `SackState.applyDeltas`. The
 * timestamp is server-receive time, captured by the publisher.
 */
data class SackDeltasApplied(
    val deltas: Map<String, Long>,
    val timestamp: Long = System.currentTimeMillis(),
) : Event
