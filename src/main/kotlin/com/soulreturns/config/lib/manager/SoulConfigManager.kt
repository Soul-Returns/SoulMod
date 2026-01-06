package com.soulreturns.config.lib.manager

import com.google.gson.GsonBuilder
import com.soulreturns.Soul
import com.soulreturns.config.lib.model.ConfigStructure
import com.soulreturns.config.lib.parser.ConfigParser
import com.soulreturns.util.DebugLogger
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Manages configuration persistence and instance lifecycle
 */
class SoulConfigManager<T : Any>(
    private val configFile: File,
    private val configClass: Class<T>,
    private val factory: () -> T
) {
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .create()
    
    var instance: T private set
    val structure: ConfigStructure
    
    init {
        // Ensure config directory exists
        configFile.parentFile?.mkdirs()
        
        // Load or create config instance
        instance = if (configFile.exists()) {
            try {
                DebugLogger.logConfigChange("Loading config from ${configFile.path}")
                FileReader(configFile).use { reader ->
                    val loaded = gson.fromJson(reader, configClass)
                    if (loaded == null) {
                        Soul.getLogger()?.warn("WARNING: Loaded config was null, using defaults")
                        factory()
                    } else {
                        Soul.getLogger()?.info("Successfully loaded config from ${configFile.path}")
                        loaded
                    }
                }
            } catch (e: Exception) {
                Soul.getLogger()?.error("ERROR: Failed to load config from ${configFile.path}: ${e.message}")
                e.printStackTrace()
                factory()
            }
        } else {
            DebugLogger.logConfigChange("Creating new config file at ${configFile.path}")
            Soul.getLogger()?.info("Config file does not exist, creating new at ${configFile.path}")
            factory()
        }
        
        // Parse the structure
        structure = ConfigParser.parse(configClass)
        
        // Save initial config if it doesn't exist
        if (!configFile.exists()) {
            save()
        }
    }
    
    /**
     * Saves the current config instance to disk
     */
    fun save() {
        try {
            DebugLogger.logConfigChange("Saving config to ${configFile.path}")
            FileWriter(configFile).use { writer ->
                gson.toJson(instance, writer)
            }
            DebugLogger.logConfigChange("Config saved successfully")
        } catch (e: Exception) {
            Soul.getLogger()?.error("Failed to save config to ${configFile.path}: ${e.message}")
        }
    }
    
    /**
     * Reloads the config from disk
     */
    fun reload() {
        if (configFile.exists()) {
            try {
                DebugLogger.logConfigChange("Reloading config from ${configFile.path}")
                FileReader(configFile).use { reader ->
                    instance = gson.fromJson(reader, configClass) ?: factory()
                }
                DebugLogger.logConfigChange("Config reloaded successfully")
            } catch (e: Exception) {
                Soul.getLogger()?.error("Failed to reload config from ${configFile.path}: ${e.message}")
            }
        }
    }
}
