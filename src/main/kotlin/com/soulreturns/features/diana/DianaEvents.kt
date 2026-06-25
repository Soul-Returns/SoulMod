package com.soulreturns.features.diana

import com.soulreturns.core.events.Event

/**
 * Source of a kill / drop event for the Diana stats tracker. OWN = the player's own dig
 * (the dig-out chat fired for them, or the LOOT SHARE chat did not fire while crediting
 * the drop). LOOTSHARE = the player damaged someone else's mob and either got the
 * lootshare detection (mob death) or the LOOT SHARE chat (loot received).
 */
enum class DianaEventSource { OWN, LOOTSHARE }

/**
 * Published when a Mythological mob is counted in [MythologicalMobTracker]. Cocoons are
 * NOT published — cocooned mobs are respawns of the original kill, not a separate
 * mob-counting event.
 */
data class MythologicalMobKilled(
    val mobName: String,
    val source: DianaEventSource,
) : Event

/**
 * Published when [MythologicalProfitTracker.grantDrop] credits a drop, regardless of
 * bucket. Subscribers filter by [source] / [bucketId] / [itemId] as needed.
 */
data class MythologicalDropCredited(
    val bucketId: String,
    val itemId: String,
    val amount: Long,
    val source: DianaEventSource,
) : Event
