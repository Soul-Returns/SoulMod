package com.soulreturns.ui.config.registry

import com.soulreturns.config.cfg
import com.soulreturns.ui.config.model.ActionRowSpec
import com.soulreturns.ui.config.model.LinkTarget
import io.wispforest.owo.config.Option

/**
 * Single source of truth for the declarative parts of the config screen — all the maps that
 * describe how categories, subcategories, sections, links, actions, visibility, and keybinds
 * relate to each other.
 *
 * Adding a new feature row is one entry in one of these maps; nothing else in the screen
 * code needs to change.
 */
internal object ConfigSections {
    /**
     * Group flat config fields into labeled sections. Used for subcategories where the
     * options are depth-≤3 paths (e.g. `render.misc.hideHeldItemTooltip`) and the auto-grouper
     * has nothing to grab onto.
     *
     * Structure: `cat → sub → ordered list of (label, field-name set)`. Options not listed in
     * any explicit section are collected into a trailing card labeled with the subcategory's
     * display name.
     */
    val explicitSections: Map<String, Map<String, List<Pair<String, Set<String>>>>> =
        mapOf(
            "render" to
                mapOf(
                    "misc" to
                        listOf(
                            "Tooltips" to setOf("hideHeldItemTooltip", "showSkyblockIdInTooltip"),
                            "Player Rendering" to setOf("oldSneakHeight"),
                            "Status Effects" to setOf("hideEffectsInInventory", "hideEffectsInHud"),
                            "World" to setOf("hideLightningFlash"),
                        ),
                    "highlights" to
                        listOf(
                            "Item Highlights" to
                                setOf(
                                    "highlightPestEquipment",
                                    "usePestVest",
                                    "highlightFarmingEquipment",
                                    "highlightCustomItems",
                                ),
                        ),
                ),
            "dev" to
                mapOf(
                    "debug" to
                        listOf(
                            // File Logging: master + sub-option. Hidden children are filtered
                            // by [optionVisibility] (includeMessagesInLog hides when logToFile off).
                            "File Logging" to setOf("logToFile", "includeMessagesInLog"),
                            // Console Logging: master (debugMode = "Log to Console") + per-category
                            // sub-toggles. Sub-toggles auto-hide when debugMode is off via
                            // [optionVisibility]; the section header stays so users see where the
                            // master toggle lives.
                            "Console Logging" to
                                setOf(
                                    "debugMode",
                                    "logConfigChanges",
                                    "logGuiLayout",
                                    "logWidgetInteractions",
                                    "logFeatureEvents",
                                    "logBackend",
                                    "logRealtime",
                                ),
                        ),
                ),
        )

    /** Cross-navigation buttons rendered inside specific subcategories. */
    val linkSections: Map<String, Map<String, List<LinkTarget>>> =
        mapOf(
            "farming" to
                mapOf(
                    "pestFarming" to
                        listOf(
                            LinkTarget("Configure Pest Equipment Highlighting", "render", "highlights"),
                        ),
                ),
        )

    /**
     * Action button rows. Callbacks receive a `ConfigScreenContext` rather than capturing
     * the screen instance directly so the registry stays a pure data declaration.
     */
    val actionRows: Map<String, Map<String, List<ActionRowSpec>>> =
        mapOf(
            // General → UI: bulk-reset buttons for the two per-HUD override fields whose
            // resolver formula is `perHud ?: global`. Once a user has toggled a per-HUD
            // override via /soul gui's right-click menu, the global toggle no longer
            // affects that HUD — these buttons clear the per-HUD value on every HUD so
            // the global takes over again.
            "general" to
                mapOf(
                    "ui" to
                        listOf(
                            ActionRowSpec(
                                label = "Reset per-HUD settings (HUD Background)",
                                buttonText = "Reset",
                                action = { resetAllHudBackgroundOverrides() },
                            ),
                            ActionRowSpec(
                                label = "Reset per-HUD settings (Minecraft Font)",
                                buttonText = "Reset",
                                action = { resetAllHudMinecraftFontOverrides() },
                            ),
                        ),
                ),
            "dev" to
                mapOf(
                    "config" to
                        listOf(
                            ActionRowSpec(
                                label = "Reload Config from Disk",
                                buttonText = "Reload",
                                action = { ctx -> ctx.reloadConfig() },
                            ),
                        ),
                ),
            // About → Used Software: third-party attributions. Each row's "Source" button
            // opens the upstream repo in the user's browser. Order roughly by relevance to
            // SkyBlock-mod users (SkyHanni first; Mojang-mapped Fabric platform / font
            // resources lower). Patterned after SkyHanni's About.Licenses accordion.
            "about" to
                mapOf(
                    "usedSoftware" to
                        listOf(
                            ActionRowSpec(
                                label = "SkyHanni — sea creature data, regex, formula references (LGPL-2.1)",
                                buttonText = "Source",
                                action = { openUrl("https://github.com/hannibal002/SkyHanni") },
                            ),
                            ActionRowSpec(
                                label = "Odin — NanoVG runtime adaptation (BSD-3-Clause)",
                                buttonText = "Source",
                                action = { openUrl("https://github.com/odtheking/Odin") },
                            ),
                            ActionRowSpec(
                                label = "Inter — bundled UI font (SIL OFL 1.1)",
                                buttonText = "Source",
                                action = { openUrl("https://github.com/rsms/inter") },
                            ),
                            ActionRowSpec(
                                label = "owo-lib — config wrapper + annotation processor (MIT)",
                                buttonText = "Source",
                                action = { openUrl("https://github.com/wisp-forest/owo-lib") },
                            ),
                            ActionRowSpec(
                                label = "Fabric API — mod platform (Apache-2.0)",
                                buttonText = "Source",
                                action = { openUrl("https://github.com/FabricMC/fabric") },
                            ),
                            ActionRowSpec(
                                label = "No-Double-Sneak — pose-data fix inlined into ClientPacketListenerMixin (MIT)",
                                buttonText = "Source",
                                action = { openUrl("https://modrinth.com/mod/no-double-sneak") },
                            ),
                        ),
                ),
        )

