package com.soulreturns

import com.soulreturns.command.SoulCommand
import com.soulreturns.render.RoundRectRenderer
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry
import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.features.DoubleHookResponse
import com.soulreturns.features.LegionCounter
import com.soulreturns.features.BobbinTimeCounter
import com.soulreturns.features.itemhighlight.HighlightManager
import com.soulreturns.features.itemhighlight.TooltipHandler
import com.soulreturns.features.mining.dwarvenMines.DonExpresso
import com.soulreturns.features.party.PartyHudOverlay
import com.soulreturns.features.party.PartyManager
import com.soulreturns.api.PresenceService
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.update.UpdateChecker
import com.soulreturns.update.UpdateModal
import com.soulreturns.update.Updater
import com.soulreturns.util.MessageHandler
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screen.TitleScreen
import com.soulreturns.util.SoulLogger
import java.io.File

object Soul : ClientModInitializer {
    private val logger = SoulLogger("Soul")

    val version: String by lazy {
        FabricLoader.getInstance().getModContainer("soul")
            .map { it.metadata.version.friendlyString }
            .orElse("Unknown")
    }

    override fun onInitializeClient() {
        logger.info("Soul mod initialized!")

        // Migrate any legacy config, then load the owo-config wrapper.
        SoulConfigHolder.init()

        // Register message handler before features so they can use it.
        MessageHandler.register()

        // Load persisted auth token so we don't re-authenticate on every launch.
        com.soulreturns.api.BackendAuth.loadCached()

        // Start presence ping so the backend knows who is online.
        PresenceService.start()

        // Clean up any old mod JARs left over from a previous auto-update (handles Windows file locks).
        UpdateChecker.checkAsync()
        Updater.cleanupPendingDeletes()
        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (screen !is TitleScreen) return@register
            val update = UpdateChecker.latestUpdate ?: return@register
            if (UpdateModal.dismissed) return@register
            client.execute { client.setScreen(UpdateModal(update)) }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            val update = UpdateChecker.latestUpdate ?: return@register
            if (UpdateModal.dismissed) return@register
            client.execute { client.setScreen(UpdateModal(update)) }
        }

        // Load highlight groups from JSON files.
        HighlightManager.loadGroups()

        // Register tooltip handler.
        TooltipHandler.register()

        // Configure GUI layout persistence location (under config/soul/gui_layout.json).
        val configDir = FabricLoader.getInstance().configDir.toFile()
        val guiLayoutFile = File(configDir, "soul/gui_layout.json")
        GuiLayoutManager.configure(guiLayoutFile)

        // Register round-rect PIP renderer for anti-aliased rounded corners.
        SpecialGuiElementRegistry.register { context -> RoundRectRenderer(context.vertexConsumers()) }

        registerCommands()
        registerFeatures()

        // Load existing GUI layout, or persist the current defaults if none exist yet.
        GuiLayoutManager.loadOrInitialize()
    }

    fun registerCommands() {
        SoulCommand.register()
        com.soulreturns.profileviewer.SpvCommand.register()
    }

    fun registerFeatures() {
        DoubleHookResponse.register()
        DonExpresso.register()
        LegionCounter.register()
        BobbinTimeCounter.register()

        // Party tracking and HUD overlay
        PartyManager.register()
        PartyHudOverlay.register()
    }

    fun reloadFeatures() {
        // reload features like world rendering here
        HighlightManager.loadGroups()
    }

    fun getLogger(): SoulLogger = this.logger
}
