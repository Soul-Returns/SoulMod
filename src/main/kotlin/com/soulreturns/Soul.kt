package com.soulreturns

import com.soulreturns.commands.SoulCommand
import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.data.location.LocationReader
import com.soulreturns.features.DoubleHookResponse
import com.soulreturns.features.dev.DevKeybindHandler
import com.soulreturns.features.farming.FarmingTimer
import com.soulreturns.features.farming.seasoning.HarvestFeastReader
import com.soulreturns.features.farming.seasoning.SeasoningTracker
import com.soulreturns.features.fishing.BobbinSpotter
import com.soulreturns.features.itemhighlight.HighlightManager
import com.soulreturns.features.itemhighlight.TooltipHandler
import com.soulreturns.features.mining.dwarvenMines.DonExpresso
import com.soulreturns.features.mining.mineshaft.LapisCorpseAlert
import com.soulreturns.features.mining.mineshaft.LittlefootAlert
import com.soulreturns.features.mining.mineshaft.MineshaftCorpses
import com.soulreturns.features.mining.mineshaft.MineshaftVisitTracker
import com.soulreturns.features.mining.mineshaft.VanguardCorpseAlert
import com.soulreturns.features.notifications.BackendNotificationCenter
import com.soulreturns.features.notifications.ChatNotifications
import com.soulreturns.features.party.PartyManager
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.platform.http.PresenceService
import com.soulreturns.platform.realtime.RealtimeClient
import com.soulreturns.platform.sync.SyncEngine
import com.soulreturns.platform.sync.SyncKind
import com.soulreturns.platform.sync.SyncedArtifact
import com.soulreturns.render.RoundRectRenderer
import com.soulreturns.stats.PersistentStats
import com.soulreturns.ui.hud.BackendNotificationHud
import com.soulreturns.ui.hud.BobbinHud
import com.soulreturns.ui.hud.LegionHud
import com.soulreturns.ui.hud.MineshaftCorpsesHud
import com.soulreturns.ui.hud.PartyHud
import com.soulreturns.ui.hud.SeasoningHud
import com.soulreturns.update.UpdateChecker
import com.soulreturns.update.UpdateModal
import com.soulreturns.update.Updater
import com.soulreturns.util.MessageHandler
import com.soulreturns.util.SoulFileLog
import com.soulreturns.util.SoulLogger
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.TitleScreen
import java.io.File

object Soul : ClientModInitializer {
    private val logger = SoulLogger("Soul")

    val version: String by lazy {
        FabricLoader.getInstance().getModContainer("soul")
            .map { it.metadata.version.friendlyString }
            .orElse("Unknown")
    }

    override fun onInitializeClient() {
        // Open the Soul-only log file first so we capture every startup line. Init is safe
        // before config is loaded — the toggle defaults to "on" until config tells us otherwise.
        SoulFileLog.init()

        logger.info("Soul mod initialized!")

        // Migrate any legacy config, then load the owo-config wrapper.
        SoulConfigHolder.init()

        // One-shot startup line — always present (not debug-gated) so support reports
        // immediately show which backend the mod is talking to.
        logger.info("Backend: ${com.soulreturns.platform.http.SoulHttp.backendBaseUrl()}")

        // Register message handler before features so they can use it.
        MessageHandler.register()

        // Load persisted stats (tracked counters like seasonings) before features may read them.
        PersistentStats.init()

        // Start polling Hypixel SkyBlock location (publishes AreaChanged/SublocationChanged events).
        LocationReader.register()

        // Start polling SkyBlock-presence (sidebar title == "SKYBLOCK"; publishes OnSkyblockChanged).
        com.soulreturns.data.skyblock.SkyblockReader.register()

        // Start polling Hypixel SkyBlock profile (publishes ProfileChanged — drives PersistentStats keying).
        com.soulreturns.data.profile.ProfileReader.register()

        // Load persisted auth token so we don't re-authenticate on every launch.
        com.soulreturns.platform.http.BackendAuth.loadCached()

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

        // Register sync artifacts AFTER all subsystems have loaded their local state. The
        // engine reconciles in the background; if a remote pull is fresher than local, the
        // onAfterPull hook reloads the in-memory representation for that subsystem.
        registerSyncArtifacts(configDir)
        SyncEngine.start(masterEnabled = { com.soulreturns.config.cfg.sync.enabled() })

        // Realtime: receive admin notifications + sync-invalidate signals over Mercure SSE.
        // The notification center must be wired before RealtimeClient.start so it doesn't
        // miss an event that arrives between connection-open and Events.subscribe.
        BackendNotificationCenter.register()
        BackendNotificationHud.register()
        RealtimeClient.start(enabled = { com.soulreturns.config.cfg.sync.enabled() })
    }

    private fun registerSyncArtifacts(configDir: File) {
        SyncEngine.register(
            SyncedArtifact(
                kind = SyncKind.CONFIG,
                file = File(configDir, "soul/config.json5"),
                enabled = { com.soulreturns.config.cfg.sync.enabled() && com.soulreturns.config.cfg.sync.syncConfig() },
                onAfterPull = { com.soulreturns.config.SoulConfigHolder.reload() },
                defaultsJson = { com.soulreturns.config.SoulConfigHolder.defaultsJson() }
            )
        )
        val guiLayoutFile = GuiLayoutManager.layoutFile()
        if (guiLayoutFile != null) {
            SyncEngine.register(
                SyncedArtifact(
                    kind = SyncKind.GUI_LAYOUT,
                    file = guiLayoutFile,
                    enabled = {
                        com.soulreturns.config.cfg.sync.enabled() &&
                            com.soulreturns.config.cfg.sync.syncGuiLayout()
                    },
                    onAfterPull = { GuiLayoutManager.reload() }
                )
            )
        }
        SyncEngine.register(
            SyncedArtifact(
                kind = SyncKind.STATS,
                file = PersistentStats.statsFile(),
                enabled = { com.soulreturns.config.cfg.sync.enabled() && com.soulreturns.config.cfg.sync.syncStats() },
                onAfterPull = { PersistentStats.reload() }
            )
        )
    }

    fun registerCommands() {
        SoulCommand.register()
        com.soulreturns.profileviewer.SpvCommand.register()
    }

    fun registerFeatures() {
        DoubleHookResponse.register()
        DonExpresso.register()
        // Mineshaft — state object must register before consumers (LapisCorpseAlert, MineshaftCorpsesHud)
        // so the per-tick tab-list scan is fresh when they read it.
        MineshaftCorpses.register()
        LapisCorpseAlert.register()
        VanguardCorpseAlert.register()
        LittlefootAlert.register()
        MineshaftVisitTracker.register()
        MineshaftCorpsesHud.register()
        LegionHud.register()
        // Fishing — spotter must register before HUD so per-tick count is fresh.
        BobbinSpotter.register()
        BobbinHud.register()

        // Party tracking and HUD overlay
        PartyManager.register()
        PartyHud.register()

        // Dev tooling: global keybinds for clipboard data dumps
        DevKeybindHandler.register()

        // Farming — split across reader / state-orchestrator / HUD
        FarmingTimer.register()
        HarvestFeastReader.register()
        SeasoningTracker.register()
        SeasoningHud.register()

        // Notifications
        ChatNotifications.register()
    }

    fun reloadFeatures() {
        // reload features like world rendering here
        HighlightManager.loadGroups()
    }

    fun getLogger(): SoulLogger = this.logger
}