    /**
     * Clear every `SoulHudElement.showBackground` per-HUD override. Persist + notify the
     * sync engine so the wipe ships to other devices on the same account. Called from the
     * `Reset per-HUD settings (HUD Background)` action row.
     */
    private fun resetAllHudBackgroundOverrides() {
        com.soulreturns.gui.lib.GuiLayoutManager.resetAllSoulHudShowBackground()
        com.soulreturns.gui.lib.GuiLayoutManager.save()
        try {
            com.soulreturns.platform.sync.SyncEngine.notifyChanged(
                com.soulreturns.platform.sync.SyncKind.GUI_LAYOUT,
            )
        } catch (_: Throwable) {
            // SyncEngine not initialised / disabled — periodic scan will pick it up.
        }
    }

    /** Same as [resetAllHudBackgroundOverrides] for the per-HUD Minecraft-font override. */
    private fun resetAllHudMinecraftFontOverrides() {
        com.soulreturns.gui.lib.GuiLayoutManager.resetAllSoulHudUseMinecraftFont()
        com.soulreturns.gui.lib.GuiLayoutManager.save()
        try {
            com.soulreturns.platform.sync.SyncEngine.notifyChanged(
                com.soulreturns.platform.sync.SyncKind.GUI_LAYOUT,
            )
        } catch (_: Throwable) {
        }
    }

