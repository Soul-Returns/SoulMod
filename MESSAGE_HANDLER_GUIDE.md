# MessageHandler System

This document explains how to use the MessageHandler system for detecting and responding to Minecraft chat messages in the Soul mod.

## Overview

The MessageHandler system provides a clean, reusable way to:
- Distinguish between server messages and player messages
- Register callbacks for specific message types
- Detect patterns in messages
- Extract information from messages

## Architecture

The system consists of two main components:

### 1. MessageHandler
Central handler that manages message callbacks and event registration.

### 2. MessageDetector
Utility functions for message pattern detection and information extraction.

## Usage Guide

### Basic Setup

The MessageHandler is automatically registered in `Soul.kt` during mod initialization. You don't need to register it manually.

### Creating a Feature with Message Detection

#### Example 1: Server Message Detection

```kotlin
object MyFeature {
    fun register() {
        MessageHandler.onServerMessage { message ->
            handleServerMessage(message)
        }
    }

    private fun handleServerMessage(message: String) {
        // Check for a specific pattern
        if (MessageDetector.containsPattern(message, "Double Hook!")) {
            // Do something when pattern is detected
            Soul.getLogger()?.info("Double Hook detected!")
        }
    }
}
```

#### Example 2: Player Message Detection

```kotlin
object MyFeature {
    fun register() {
        MessageHandler.onPlayerMessage { message ->
            handlePlayerMessage(message)
        }
    }

    private fun handlePlayerMessage(message: String) {
        // Extract player name
        val playerName = MessageDetector.extractPlayerName(message)
        if (playerName != null) {
            Soul.getLogger()?.info("Message from: $playerName")
        }
    }
}
```

#### Example 3: Both Server and Player Messages

```kotlin
object MyFeature {
    fun register() {
        // Register for both types
        MessageHandler.onServerMessage { message ->
            handleServerMessage(message)
        }
        
        MessageHandler.onPlayerMessage { message ->
            handlePlayerMessage(message)
        }
    }

    private fun handleServerMessage(message: String) {
        // Handle server messages
    }

    private fun handlePlayerMessage(message: String) {
        // Handle player messages
    }
}
```

## MessageDetector Utilities

### containsPattern(message, pattern, ignoreCase)
Check if a message contains a specific pattern.

```kotlin
// Case-insensitive by default
if (MessageDetector.containsPattern(message, "Double Hook!")) {
    // Pattern found
}

// Case-sensitive
if (MessageDetector.containsPattern(message, "ExactMatch", ignoreCase = false)) {
    // Exact pattern found
}
```

### stripColorCodes(text)
Remove Minecraft color codes from text.

```kotlin
val cleaned = MessageDetector.stripColorCodes("§aGreen §bBlue §cRed")
// Result: "Green Blue Red"
```

### extractPlayerName(message)
Extract the player name from a player message.

```kotlin
val name = MessageDetector.extractPlayerName("Party > [MVP++] PlayerName: Hello!")
// Result: "PlayerName"
```

### isPlayerMessage(message)
Check if a message is from a player (this is used internally by MessageHandler).

```kotlin
if (MessageDetector.isPlayerMessage(message)) {
    // This is a player message
}
```

## Message Type Detection

The system automatically classifies messages as either "server" or "player" based on patterns:

### Player Messages Include:
- Party chat: `Party > [RANK] PlayerName: message`
- Guild chat: `Guild > [RANK] PlayerName: message`
- Officer chat: `Officer > ...`
- Ranked players: `[MVP++] PlayerName: message`
- Lobby messages: `[505] [VIP] PlayerName: message`
- General chat: `PlayerName: message`

### Server Messages Include:
- System announcements
- Game events (Double Hook, Rare Drop, etc.)
- Server notifications
- Kill feeds
- Ability activations
- Quest completions
- And any message that doesn't match player patterns

## Best Practices

### 1. Use Feature Toggle
Always check your config toggle before processing:

```kotlin
private fun handleServerMessage(message: String) {
    if (!Soul.configManager.config.instance.MyCategory.myFeatureToggle) return
    
    // Process message
}
```

### 2. Error Handling
Wrap your logic in try-catch:

```kotlin
private fun handleServerMessage(message: String) {
    try {
        // Your logic here
    } catch (t: Throwable) {
        Soul.getLogger()?.error("Error in MyFeature", t)
    }
}
```

### 3. Null Checks
Always check for null when accessing Minecraft client:

```kotlin
val player = MinecraftClient.getInstance().player ?: return
```

### 4. Logging
Use appropriate log levels:

```kotlin
Soul.getLogger()?.debug("Verbose debug info")  // Frequent events
Soul.getLogger()?.info("Important event")      // Important events
Soul.getLogger()?.warn("Warning condition")    // Warnings
Soul.getLogger()?.error("Error occurred", t)   // Errors with stacktrace
```

## Registering Your Feature

Add your feature to `Soul.kt`:

```kotlin
fun registerFeatures() {
    DoubleHookResponse.register()
    MyFeature.register()  // Add your feature here
}
```

## Example: Complete Feature

```kotlin
package com.soulreturns.features

import com.soulreturns.Soul
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MessageHandler
import net.minecraft.client.MinecraftClient

object KillFeedTracker {
    private var killCount = 0

    fun register() {
        MessageHandler.onServerMessage { message ->
            handleServerMessage(message)
        }
        Soul.getLogger()?.info("KillFeedTracker registered")
    }

    private fun handleServerMessage(message: String) {
        try {
            // Check config toggle
            if (!Soul.configManager.config.instance.CombatCategory.trackKills) return

            // Check for kill message
            if (MessageDetector.containsPattern(message, "You killed")) {
                killCount++
                Soul.getLogger()?.info("Kill count: $killCount")
                
                // Show in chat every 10 kills
                if (killCount % 10 == 0) {
                    val player = MinecraftClient.getInstance().player ?: return
                    player.sendMessage(
                        Text.literal("§aYou've reached $killCount kills!")
                    )
                }
            }
        } catch (t: Throwable) {
            Soul.getLogger()?.error("Error in KillFeedTracker", t)
        }
    }

    fun reset() {
        killCount = 0
    }
}
```

## Advanced Usage

### Multiple Handlers
You can register multiple handlers for the same message type:

```kotlin
MessageHandler.onServerMessage { message ->
    checkForDoubleHook(message)
}

MessageHandler.onServerMessage { message ->
    checkForRareDrop(message)
}
// Both handlers will be called for each server message
```

### Clearing Handlers (Testing)
For testing or dynamic reloading:

```kotlin
MessageHandler.clearHandlers()
// Re-register all features
registerFeatures()
```

## Troubleshooting

### Messages Not Detected
1. Check if MessageHandler is registered in Soul.kt
2. Verify your pattern matching (case sensitivity)
3. Use stripColorCodes if color codes interfere
4. Add debug logging to see raw messages

### Wrong Message Type
If server messages are classified as player messages or vice versa:
1. Check the message pattern in isPlayerMessage()
2. You may need to update the detection logic for new patterns

### Performance Concerns
The system is designed to be efficient:
- Handlers are called sequentially
- Deduplication prevents duplicate processing
- Use early returns to skip unnecessary work
- Consider config toggles to disable features

