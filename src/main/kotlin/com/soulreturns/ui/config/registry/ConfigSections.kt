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
        )

    /** Subcategories without backing config fields. Entries appear in the sidebar via translation keys. */
    val virtualSubs: Map<String, List<String>> =
        mapOf(
            "farming" to listOf("pestFarming"),
            "dev" to listOf("config"),
        )

    /** Explicit category sort order. Categories not listed here fall to the end in their original order. */
    val categoryOrder: List<String> =
        listOf(
            "general",
            "render",
            "fishing",
            "mining",
            "farming",
            "notifications",
            "profileViewer",
            "sync",
            "dev",
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
        )

    /** Option full-path → predicate. Option is hidden when the predicate returns false. */
    val optionVisibility: Map<String, () -> Boolean> =
        mapOf(
            "render.highlights.usePestVest" to { cfg.render.highlights.highlightPestEquipment() },
            "farming.seasonings.showMaxMilestone" to { cfg.farming.seasonings.enableTracker() },
            "farming.seasonings.showNextMilestone" to { cfg.farming.seasonings.enableTracker() },
            "farming.seasonings.showFarmingTime" to { cfg.farming.seasonings.enableTracker() },
            "farming.seasonings.showPerHour" to { cfg.farming.seasonings.enableTracker() },
            "mining.mineshaft.lapisCorpseThreshold" to { cfg.mining.mineshaft.enableLapisPtme() },
            "mining.mineshaft.autoShareLittlefootWaypoint" to { cfg.mining.mineshaft.enableLittlefootPtme() },
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
            "farming.seasonings.enableTracker",
            "mining.mineshaft.enableLapisPtme",
            "mining.mineshaft.enableLittlefootPtme",
            "sync.enabled",
            "dev.debug.debugMode",
            "dev.debug.logToFile",
            "dev.debug.logging.logBackend",
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
