package com.soulreturns

import com.soulreturns.api.HypixelModApi
import com.soulreturns.api.HypixelNetwork
import com.soulreturns.api.LocationApi
import com.soulreturns.commands.SoulCommand
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.data.drops.DropCatalogClient
import com.soulreturns.data.fishing.SeaCreatureCatalog
import com.soulreturns.data.fishing.SeaCreatureCatalogClient
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.prices.PriceCatalogClient
import com.soulreturns.data.prices.MarketValues
import com.soulreturns.features.prices.PriceTooltip
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.data.skyblock.SkyblockStateReader
import com.soulreturns.data.skyblock.TabWidgets
import com.soulreturns.features.combo.ComboDurations
import com.soulreturns.features.combo.ComboHud
import com.soulreturns.features.combo.ComboKillCounter
import com.soulreturns.features.combo.ComboTracker
import com.soulreturns.features.dev.DevKeybindHandler
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.dungeons.map.DungeonDebugHud
import com.soulreturns.features.dungeons.map.DungeonMapHud
import com.soulreturns.features.dungeons.map.DungeonMapItemScanner
import com.soulreturns.features.dungeons.map.CeilingHashMatcher
import com.soulreturns.features.dungeons.sync.DevonianChannel
import com.soulreturns.features.dungeons.sync.SecretSyncHandler
import com.soulreturns.features.dungeons.sync.SoulBackendChannel
import com.soulreturns.features.dungeons.terminals.TerminalSolver
import com.soulreturns.features.dungeons.leap.LeapMenu
import com.soulreturns.features.dungeons.devices.DeviceSolvers
import com.soulreturns.features.dungeons.boss.DragonCountdown
import com.soulreturns.features.dungeons.boss.F7Boss
import com.soulreturns.features.dungeons.boss.SectionWarning
import com.soulreturns.features.dungeons.boss.CrosshairHud
import com.soulreturns.features.dungeons.roles.ArcherRoles
import com.soulreturns.features.dungeons.roles.ArcherRoleDisplay
import com.soulreturns.platform.ServerTime
import com.soulreturns.platform.devaccess.DevAccess
import com.soulreturns.ui.AutoUiScale
import com.soulreturns.ui.hud.SoulHud
import com.soulreturns.ui.hud.alert.AlertHud
import com.soulreturns.ui.hud.alert.Alerts
import com.soulreturns.ui.hud.movable.MovableHudRegistry
import com.soulreturns.ui.hud.tracker.TrackerHudHost
import com.soulreturns.features.fishing.BobbinSpotter
import com.soulreturns.features.fishing.DoubleHookResponse
import com.soulreturns.features.fishing.FishingHud
import com.soulreturns.features.fishing.FishingTimer
import com.soulreturns.features.fishing.FishingTracker
import com.soulreturns.features.fishing.FishingXpTracker
import com.soulreturns.features.fishing.FishingVisibility
import com.soulreturns.features.mining.commissions.Commissions
import com.soulreturns.features.mining.commissions.CommissionsHud
import com.soulreturns.features.mining.mineshaft.MineshaftCorpsesHud
import com.soulreturns.features.darkui.DarkUi
import com.soulreturns.features.inventory.EquipmentDisplay
import com.soulreturns.features.inventory.InventoryInfo
import com.soulreturns.features.inventory.InventoryCleanup
import com.soulreturns.features.inventory.KeepCursor
import com.soulreturns.features.inventorybuttons.InventoryButtons
import com.soulreturns.features.keybinds.CommandKeybinds
import com.soulreturns.features.mining.mineshaft.MineshaftTick
import com.soulreturns.features.mining.metaldetector.MetalDetector
import com.soulreturns.features.mining.metaldetector.MetalDetectorHud
import com.soulreturns.features.mining.metaldetector.MetalDetectorLoot
import com.soulreturns.features.mining.nucleus.BalHud
import com.soulreturns.features.mining.metaldetector.MetalDetectorToolsHud
import com.soulreturns.features.mining.nucleus.BalState
import com.soulreturns.features.mining.nucleus.CrystalChecklistHud
import com.soulreturns.features.mining.nucleus.NucleusChatCompactor
import com.soulreturns.features.mining.nucleus.NucleusHud
import com.soulreturns.features.mining.nucleus.NucleusRuns
import com.soulreturns.features.notifications.BackendNotificationCenter
import com.soulreturns.features.sacks.SackMenus
import com.soulreturns.features.sacks.SackState
import com.soulreturns.features.sacks.SackTrackerHud
import com.soulreturns.features.profit.dragon.DragonDeathDetector
import com.soulreturns.features.profit.dragon.DragonLegionAnnouncer
import com.soulreturns.features.profit.dragon.DragonLootScanner
import com.soulreturns.features.profit.dragon.DragonProfitTracker
import com.soulreturns.features.profit.dragon.DragonTick
import com.soulreturns.features.profit.dragon.EyePlacementTracker
import com.soulreturns.features.profit.dragon.gui.DragonProfitHud
import com.soulreturns.features.notifications.NotificationHud
import com.soulreturns.features.party.PartyManager
import com.soulreturns.features.profileviewer.SpvCommand
import com.soulreturns.features.profileviewer.gui.ProfileViewerScreen
import com.soulreturns.features.qol.HideEffects
import com.soulreturns.features.qol.HideFoliage
import com.soulreturns.features.qol.HideHeldItemTooltip
import com.soulreturns.features.qol.HideStuckDianaMobs
import com.soulreturns.features.qol.ImplosionHider
import com.soulreturns.features.qol.NoFrontCamera
import com.soulreturns.features.qol.PauseMenuButtons
import com.soulreturns.features.qol.PreventAbilityItemPlace
import com.soulreturns.features.qol.ToggleSprint
import com.soulreturns.features.qol.ToggleSprintHud
import com.soulreturns.features.mobhealth.MobHealthHud
import com.soulreturns.features.mobhealth.MobHealthTracker
import com.soulreturns.features.pets.PetHud
import com.soulreturns.features.pets.PetState
import com.soulreturns.features.actionbar.ActionBarHud
import com.soulreturns.features.scoreboard.Scoreboard
import com.soulreturns.features.scoreboard.ScoreboardHud
import com.soulreturns.platform.catalog.CatalogRegistry
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendAuth
import com.soulreturns.platform.presence.PresenceService
import com.soulreturns.platform.realtime.RealtimeClient
import com.soulreturns.platform.updater.UpdateService
import com.soulreturns.features.updater.UpdateNotices
import com.soulreturns.features.updater.UpdateTitlePrompt
import com.soulreturns.features.updater.UpdateToastHud
import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.PlayerDataset
import com.soulreturns.stats.TrackerSessions
import com.soulreturns.util.MessageHandler
import com.soulreturns.sound.SoundLibrary
import com.soulreturns.util.SoulLogger
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader

