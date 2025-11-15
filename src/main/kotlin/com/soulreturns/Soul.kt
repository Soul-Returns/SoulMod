package com.soulreturns

import com.soulreturns.command.SoulCommand
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.MainConfig
import io.github.notenoughupdates.moulconfig.managed.ManagedConfig
import io.github.notenoughupdates.moulconfig.processor.MoulConfigProcessor
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
		logger.info("Hello Fabric world!")

        // Access config to trigger initialization
        configManager = ConfigManager()

        SoulCommand.register()
	}

    fun getLogger(): Logger? {
        return this.logger
    }
}