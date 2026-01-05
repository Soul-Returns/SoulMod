package com.soulreturns.util

import com.soulreturns.Soul
import org.slf4j.LoggerFactory

object DebugLogger {
    private val logger = LoggerFactory.getLogger("SoulMod")

    private fun isDebugEnabled(): Boolean {
        return try {
            val config = Soul.configManager.config.instance
            config.debugCategory.debugMode
        } catch (e: Exception) {
            false // Config not initialized yet
        }
    }

    fun logConfigChange(message: String) {
        try {
            val config = Soul.configManager.config.instance
            if (config.debugCategory.debugMode && 
                config.debugCategory.loggingSubCategory.logConfigChanges) {
                logger.info("[Config] $message")
            }
        } catch (e: Exception) {
            // Config not initialized yet, skip logging
        }
    }

    fun logWidgetInteraction(message: String) {
        try {
            val config = Soul.configManager.config.instance
            if (config.debugCategory.debugMode && 
                config.debugCategory.loggingSubCategory.logWidgetInteractions) {
                logger.info("[Widget] $message")
            }
        } catch (e: Exception) {
            // Config not initialized yet, skip logging
        }
    }

    fun logMessageHandler(message: String) {
        try {
            val config = Soul.configManager.config.instance
            if (config.debugCategory.debugMode && 
                config.debugCategory.loggingSubCategory.logMessageHandler) {
                logger.info("[Message] $message")
            }
        } catch (e: Exception) {
            // Config not initialized yet, skip logging
        }
    }

    fun logFeatureEvent(message: String) {
        try {
            val config = Soul.configManager.config.instance
            if (config.debugCategory.debugMode && 
                config.debugCategory.loggingSubCategory.logFeatureEvents) {
                logger.info("[Feature] $message")
            }
        } catch (e: Exception) {
            // Config not initialized yet, skip logging
        }
    }
}
