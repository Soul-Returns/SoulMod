package com.soulreturns

import com.soulreturns.command.SoulCommand
import com.soulreturns.config.ConfigManager
import com.soulreturns.features.DoubleHookResponse
import com.soulreturns.features.itemhighlight.HighlightManager
import com.soulreturns.features.itemhighlight.TooltipHandler
import com.soulreturns.features.mining.dwarvenMines.DonExpresso
import com.soulreturns.util.MessageHandler
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Soul : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("SoulMod")
    lateinit var configManager: ConfigManager

    val version: String by lazy {
        FabricLoader.getInstance().getModContainer("soul")
            .map { it.metadata.version.friendlyString }
            .orElse("Unknown")
    }

	override fun onInitializeClient() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		logger.info("Soul mod initialized!")

        // Access config to trigger initialization
        configManager = ConfigManager()

        // Register message handler before features so they can use it
        MessageHandler.register()

        // Load highlight groups from JSON files
        HighlightManager.loadGroups()

        // Register tooltip handler
        TooltipHandler.register()

        registerCommands()
        registerFeatures()
    }

    fun registerCommands() {
        SoulCommand.register()
    }

    fun registerFeatures() {
        DoubleHookResponse.register()
        DonExpresso.register()
    }

    fun reloadFeatures() {
        // reload features like world rendering here (this is beeing called whenever the config gui is closed)
        HighlightManager.loadGroups()
    }

    fun getLogger(): Logger? {
        return this.logger
    }
}