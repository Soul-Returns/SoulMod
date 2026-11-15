package com.soulreturns.data.model

import com.soulreturns.core.events.Event
import net.minecraft.network.chat.Component

/**
 * A single received chat line, published on the [com.soulreturns.core.events.Events] bus.
 *
 * @param raw the trimmed plain-text content (color codes still present; strip via
 *   [com.soulreturns.util.MessageDetector.stripColorCodes] when matching)
 * @param source whether the message looks like player chat or a server/system line
 * @param component the original [Component] (null for synthetic/test messages)
 */
data class ChatMessage(
    val raw: String,
    val source: Source,
    val component: Component?,
) : Event {
    enum class Source { SERVER, PLAYER }
}
