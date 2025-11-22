package com.soulreturns.features

import com.soulreturns.Soul
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MessageHandler

/**
 * Example feature demonstrating how to use the MessageHandler system.
 *
 * This example shows:
 * 1. How to register callbacks for server messages
 * 2. How to register callbacks for player messages
 * 3. How to use MessageDetector utilities
 */
object ExampleMessageFeature {
    fun register() {
        // Register a handler for server messages
        MessageHandler.onServerMessage { message ->
            handleServerMessage(message)
        }

        // Register a handler for player messages
        MessageHandler.onPlayerMessage { message ->
            handlePlayerMessage(message)
        }

        Soul.getLogger()?.info("ExampleMessageFeature handlers registered")
    }

    private fun handleServerMessage(message: String) {
        try {
            // Example: Detect a specific server announcement
            if (MessageDetector.containsPattern(message, "Rare Drop!")) {
                Soul.getLogger()?.info("Rare drop detected from server!")
                // You could trigger a notification, play a sound, etc.
            }

            // Example: Check for multiple patterns
            if (MessageDetector.containsPattern(message, "Event Starting", ignoreCase = true)) {
                Soul.getLogger()?.info("Server event is starting!")
            }
        } catch (t: Throwable) {
            Soul.getLogger()?.error("Error handling server message in ExampleMessageFeature", t)
        }
    }

    private fun handlePlayerMessage(message: String) {
        try {
            // Example: Extract player name from message
            val playerName = MessageDetector.extractPlayerName(message)
            if (playerName != null) {
                Soul.getLogger()?.debug("Message from player: $playerName")
            }

            // Example: Check if a specific player is talking
            if (MessageDetector.containsPattern(message, "specificPlayerName", ignoreCase = false)) {
                Soul.getLogger()?.info("Detected message from specific player!")
            }

            // Example: Detect party chat messages
            val stripped = MessageDetector.stripColorCodes(message)
            if (stripped.contains("Party >")) {
                Soul.getLogger()?.debug("Party chat message detected")
                // You could parse party chat, track party members, etc.
            }
        } catch (t: Throwable) {
            Soul.getLogger()?.error("Error handling player message in ExampleMessageFeature", t)
        }
    }
}

