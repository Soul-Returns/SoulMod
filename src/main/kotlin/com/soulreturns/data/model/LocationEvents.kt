package com.soulreturns.data.model

import com.soulreturns.core.events.Event

/** Published when the player moves to a different SkyBlock island (or leaves SkyBlock entirely). */
data class AreaChanged(val previous: String?, val current: String?) : Event

/** Published when the scoreboard sidebar's `⏣` line changes (sub-area within an island). */
data class SublocationChanged(val previous: String?, val current: String?) : Event
