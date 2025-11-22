package com.soulreturns.features

import com.soulreturns.Soul
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.MinecraftClient

object DoubleHookResponse {
    private var lastProcessed: String? = null

    fun register() {
        // Register for normal chat messages (newer Fabric: 5 parameters)
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handleMessage(message.string)
        }
        // Register for game/system messages (some servers push kill feeds or ability procs here)
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (overlay) return@register // Ignore action bar / overlay lines
            handleMessage(message.string)
        }
        Soul.getLogger()?.info("DoubleHookResponse listeners registered")
    }

    private fun handleMessage(raw: String) {
        try {
            // Feature toggle
            if (!Soul.configManager.config.instance.ChatCategory.wootWoot) return

            val player = MinecraftClient.getInstance().player ?: return

            // Basic deduplication in case both GAME and CHAT fire for same content
            val trimmed = raw.trim()
            if (lastProcessed == trimmed) return else lastProcessed = trimmed

            if (trimmed.contains("Double Hook!", ignoreCase = true)) {
                Soul.getLogger()?.info("Detected 'Double Hook!' in chat, sending party cheer")
                player.networkHandler.sendChatCommand("pc Woot Woot!")
            }
        } catch (t: Throwable) {
            Soul.getLogger()?.error("Error handling chat message for DoubleHookResponse", t)
        }
    }
}
