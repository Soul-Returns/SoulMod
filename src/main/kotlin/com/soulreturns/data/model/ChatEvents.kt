package com.soulreturns.data.model

import com.soulreturns.core.events.Event

/**
 * Published whenever Minecraft delivers a chat or game-system message.
 *
 * `raw` is the server-sent text *with* color codes preserved — call
 * [com.soulreturns.util.MessageDetector.stripColorCodes] before pattern-matching if you don't
 * care about formatting. `source` is best-effort: see [com.soulreturns.util.MessageDetector.isPlayerMessage]
 * for the heuristic that distinguishes player chat from server announcements.
 */
data class ChatMessage(val raw: String, val source: Source) : Event {
    enum class Source { SERVER, PLAYER }
}
