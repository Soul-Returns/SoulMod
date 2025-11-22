package com.soulreturns.util

import com.soulreturns.Soul
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

/**
 * Central message handler that distinguishes between server and player messages,
 * allowing features to register callbacks for specific message types.
 */
object MessageHandler {
    private val serverMessageHandlers = mutableListOf<(String) -> Unit>()
    private val playerMessageHandlers = mutableListOf<(String) -> Unit>()
    private var lastProcessed: String? = null
    private var isRegistered = false

    /**
     * Initialize the message handler and register event listeners.
     * Should be called once during mod initialization.
     */
    fun register() {
        if (isRegistered) {
            Soul.getLogger()?.warn("MessageHandler already registered, skipping duplicate registration")
            return
        }

        // Register for normal chat messages
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handleMessage(message.string)
        }

        // Register for game/system messages
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (overlay) return@register // Ignore action bar / overlay lines
            handleMessage(message.string)
        }

        isRegistered = true
        Soul.getLogger()?.info("MessageHandler listeners registered")
    }

    /**
     * Register a callback for server messages.
     * Server messages are those that don't match player message patterns.
     *
     * @param handler Function that will be called with the message content
     */
    fun onServerMessage(handler: (String) -> Unit) {
        serverMessageHandlers.add(handler)
        Soul.getLogger()?.debug("Registered server message handler (total: ${serverMessageHandlers.size})")
    }

    /**
     * Register a callback for player messages.
     * Player messages include chat, party chat, guild chat, etc.
     *
     * @param handler Function that will be called with the message content
     */
    fun onPlayerMessage(handler: (String) -> Unit) {
        playerMessageHandlers.add(handler)
        Soul.getLogger()?.debug("Registered player message handler (total: ${playerMessageHandlers.size})")
    }

    /**
     * Clear all registered handlers. Useful for testing or reloading features.
     */
    fun clearHandlers() {
        serverMessageHandlers.clear()
        playerMessageHandlers.clear()
        Soul.getLogger()?.debug("Cleared all message handlers")
    }

    private fun handleMessage(raw: String) {
        try {
            val trimmed = raw.trim()

            // Basic deduplication in case both GAME and CHAT fire for same content
            if (lastProcessed == trimmed) return
            lastProcessed = trimmed

            // Determine message type and notify appropriate handlers
            if (MessageDetector.isPlayerMessage(trimmed)) {
                Soul.getLogger()?.debug("Player message detected: $trimmed")
                playerMessageHandlers.forEach { handler ->
                    try {
                        handler(trimmed)
                    } catch (t: Throwable) {
                        Soul.getLogger()?.error("Error in player message handler", t)
                    }
                }
            } else {
                Soul.getLogger()?.debug("Server message detected: $trimmed")
                serverMessageHandlers.forEach { handler ->
                    try {
                        handler(trimmed)
                    } catch (t: Throwable) {
                        Soul.getLogger()?.error("Error in server message handler", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Soul.getLogger()?.error("Error handling message in MessageHandler", t)
        }
    }
}

/**
 * Utility object for detecting message types and patterns.
 */
object MessageDetector {
    /**
     * Detects if a message is from a player rather than the server.
     * Player messages typically contain:
     * - Rank prefixes like [MVP++], [VIP], etc. (with or without color codes)
     * - Party chat prefix: "Party >"
     * - Guild chat prefix: "Guild >"
     * - Colon followed by player message (e.g., "PlayerName: message")
     * - Player name patterns with ranks
     *
     * @param message The message to check (can include Minecraft color codes)
     * @return true if the message appears to be from a player, false if from server
     */
    fun isPlayerMessage(message: String): Boolean {
        // Strip Minecraft color codes for easier pattern matching
        val stripped = stripColorCodes(message)

        return when {
            // Party chat: "Party > [RANK] PlayerName: message"
            stripped.contains("Party >", ignoreCase = false) -> true

            // Guild chat: "Guild > [RANK] PlayerName: message"
            stripped.contains("Guild >", ignoreCase = false) -> true

            // Officer chat: "Officer >"
            stripped.contains("Officer >", ignoreCase = false) -> true

            // Rank patterns like [MVP++], [VIP], [VIP+], etc.
            // Followed by a colon (indicating player sent message)
            stripped.matches(".*\\[(VIP|MVP|MOD|ADMIN|HELPER|YOUTUBE|PIG).*?].*:.*".toRegex(RegexOption.IGNORE_CASE)) -> true

            // Lobby number pattern like "[505]" followed by rank and colon
            stripped.matches(".*\\[\\d+].*\\[.*?].*:.*".toRegex()) -> true

            // Generic pattern: looks like "SomeName: message" (name followed by colon)
            // But exclude if it's clearly a server announcement (e.g., starts with special chars)
            stripped.matches(".*[a-zA-Z0-9_]{3,16}\\s*:.*".toRegex()) &&
                !stripped.matches("^[^a-zA-Z0-9]*(?:DOUBLE|TRIPLE|RARE|LEGENDARY|SPECIAL|EVENT).*".toRegex(RegexOption.IGNORE_CASE)) -> true

            else -> false
        }
    }

    /**
     * Remove Minecraft color codes from a string.
     *
     * @param text Text potentially containing color codes
     * @return Text with color codes removed
     */
    fun stripColorCodes(text: String): String {
        return text.replace("§[0-9a-fk-or]".toRegex(), "")
    }

    /**
     * Check if a message contains a specific pattern (case-insensitive by default).
     *
     * @param message The message to check
     * @param pattern The pattern to search for
     * @param ignoreCase Whether to ignore case (default: true)
     * @return true if the pattern is found
     */
    fun containsPattern(message: String, pattern: String, ignoreCase: Boolean = true): Boolean {
        val stripped = stripColorCodes(message)
        return stripped.contains(pattern, ignoreCase)
    }

    /**
     * Extract player name from a player message if possible.
     *
     * @param message The player message
     * @return The player name or null if not found
     */
    fun extractPlayerName(message: String): String? {
        val stripped = stripColorCodes(message)

        // Try to match patterns like "[RANK] PlayerName:" or "PlayerName:"
        val patterns = listOf(
            "(?:Party|Guild|Officer) > (?:\\[.*?] )?([a-zA-Z0-9_]+)\\s*:".toRegex(),
            "(?:\\[\\d+] )?(?:\\[.*?] )?([a-zA-Z0-9_]+)\\s*:".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(stripped)
            if (match != null) {
                return match.groupValues.getOrNull(1)
            }
        }

        return null
    }
}

