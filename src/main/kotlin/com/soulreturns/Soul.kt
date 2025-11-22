package com.soulreturns

import com.soulreturns.command.SoulCommand
import com.soulreturns.config.ConfigManager
import com.soulreturns.features.ReplaceLava
import net.fabricmc.api.ClientModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Soul : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("soul")
    lateinit var configManager: ConfigManager


	override fun onInitializeClient() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		logger.info("Crime stinkt!!!")

        // Access config to trigger initialization
        configManager = ConfigManager()

        SoulCommand.register()
    }

    fun loadFeatures() {
        ReplaceLava.replaceLava()
    }

    fun getLogger(): Logger? {
        return this.logger
    }
}