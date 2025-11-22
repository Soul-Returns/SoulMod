package com.soulreturns

import com.soulreturns.command.SoulCommand
import com.soulreturns.command.TestDoubleHookCommand
import com.soulreturns.config.ConfigManager
import com.soulreturns.features.DoubleHookResponse
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
		logger.info("Soul mod initialized!")

        // Access config to trigger initialization
        configManager = ConfigManager()

        registerCommands()
        registerFeatures()
    }

    fun registerCommands() {
        SoulCommand.register()
        TestDoubleHookCommand.register()
    }

    fun registerFeatures() {
        DoubleHookResponse.register()
    }

    fun reloadFeatures() {
        // reload features like world rendering here (this is beeing called whenever the config gui is closed)
    }

    fun getLogger(): Logger? {
        return this.logger
    }
}