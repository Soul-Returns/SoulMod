package com.soulreturns

import com.soulreturns.commands.SoulCommand
import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.data.drops.DropCatalogClient
import com.soulreturns.data.fishing.SeaCreatureCatalog
import com.soulreturns.data.fishing.SeaCreatureCatalogClient
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.items.ItemNameAliasClient
import com.soulreturns.data.location.LocationReader
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.data.skyblock.DianaStatsCatalogClient
import com.soulreturns.data.skyblock.MythologicalMobCatalogClient
import com.soulreturns.features.DoubleHookResponse
import com.soulreturns.features.dev.DevKeybindHandler
import com.soulreturns.features.diana.DianaLootWatcher
import com.soulreturns.features.diana.DianaLootshareDetector
import com.soulreturns.features.diana.MythologicalActivityTimer
import com.soulreturns.features.diana.MythologicalMobTracker
import com.soulreturns.features.diana.MythologicalProfitChatListener
import com.soulreturns.features.diana.MythologicalProfitTracker
import com.soulreturns.features.diana.MythologicalStatsTracker
import com.soulreturns.features.farming.FarmingTimer
import com.soulreturns.features.farming.seasoning.HarvestFeastReader
import com.soulreturns.features.farming.seasoning.SeasoningTracker
import com.soulreturns.features.fishing.BobbinSpotter
import com.soulreturns.features.fishing.FishingTimer
import com.soulreturns.features.fishing.FishingTracker
import com.soulreturns.features.fishing.FishingVisibility
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
import com.soulreturns.features.profit.dragon.DragonDeathDetector
import com.soulreturns.features.profit.dragon.DragonLegionAnnouncer
import com.soulreturns.features.profit.dragon.DragonLootScanner
import com.soulreturns.features.profit.dragon.DragonProfitAnnouncer
import com.soulreturns.features.profit.dragon.DragonProfitTracker
import com.soulreturns.features.profit.dragon.EyePlacementTracker
import com.soulreturns.features.sacks.SackChatReader
import com.soulreturns.features.sacks.SackGuiReader
import com.soulreturns.features.sacks.SackState
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.platform.http.PresenceService
import com.soulreturns.platform.realtime.RealtimeClient
import com.soulreturns.platform.sync.SyncEngine
import com.soulreturns.platform.sync.SyncKind
import com.soulreturns.platform.sync.SyncedArtifact
import com.soulreturns.render.RoundRectRenderer
import com.soulreturns.stats.PersistentStats
import com.soulreturns.ui.hud.BobbinHud
import com.soulreturns.ui.hud.DianaStatsHud
import com.soulreturns.ui.hud.DragonProfitHud
import com.soulreturns.ui.hud.FishingHud
import com.soulreturns.ui.hud.LegionHud
import com.soulreturns.ui.hud.MineshaftCorpsesHud
import com.soulreturns.ui.hud.MythologicalHud
import com.soulreturns.ui.hud.MythologicalProfitHud
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

        // (The legacy TrackerSettingsStore.init was here. Per-feature HUD settings now
        // initialize from their own register() functions — see e.g. FishingHud.register.)

        // Start polling Hypixel SkyBlock location (publishes AreaChanged/SublocationChanged events).
        LocationReader.register()

        // Start polling SkyBlock-presence (sidebar title == "SKYBLOCK"; publishes OnSkyblockChanged).
        com.soulreturns.data.skyblock.SkyblockReader.register()

        // Start polling Hypixel SkyBlock profile (publishes ProfileChanged — drives PersistentStats keying).
        com.soulreturns.data.profile.ProfileReader.register()

        // Start polling Hypixel SkyBlock mayor / minister so the Mythological tracker's
        // Event tab knows which mayor term to bucket kills under. Polls every 15 min on
        // its own daemon thread — no work runs until a feature reads MayorState.current*.
        com.soulreturns.data.skyblock.MayorState.start()

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
            // Defer ~1.2 s so the title screen panorama visibly opens first — otherwise the
            // modal pops up before the screen has finished its fade-in and feels jarring.
            // CompletableFuture.delayedExecutor runs on a shared scheduler; we hop back to
            // the render thread via client.execute and re-check state in case the user
            // dismissed mid-delay or navigated away from TitleScreen.
            java.util.concurrent.CompletableFuture
                .delayedExecutor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute {
                    client.execute {
                        if (client.screen is TitleScreen && !UpdateModal.dismissed) {
                            client.setScreen(UpdateModal(update))
                        }
                    }
                }
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

        // Register the NanoVG PIP renderer — Mojang invokes it for every state submitted
        // via NvgFrame.submit. This is the only render path that lets NanoVG output reach
        // the visible swapchain in 1.21.11 (the legacy raw-FBO-bind API was removed).
        SpecialGuiElementRegistry.register { context ->
            com.soulreturns.platform.render.nvg.NvgPipRenderer(context.vertexConsumers())
        }

        // Re-render Soul HUDs on top of every open screen. Without this hook the inventory's
        // dim/blur background overlays the HUD; see SoulGuiHudAdapter.registerScreenOverlay.
        com.soulreturns.platform.mixinbridge.SoulGuiHudAdapter.registerScreenOverlay()

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
        // miss an event that arrives between connection-open and Events.subscribe. Rendering
        // + sound for the notification toast lives in `RenderUtils.showAlert` (the same path
        // `/soul dev testAlert` uses) — wired via `GuiMixin`, no separate registration here.
        BackendNotificationCenter.register()
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
        // Fishing tracker stack: catalog (lookup table) → festival state (publishes Started/Ended)
        // → tracker (subscribes to both ChatMessage + festival events) → overlay (renders panel).
        SeaCreatureCatalog.init()
        FishingFestivalState.register()
        FishingTimer.register()
        FishingVisibility.register()
        FishingTracker.register()
        FishingHud.register()

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

        // Item catalog — backend-served, loads cache from disk synchronously then fetches
        // async. Subscribes to SyncInvalidate(kind="items") for live admin-edit propagation.
        // Init here (early in registerFeatures) so any downstream feature can read the
        // catalog right away; lookups return null gracefully when the disk cache is missing
        // and the first network fetch hasn't completed yet.
        ItemCatalogClient.init()
        // Drop catalog — same lifecycle as the item catalog. Profit trackers (dragon today,
        // mythological forthcoming) resolve their per-source drop lists through this.
        DropCatalogClient.init()
        // Mythological mob catalog + Sea-creature catalog — backend-driven equivalents of
        // what used to be hardcoded enums / bundled JSON. SeaCreatureCatalog.init()
        // subscribes to its client's snapshot-change listener so a Mercure-driven refresh
        // rebuilds its lookup maps automatically; the MythologicalMobCatalog object reads
        // the client's snapshot on every lookup (cheap — 12 mobs).
        MythologicalMobCatalogClient.init()
        DianaStatsCatalogClient.init()
        SeaCreatureCatalogClient.init()
        // Alias table — chat-parser fallback for display-name → item-id when the catalog's
        // 1:1 doesn't match. Cheap to ship; consumers fall back gracefully on empty.
        ItemNameAliasClient.init()

        // Sacks — state is authoritative source of truth, GUI reader populates it on every
        // sack-screen open. Init order: state first (handles ProfileChanged for legacy
        // promotion), then Hypixel-API loader (replaces active profile state on every
        // ProfileChanged), then GUI reader (publishes per-sack snapshots into the state).
        // The state's own ProfileChanged subscriber runs before the loader's by virtue
        // of init order — legacy bucket promotes first, then the API fetch overwrites.
        SackState.init()
        com.soulreturns.data.skyblock.SkyblockProfileLoader.register()
        SackGuiReader.register()
        SackChatReader.register()

        // Profit trackers — price cache must start before the tracker registers its HUD so
        // the first frame after registration can render real prices. PriceCache.start is
        // idempotent + dormant when offline; safe to call unconditionally.
        PriceCache.start()
        DragonProfitTracker.init()
        // Dragon drop / eye detection — listens for chat banners. Order vs HUD register
        // doesn't matter (no state crossing), but conventionally we register listeners
        // before view code so the data layer is "live" by the time the HUD first paints.
        DragonDeathDetector.register()
        DragonLootScanner.register()
        EyePlacementTracker.register()
        DragonLegionAnnouncer.register()
        DragonProfitAnnouncer.register()
        DragonProfitHud.register()

        // Diana / Mythological mob tracker — timer must register before tracker so the
        // tracker's first chat event lands after the timer is already subscribed (the
        // catalog's isDigOutLine drives the timer; the tracker's chat handler then runs
        // independently and reads the timer's accumulated state on render).
        MythologicalActivityTimer.register()
        MythologicalMobTracker.init()
        MythologicalHud.register()

        // Mythological profit tracker — own data layer + chat listener for treasure-burrow
        // drops and burrow counts. The HUD reads the same backend drop catalog as Dragon.
        MythologicalProfitTracker.init()
        MythologicalProfitChatListener.register()
        // Lootshare detector — listens to player attacks + entity unloads to fire
        // lootshare credit + open the attribution window the loot watcher reads. Must
        // register before the loot watcher so its hooks are live when the first attack
        // arrives.
        DianaLootshareDetector.register()
        // Inventory + sack watcher feeds the mob-loot bucket (or lootshare bucket when
        // the detector's window is open). Reads the watched-item set from the backend
        // drop catalog (`mythological.mob_loot` source), so it stays dormant until an
        // admin curates that list.
        DianaLootWatcher.register()
        MythologicalProfitHud.register()

        // Diana stats tracker + HUD — listens for kill/drop events emitted by the mob
        // and profit trackers above, so it must initialise AFTER them so the event-bus
        // subscriptions are in place when the first publish fires. Row catalog is
        // currently hardcoded; backend-driven version is a follow-up.
        MythologicalStatsTracker.init()
        DianaStatsHud.register()
    }

    fun reloadFeatures() {
        // reload features like world rendering here
        HighlightManager.loadGroups()
    }

    fun getLogger(): SoulLogger = this.logger
}
