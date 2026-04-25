package com.soulreturns.util

import com.soulreturns.config.cfg
import com.soulreturns.util.SoulLogger

object DebugLogger {
    private val logger = SoulLogger("Soul")

    private inline fun log(prefix: String, message: String, predicate: () -> Boolean) {
        try {
            if (cfg.debug.debugMode() && predicate()) {
                logger.info("[$prefix] $message")
            }
        } catch (_: Exception) { /* config not ready yet */ }
    }

    fun logConfigChange(message: String)     = log("Config",      message) { cfg.debug.logging.logConfigChanges() }
    fun logWidgetInteraction(message: String) = log("Widget",     message) { cfg.debug.logging.logWidgetInteractions() }
    fun logMessageHandler(message: String)   = log("Message",     message) { cfg.debug.logging.logMessageHandler() }
    fun logFeatureEvent(message: String)     = log("Feature",     message) { cfg.debug.logging.logFeatureEvents() }
    fun logGuiLayout(message: String)        = log("GuiLayout",   message) { cfg.debug.logging.logGuiLayout() }
    fun logCommandExecution(commandInput: String) = log("Command", commandInput) { cfg.debug.logging.logCommandsAndMessages() }
    fun logSentMessage(message: String)      = log("SentMessage", message) { cfg.debug.logging.logCommandsAndMessages() }
    fun logChatInput(input: String)          = log("ChatInput",   input)   { cfg.debug.logging.logCommandsAndMessages() }
}