/**
 * Client entrypoint. Init order matters:
 *  1. [ConfigManager.init] — `cfg` is unusable before this; features + the logger read it.
 *  2. [MessageHandler.register] — must precede features that subscribe to chat.
 *  3. commands, then features (producer-before-consumer: [PartyManager] before
 *     [DoubleHookResponse], which gates its `/pc` send on party membership).
 */
object Soul : ClientModInitializer {
    private val logger = SoulLogger("Soul")

    val version: String by lazy {
        FabricLoader.getInstance().getModContainer("soul")
            .map { it.metadata.version.friendlyString }
            .orElse("Unknown")
    }

    override fun onInitializeClient() {
        logger.info("Soul initializing (26.2 baseline)")

        ConfigManager.init()
        MsdfFonts.init()
        AutoUiScale.register()
        SoulScope.register()
        // Before PersistentStats: its alive stamp on CLIENT_STOPPING must land before the stats
        // flush, and trackers read freshStart at their own register().
        TrackerSessions.register()
        // Before any feature claims a namespace, and before SoulScope's own CLIENT_STOPPING handler
        // could beat the stats flush to the punch — see PersistentStats.flushOnShutdown.
        PersistentStats.register()
        BackendAuth.loadCached()
        // Before commands and screens read it; refreshes ride on BackendAuth's handshake event.
        DevAccess.register()
        // Early: removes older Soul jars on the first start of a new version and records the update.
        UpdateService.register()
        UpdateNotices.register()
        UpdateTitlePrompt.register()
        MessageHandler.register()

        registerCommands()
        registerFeatures()

        logger.info("Soul initialized (v$version)")
    }

    private fun registerCommands() {
        SoulCommand.register()
        // Also registered as `/soul spv` inside SoulCommand's tree; this is the top-level alias.
        SpvCommand.register()
    }

