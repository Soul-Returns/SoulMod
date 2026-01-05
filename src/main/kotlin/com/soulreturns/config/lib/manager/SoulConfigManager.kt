package com.soulreturns.config.lib.manager

import com.google.gson.GsonBuilder
import com.soulreturns.config.lib.model.ConfigStructure
import com.soulreturns.config.lib.parser.ConfigParser
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
                FileReader(configFile).use { reader ->
                    gson.fromJson(reader, configClass) ?: factory()
                }
            } catch (e: Exception) {
                println("Failed to load config from ${configFile.path}, creating new: ${e.message}")
                factory()
            }
        } else {
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
            FileWriter(configFile).use { writer ->
                gson.toJson(instance, writer)
            }
        } catch (e: Exception) {
            println("Failed to save config to ${configFile.path}: ${e.message}")
        }
    }
    
    /**
     * Reloads the config from disk
     */
    fun reload() {
        if (configFile.exists()) {
            try {
                FileReader(configFile).use { reader ->
                    instance = gson.fromJson(reader, configClass) ?: factory()
                }
            } catch (e: Exception) {
                println("Failed to reload config from ${configFile.path}: ${e.message}")
            }
        }
    }
}
