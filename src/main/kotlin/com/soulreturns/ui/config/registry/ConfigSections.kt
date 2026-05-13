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
            "dev",
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
        )

    /** Boolean toggles whose change should rebuild content (because they gate other options' visibility). */
    val rebuildOnChange: Set<String> =
        setOf(
            "render.highlights.highlightPestEquipment",
            "farming.seasonings.enableTracker",
            "mining.mineshaft.enableLapisPtme",
            "mining.mineshaft.enableLittlefootPtme",
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