    private fun registerFeatures() {
        // Before HypixelNetwork: its JOIN flush writes the last connection's data before an Alpha address can switch datasets.
        PlayerDataset.register()
        // Then: its JOIN handler must latch the Alpha network before any other JOIN handler sends.
        HypixelNetwork.register()
        HypixelModApi.register()
        LocationApi.register()
        // After LocationApi: it turns the location packet into `inSkyblock`, which the reader reads.
        SkyblockStateReader.register()
        TabWidgets.register()
        PartyManager.register()
        // Backend catalogs declare themselves, then CatalogRegistry loads them all from disk, wires
        // the one invalidate subscription and the staleness tick, and kicks off the first refresh.
        // Prices are one of them: they back the profit panel and the per-kill announcement, and
        // they refresh unconditionally — see PriceCache for why gating that on being in SkyBlock was
        // a mistake worth not repeating.
        // Declare before init: a catalog registered afterwards is initialised on its own, which works
        // but costs a second pass for no reason.
        SeaCreatureCatalogClient.init()
        DropCatalogClient.init()
        ItemCatalogClient.init()
        com.soulreturns.data.items.PackModels.register()
        PriceCatalogClient.init()
        com.soulreturns.data.recipes.RecipeCatalogClient.init()
        com.soulreturns.data.hypixel.HotmTreeClient.init()
        com.soulreturns.data.sacks.SackCatalogClient.init()
        CatalogRegistry.init()
        // Market values merge deltas rather than swapping snapshots, so they sit beside the
        // registry with their own subscription (kind `market` is unclaimed there).
        MarketValues.init()
        // Fishing. Order matters twice over: FishingTracker installs the festival's persistence
        // hooks, so it must precede FishingFestivalState (which resumes an in-progress festival from
        // them at register time); and it is the only detector of the double-hook line, so
        // DoubleHookResponse — a subscriber now, not a second parser — has to come after it.
        SeaCreatureCatalog.init()
        FishingTracker.register()
        FishingFestivalState.register()
        FishingTimer.register()
        FishingVisibility.register()
        FishingXpTracker.register()
        BobbinSpotter.register()
        DoubleHookResponse.register()
        com.soulreturns.features.chat.AutoMeow.register()
        NoFrontCamera.register()
        PauseMenuButtons.register()
        ToggleSprint.register()
        PreventAbilityItemPlace.register()
        HideEffects.register()
        ActionBarHud.register()
        Scoreboard.register()
        HideHeldItemTooltip.register()
        HideFoliage.register()
        DevKeybindHandler.register()
        CommandKeybinds.register()
        // Before the buttons: both draw at afterBackground, and a button must land on top.
        InventoryCleanup.register()
        DarkUi.register()
        InventoryInfo.register()
        EquipmentDisplay.register()
        KeepCursor.register()
        InventoryButtons.register()
        HideStuckDianaMobs.register()
        ImplosionHider.register()
        // Loads library.json and warms the decoded clips; the replacer itself hangs off SoundEngineMixin.
        SoundLibrary.register()
        PriceTooltip.register()
        ComboTracker.register()
        ComboKillCounter.register()
        ComboDurations.register()
        MobHealthTracker.register()
        // After PartyManager (its alerts gate /pc on party membership) and after SkyblockStateReader
        // (it drives the whole feature off AreaChanged).
        MineshaftTick.register()
        MetalDetector.register()
        MetalDetectorLoot.register()
        NucleusRuns.register()
        BalState.register()
        NucleusChatCompactor.register()
        Commissions.register()
        // Dragons. After PartyManager (both announcements gate /pc on party membership) and after
        // SkyblockStateReader (every listener gates on the Dragon's Nest sublocation it publishes).
        // The detector is the only parser of the death banner; the scanner and the Legion announcer
        // are both subscribers to the event it publishes, so they register after it.
        DragonProfitTracker.register()
        EyePlacementTracker.register()
        DragonDeathDetector.register()
        DragonLootScanner.register()
        DragonLegionAnnouncer.register()
        DragonTick.register()
        // Backend plumbing: one realtime connection for the whole mod, then its consumers.
        RealtimeClient.register()
        BackendNotificationCenter.register()
        // After SkyblockStateReader: presence pushes the area/sublocation/profile it publishes.
        PresenceService.register()
        // Also after SkyblockStateReader: it fetches on the ProfileChanged that reader publishes.
        SkyblockSelfProfile.register()
        // Friends & Contests: after RealtimeClient (event handlers) and NucleusRuns (run capture source).
        com.soulreturns.features.contests.ContestsSync.register()
        com.soulreturns.features.contests.ContestCaptures.register()
        com.soulreturns.features.contests.gui.ContestsUi.register()
        // Sack tracker: counts from the self profile, sack menus and the [Sacks] chat summary.
        SackState.register()
        SackMenus.register()
        com.soulreturns.features.islandchests.IslandChests.register()
        com.soulreturns.features.islandchests.IslandChestDisplay.register()
        com.soulreturns.features.storage.StorageOverlay.register()
        com.soulreturns.features.search.SearchEverywhere.register()
        com.soulreturns.features.search.forge.Forge.register()
        // Pet HUD data: after SkyblockSelfProfile (seeds on SelfProfileLoaded) and TabWidgets.
        PetState.register()
        PetHud.register()
        com.soulreturns.features.pets.ActivePetMark.register()
        // Repoints /sp from its chat summary onto the Profile Viewer screen.
        ProfileViewerScreen.register()
        DungeonRunState.register()
        DungeonMapItemScanner.register()
        CeilingHashMatcher.register()
        TerminalSolver.register()
        LeapMenu.register()
        com.soulreturns.features.experiments.Experiments.register()
        DeviceSolvers.register()
        ServerTime.register()
        F7Boss.register()
        SectionWarning.register()
        DragonCountdown.register()
        ArcherRoles.register()
        ArcherRoleDisplay.register()

        // Secret sync: our own backend (primary) + devonian (fallback for teammates on that mod).
        // The backend channel's inbound half is a handler on the shared realtime stream, so it must
        // register before anything can deliver to it.
        SoulBackendChannel.register()
        SecretSyncHandler.addChannel(SoulBackendChannel)
        SecretSyncHandler.addChannel(DevonianChannel)
        SecretSyncHandler.register()

        // After the features, so the alert tick reads their state from this same tick.
        Alerts.register()

        SoulHud.add(DungeonMapHud)
        SoulHud.add(DungeonDebugHud)
        SoulHud.add(ComboHud)
        SoulHud.add(ToggleSprintHud)
        SoulHud.add(ActionBarHud)
        SoulHud.add(ScoreboardHud)
        SoulHud.add(NotificationHud)
        SoulHud.add(MineshaftCorpsesHud)
        SoulHud.add(FishingHud)
        SoulHud.add(DragonProfitHud)
        SoulHud.add(MetalDetectorHud)
        SoulHud.add(NucleusHud)
        SoulHud.add(CrystalChecklistHud)
        SoulHud.add(BalHud)
        SoulHud.add(CommissionsHud)
        SoulHud.add(MetalDetectorToolsHud)
        SoulHud.add(AlertHud)
        SoulHud.add(MobHealthHud)
        SoulHud.add(SackTrackerHud)
        SoulHud.add(PetHud)
        SoulHud.add(CrosshairHud)
        SoulHud.add(UpdateToastHud)
        TrackerHudHost.add(FishingHud)
        TrackerHudHost.add(DragonProfitHud)
        TrackerHudHost.add(MetalDetectorHud)
        TrackerHudHost.add(NucleusHud)
        TrackerHudHost.add(SackTrackerHud)
        MovableHudRegistry.register(ComboHud)
        MovableHudRegistry.register(ToggleSprintHud)
        MovableHudRegistry.register(ActionBarHud)
        MovableHudRegistry.register(ScoreboardHud)
        MovableHudRegistry.register(DungeonMapHud)
        MovableHudRegistry.register(NotificationHud)
        MovableHudRegistry.register(MineshaftCorpsesHud)
        MovableHudRegistry.register(FishingHud)
        MovableHudRegistry.register(DragonProfitHud)
        MovableHudRegistry.register(MetalDetectorHud)
        MovableHudRegistry.register(NucleusHud)
        MovableHudRegistry.register(CrystalChecklistHud)
        MovableHudRegistry.register(BalHud)
        MovableHudRegistry.register(CommissionsHud)
        MovableHudRegistry.register(MetalDetectorToolsHud)
        MovableHudRegistry.register(AlertHud)
        MovableHudRegistry.register(MobHealthHud)
        MovableHudRegistry.register(SackTrackerHud)
        MovableHudRegistry.register(PetHud)
        // Draws the tracker again on top of container screens, and routes clicks + the wheel into it.
        TrackerHudHost.register()
        SoulHud.register()
    }

    fun getLogger(): SoulLogger = logger
}
