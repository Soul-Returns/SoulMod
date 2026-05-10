package com.soulreturns.data.model

import com.soulreturns.core.events.Event

/**
 * Published when the player's active SkyBlock profile changes (or first becomes known
 * after login). [from] is null on the first detection of the session; [to] is null when
 * the player leaves SkyBlock and the tab list no longer carries a `Profile:` entry.
 */
data class ProfileChanged(val from: String?, val to: String?) : Event
