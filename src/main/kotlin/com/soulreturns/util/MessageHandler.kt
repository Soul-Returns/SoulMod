package com.soulreturns.util

import com.soulreturns.Soul
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ActionBarMessage
import com.soulreturns.data.model.ChatMessage
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * Central chat pump. Distinguishes server/system lines from player chat and publishes a typed
 * [ChatMessage] event for each, plus exposes legacy server/player callback registration.
 */
object MessageHandler {
    private val serverMessageHandlers = mutableListOf<(String) -> Unit>()
    private val playerMessageHandlers = mutableListOf<(String) -> Unit>()
    private var lastProcessed: String? = null
    private var lastProcessedAt: Long = 0L
    private var isRegistered = false

    // Suppress only within-tick duplicates (CHAT and GAME firing for the same packet). A longer
    // window would swallow legitimate back-to-back identical server lines, breaking per-event counters.
    private const val DEDUP_WINDOW_MS = 50L

    /**
     * Subscribe to BOTH the normal and `*_CANCELED` channels.
     *
     * Other mods (SkyHanni, etc.) cancel chat by returning `false` from
     * `ClientReceiveMessageEvents.ALLOW_*`. Once cancelled, the regular `CHAT` / `GAME` events
     * never fire — but `CHAT_CANCELED` / `GAME_CANCELED` do. Subscribing to both means we see
     * every message exactly once regardless of what else is installed. They're mutually exclusive
     * per Fabric's dispatch order, so no double-processing.
     */
    fun register() {
        if (isRegistered) return

        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handleMessage(message.string, message)
        }
        ClientReceiveMessageEvents.CHAT_CANCELED.register { message, _, _, _, _ ->
            handleMessage(message.string, message)
        }
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (overlay) {
                Events.publish(ActionBarMessage(message.string))
                return@register
            }
            handleMessage(message.string, message)
        }
        ClientReceiveMessageEvents.GAME_CANCELED.register { message, overlay ->
            if (overlay) {
                Events.publish(ActionBarMessage(message.string))
                return@register
            }
            handleMessage(message.string, message)
        }

        isRegistered = true
    }

    fun onServerMessage(handler: (String) -> Unit) {
        serverMessageHandlers.add(handler)
    }

    fun onPlayerMessage(handler: (String) -> Unit) {
        playerMessageHandlers.add(handler)
    }

    /** Bypass the event pipeline and process [message] directly — for testing. */
    fun simulateMessage(message: String) {
        handleMessage(message, null)
    }

    private fun handleMessage(
        raw: String,
        component: Component?,
    ) {
        try {
            val trimmed = raw.trim()

            val now = System.currentTimeMillis()
            if (lastProcessed == trimmed && now - lastProcessedAt < DEDUP_WINDOW_MS) return
            lastProcessed = trimmed
            lastProcessedAt = now

            val source =
                if (MessageDetector.isPlayerMessage(trimmed)) {
                    ChatMessage.Source.PLAYER
                } else {
                    ChatMessage.Source.SERVER
                }

            Events.publish(ChatMessage(trimmed, source, component))

            val handlers = if (source == ChatMessage.Source.PLAYER) playerMessageHandlers else serverMessageHandlers
            DebugLogger.logMessageHandler("${source.name.lowercase().replaceFirstChar(Char::uppercase)} message: $trimmed")
            handlers.forEach { handler ->
                try {
                    handler(trimmed)
                } catch (t: Throwable) {
                    Soul.getLogger().error("Error in ${source.name.lowercase()} message handler", t)
                }
            }
        } catch (t: Throwable) {
            Soul.getLogger().error("Error handling message in MessageHandler", t)
        }
    }
}

/** Detects message types and patterns over raw Hypixel chat strings. Pure string logic — no MC API. */
object MessageDetector {
    fun isPlayerMessage(message: String): Boolean {
        val stripped = stripColorCodes(message)

        return when {
            stripped.contains("Party >", ignoreCase = false) -> true
            stripped.contains("Guild >", ignoreCase = false) -> true
            stripped.contains("Officer >", ignoreCase = false) -> true
            stripped.matches(".*\\[(VIP|MVP|MOD|ADMIN|HELPER|YOUTUBE|PIG).*?].*:.*".toRegex(RegexOption.IGNORE_CASE)) -> true
            stripped.matches("\\[\\d+] (?:\\[.*?] )?[a-zA-Z0-9_]{3,16}: .*".toRegex()) -> true
            // (?![+\\-]\\d) excludes SkyBlock skill XP lines like "Runecrafting: +582 XP".
            stripped.matches("[a-zA-Z0-9_]{3,16}: (?![+\\-]\\d).*".toRegex()) -> true
            else -> false
        }
    }

    /**
     * Strip `§` + any single following char — covers vanilla codes AND Hypixel's non-standard
     * placeholder codes (§y, §x, §u, …) used as scoreboard line keys.
     */
    fun stripColorCodes(text: String): String = text.replace("§.".toRegex(), "")

    /**
     * Replace characters that cannot be seen or pasted with a `<U+XXXX>` placeholder, leaving
     * everything legible untouched. For diagnostic dumps.
     *
     * **Hypixel ships a resource pack that draws its own icons from the Unicode private-use area**
     * (`U+E000`-`U+F8FF`); the scoreboard's location marker is `U+E067`. Those codepoints draw as an
     * icon in game, as nothing at all anywhere else, and a copy-paste drops them silently — so a raw
     * dump of a line containing one is indistinguishable from a dump of a line without it. That
     * ambiguity is not academic: it is what made Hypixel's 2026-08-11 swap of the location marker look
     * like the marker had simply been deleted.
     *
     * Ordinary symbols (`❤`, `☀`, `⏣`) paste fine and are deliberately left alone — escaping them only
     * makes a dump harder to read.
     *
     * `<U+E067>` rather than `\uE067` on purpose: it survives JSON encoding without picking up a second
     * backslash, and it cannot be mistaken for an escape that was already in the text.
     */
    fun escapeInvisible(text: String): String {
        if (text.none { it.isInvisibleGlyph() }) return text
        return buildString(text.length) {
            for (c in text) {
                if (c.isInvisibleGlyph()) append(String.format(Locale.ROOT, "<U+%04X>", c.code)) else append(c)
            }
        }
    }

    /** Renders as nothing, or not at all, outside the game it came from. */
    private fun Char.isInvisibleGlyph(): Boolean =
        code in 0xE000..0xF8FF || // private-use area — Hypixel's resource-pack icons
            code < 0x20 || code == 0x7F || // C0 controls
            code in 0x80..0x9F || // C1 controls
            code in 0x200B..0x200F || // zero-width space + bidi marks
            code == 0xFEFF // BOM / zero-width no-break space

    fun containsPattern(
        message: String,
        pattern: String,
        ignoreCase: Boolean = true,
    ): Boolean = stripColorCodes(message).contains(pattern, ignoreCase)

    fun extractPlayerName(message: String): String? {
        val stripped = stripColorCodes(message)
        val patterns =
            listOf(
                "(?:Party|Guild|Officer) > (?:\\[.*?] )?([a-zA-Z0-9_]+)\\s*:".toRegex(),
                "(?:\\[\\d+] )?(?:\\[.*?] )?([a-zA-Z0-9_]+)\\s*:".toRegex(),
            )
        for (pattern in patterns) {
            val match = pattern.find(stripped)
            if (match != null) return match.groupValues.getOrNull(1)
        }
        return null
    }
}
