package com.soulreturns.features

import com.soulreturns.Soul
import com.soulreturns.config.cfg
import com.soulreturns.util.DebugLogger
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
            if (!cfg.fishing.chat.doubleHookMessageToggle()) return

            val player = MinecraftClient.getInstance().player ?: return

            // Check for Double Hook message
            if (MessageDetector.containsPattern(message, "Double Hook!")) {
                DebugLogger.logFeatureEvent("Double Hook detected, sending: ${cfg.fishing.chat.doubleHookMessageText()}")
                Soul.getLogger()?.info("Detected 'Double Hook!' in server message, sending party cheer")
                player.networkHandler.sendChatCommand("pc " + cfg.fishing.chat.doubleHookMessageText())
            }
        } catch (t: Throwable) {
            Soul.getLogger()?.error("Error handling chat message for DoubleHookResponse", t)
        }
    }
}