    private fun openUrl(url: String) {
        try {
            net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create(url))
        } catch (t: Throwable) {
            // openUri can throw if no browser is registered. Swallowing here keeps the
            // config screen interactive instead of crashing the whole compose pass.
            com.soulreturns.util.SoulLogger("Soul/Config").warn("Failed to open URL $url: ${t.message}")
        }
    }

    /** Subcategories without backing config fields. Entries appear in the sidebar via translation keys. */
    val virtualSubs: Map<String, List<String>> =
        mapOf(
            // Welcome is a fully custom landing page — see SoulConfigScreen.HomePage(). It
            // lives under `general` (rather than its own category) and `subcategoryOrder`
            // pins it to the top of general's sub-list.
            "general" to listOf("welcome"),
            "farming" to listOf("pestFarming"),
            "dev" to listOf("config"),
            // About → Used Software has zero owo-config-backed fields, so `forEachOption`
            // in CategoriesCollector never adds the category. Virtual-sub registration is
            // what brings it into the sidebar; all rows below come from `actionRows`.
            "about" to listOf("usedSoftware"),
        )

    /**
     * Explicit subcategory sort order within a category. Subs listed here come out in this
     * order; unlisted subs (real or virtual) fall to the end in original (model / virtualSubs)
     * order. Use this when a virtual sub needs to appear *before* the natural model-order
     * subs (default behavior is virtual-last because [CategoriesCollector] injects virtual
     * subs after walking the wrapper).
     */
    val subcategoryOrder: Map<String, List<String>> =
        mapOf(
            // Welcome sits above the real `general.ui` / `general.fixes` subs so it's the
            // first thing a user sees when /soul config opens.
            "general" to listOf("welcome"),
        )

    /** Explicit category sort order. Categories not listed here fall to the end in their original order. */
    val categoryOrder: List<String> =
        listOf(
            "general",
            "render",
            "fishing",
            "mining",
            "farming",
            "combat",
            "notifications",
            "profileViewer",
            "sync",
            "dev",
            "about",
        )

    /**
     * Visual indent depth for nested-dependent options. Defaults to 1 for anything in
     * [optionVisibility] (one `↳` worth of left spacing). Bump to 2+ when an option is
     * gated by another option that is itself gated — e.g. `logRealtime` depends on
     * `logBackend`, which depends on `debugMode`. Without this hint the renderer can't tell
     * the chain depth (predicates are opaque lambdas), so the parent-child relationship in
     * the UI collapses into "everything has one ↳."
     *
     * Keep this in sync with [optionVisibility]: every key here must also be a key in
     * [optionVisibility]; keys absent here default to depth 1.
     */
    val optionDepth: Map<String, Int> =
        mapOf(
            "dev.debug.logging.logRealtime" to 2,
            "general.ui.hudTextShadowSize" to 1,
            "combat.dragons.sendLegionToPartyChat" to 1,
            "combat.dragons.sendDragonProfitToPartyChat" to 1,
        )

    /**
     * Option full-path → step value passed to the Float / Double slider. `0` (the default
     * when missing) means a fully continuous slider; a positive value snaps the slider to
     * the nearest `min + n × step` position. Only honoured for Float / Double options —
     * integer-typed sliders already snap to 1 by virtue of their type.
     */
    val optionStep: Map<String, Float> =
        mapOf(
            "general.ui.hudTextShadowSize" to 0.5f,
        )

    /** Option full-path → predicate. Option is hidden when the predicate returns false. */
    val optionVisibility: Map<String, () -> Boolean> =
        mapOf(
            "render.highlights.usePestVest" to { cfg.render.highlights.highlightPestEquipment() },
            "general.ui.hudTextShadowSize" to { cfg.general.ui.hudTextShadow() },
            "farming.seasonings.showMaxMilestone" to { cfg.farming.seasonings.enableTracker() },
            "farming.seasonings.showNextMilestone" to { cfg.farming.seasonings.enableTracker() },
            "farming.seasonings.showFarmingTime" to { cfg.farming.seasonings.enableTracker() },
            "farming.seasonings.showPerHour" to { cfg.farming.seasonings.enableTracker() },
            "mining.mineshaft.lapisCorpseThreshold" to { cfg.mining.mineshaft.enableLapisPtme() },
            "mining.mineshaft.autoShareLittlefootWaypoint" to { cfg.mining.mineshaft.enableLittlefootPtme() },
            "combat.dragons.sendLegionToPartyChat" to { cfg.combat.dragons.sendLegionOnDeath() },
            "combat.dragons.sendDragonProfitToPartyChat" to { cfg.combat.dragons.sendDragonProfit() },
            "sync.syncConfig" to { cfg.sync.enabled() },
            "sync.syncGuiLayout" to { cfg.sync.enabled() },
            "sync.syncStats" to { cfg.sync.enabled() },
            // dev.debug.logging.* — hidden when the master Debug Mode toggle is off,
            // since they only have any effect when debug logging is enabled overall.
            "dev.debug.logging.logConfigChanges" to { cfg.dev.debug.debugMode() },
            "dev.debug.logging.logGuiLayout" to { cfg.dev.debug.debugMode() },
            "dev.debug.logging.logWidgetInteractions" to { cfg.dev.debug.debugMode() },
            "dev.debug.logging.logFeatureEvents" to { cfg.dev.debug.debugMode() },
            "dev.debug.logging.logBackend" to { cfg.dev.debug.debugMode() },
            // Realtime is also gated by logBackend (parent toggle).
            "dev.debug.logging.logRealtime" to {
                cfg.dev.debug.debugMode() && cfg.dev.debug.logging.logBackend()
            },
            // Sub-option of Log to File, file-only (never reaches console).
            "dev.debug.includeMessagesInLog" to { cfg.dev.debug.logToFile() },
        )

    /** Boolean toggles whose change should rebuild content (because they gate other options' visibility). */
    val rebuildOnChange: Set<String> =
        setOf(
            "render.highlights.highlightPestEquipment",
            "general.ui.hudTextShadow",
            "farming.seasonings.enableTracker",
            "mining.mineshaft.enableLapisPtme",
            "mining.mineshaft.enableLittlefootPtme",
            "sync.enabled",
            "dev.debug.debugMode",
            "dev.debug.logToFile",
            "dev.debug.logging.logBackend",
            "combat.dragons.sendLegionOnDeath",
            "combat.dragons.sendDragonProfit",
        )

    /** String fields rendered as keybind pickers (with capture mode) instead of textboxes. */
    val keybindOptions: Set<String> =
        setOf(
            "dev.keybinds.copyOpenedGui",
            "dev.keybinds.copyItemUnderCursor",
            "dev.keybinds.copyHeldItem",
            "dev.keybinds.copyScoreboard",
            "dev.keybinds.copyTablist",
            "dev.keybinds.copyNearbyEntities",
        )

    fun isOptionVisible(opt: Option<*>): Boolean {
        val key = opt.key().path().joinToString(".")
        val pred = optionVisibility[key] ?: return true
        return try {
            pred()
        } catch (_: Throwable) {
            true
        }
    }
}
