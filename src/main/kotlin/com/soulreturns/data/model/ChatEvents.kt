package com.soulreturns.data.model

import com.soulreturns.core.events.Event
import net.minecraft.network.chat.Component

/**
 * Published whenever Minecraft delivers a chat or game-system message.
 *
 * `raw` is the server-sent text *with* color codes preserved — call
 * [com.soulreturns.util.MessageDetector.stripColorCodes] before pattern-matching if you don't
 * care about formatting. `source` is best-effort: see [com.soulreturns.util.MessageDetector.isPlayerMessage]
 * for the heuristic that distinguishes player chat from server announcements.
 *
 * `component` is the original Mojang [Component] tree that Fabric delivered — present when
 * the message came through `ClientReceiveMessageEvents.GAME` / `CHAT` (the normal path)
 * and `null` when synthesized via `MessageHandler.simulateMessage(...)` for tests. Walk
 * this tree (and its `siblings`) to inspect `Style.hoverEvent` — required for any feature
 * that needs the hover-tooltip content of a chat line (e.g. the sack chat reader parsing
 * `[Sacks] +N items.` per-item breakdowns).
 */
data class ChatMessage(
    val raw: String,
    val source: Source,
    val component: Component? = null,
) : Event {
    enum class Source { SERVER, PLAYER }
}
