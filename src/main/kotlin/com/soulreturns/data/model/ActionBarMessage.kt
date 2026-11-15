package com.soulreturns.data.model

import com.soulreturns.core.events.Event

/**
 * An overlay (action bar) line. Kept separate from [ChatMessage] — action bar spam (health/mana
 * every tick on Hypixel) must not hit the chat handlers or the dedup window.
 */
data class ActionBarMessage(val text: String) : Event
