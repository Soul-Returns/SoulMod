package com.soulreturns.features

import com.soulreturns.Soul
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.MinecraftClient

object DoubleHookResponse {
    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            // Don't process overlay messages (action bar)
            if (overlay) return@register

            // Check if the feature is enabled
            if (!Soul.configManager.config.instance.ChatCategory.wootWoot) return@register

            // Get the message text
            val messageText = message.string

            // Check if the message contains "Double Hook!"
            if (messageText.contains("Double Hook!", ignoreCase = true)) {
                // Send the party chat command
                MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand("pc Woot Woot!")
            }
        }
    }
}

