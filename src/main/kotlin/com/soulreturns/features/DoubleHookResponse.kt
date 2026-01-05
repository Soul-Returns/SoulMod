package com.soulreturns.features

import com.soulreturns.Soul
import com.soulreturns.config.config
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MessageHandler
import net.minecraft.client.MinecraftClient

object DoubleHookResponse {
    fun register() {
        // Register a handler for server messages only
        MessageHandler.onServerMessage { message ->
            handleServerMessage(message)
        }
        Soul.getLogger()?.info("DoubleHookResponse handler registered")
    }

    private fun handleServerMessage(message: String) {
        try {
            // Feature toggle via direct instance chain
            if (!config.fishingCategory.chatSubCategory.doubleHookMessageToggle) return

            val player = MinecraftClient.getInstance().player ?: return

            // Check for Double Hook message
            if (MessageDetector.containsPattern(message, "Double Hook!")) {
                Soul.getLogger()?.info("Detected 'Double Hook!' in server message, sending party cheer")
                player.networkHandler.sendChatCommand("pc " + config.fishingCategory.chatSubCategory.doubleHookMessageText)
            }
        } catch (t: Throwable) {
            Soul.getLogger()?.error("Error handling chat message for DoubleHookResponse", t)
        }
    }
}
