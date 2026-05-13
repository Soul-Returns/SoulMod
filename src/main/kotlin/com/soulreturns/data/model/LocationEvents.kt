package com.soulreturns.data.model

import com.soulreturns.core.events.Event

/** Published when the player moves to a different SkyBlock island (or leaves SkyBlock entirely). */
data class AreaChanged(val previous: String?, val current: String?) : Event

/** Published when the scoreboard sidebar's `⏣` line changes (sub-area within an island). */
data class SublocationChanged(val previous: String?, val current: String?) : Event

/**
 * Published when the player crosses the SkyBlock boundary (joins/leaves the SkyBlock server,
 * detected via the scoreboard sidebar title being `SKYBLOCK`). Distinct from [AreaChanged] —
 * area changes only fire *within* SkyBlock; this one fires when transitioning between
 * SkyBlock and elsewhere (lobby, Bedwars, login screen).
 */
data class OnSkyblockChanged(val previous: Boolean, val current: Boolean) : Event
