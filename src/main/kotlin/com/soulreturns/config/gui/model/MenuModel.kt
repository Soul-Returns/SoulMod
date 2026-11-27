package com.soulreturns.config.gui.model

/**
 * The config menu's tree (spec `mocks/config-menu/`): activities › tabs › feature cards, plus the
 * Settings screen's tabs › sections. Declared in [com.soulreturns.config.gui.ConfigRegistry].
 *
 * Icons are names of `assets/soul/textures/gui/icons/<icon>.png` (generated from the mock by
 * `scripts/config_icons.mjs`).
 */
class Activity(
    val id: String,
    val name: String,
    val icon: String,
    val tabs: List<ActivityTab>,
)

class ActivityTab(
    val id: String,
    val name: String,
)

/**
 * One feature: a card in its activity tab with a switch, and a side panel with [simple] options
 * (everyone sees them) and [advanced] ones (folded). The switch is [master]; a card without one
 * opens [openAction] instead. [parts] are the toggles a merged card's master gates: they act only
 * while the master is on, and neither ever changes the other.
 */
class FeatureCard(
    val id: String,
    val name: String,
    /** One line under the name on the card. */
    val line: String,
    val activity: String,
    val tab: String,
    val icon: String,
    /** The panel's paragraph under the header. */
    val description: String,
    val master: ToggleOption?,
    val simple: List<ConfigOption> = emptyList(),
    val advanced: List<ConfigOption> = emptyList(),
    val parts: List<ToggleOption> = emptyList(),
    /** Action id of the button a switchless card shows instead ("openInventoryButtons"). */
    val openAction: String? = null,
    /** Areas (Location API / scoreboard names) where the feature matters: Start › Right now. */
    val where: List<String> = emptyList(),
    /** Activity detectors ([com.soulreturns.config.gui.menu.Detector] ids) where it matters. */
    val contexts: List<String> = emptyList(),
    /** Search keywords; the first is the feature's own word ("sprint" → Toggle sprint). */
    val synonyms: List<String> = emptyList(),
    val hud: Boolean = false,
    /** Work in progress: ships, but is still being finished. */
    val beta: Boolean = false,
    /** Null = nothing missing; else what has to be on first (the switch stays off and disabled). */
    val blockedBy: () -> String? = { null },
    /** A secret: absent from [com.soulreturns.config.gui.ConfigRegistry.cards] until found, then shown with the paw + SECRET chip. */
    val secret: Boolean = false,
)

/** A titled block on a Settings tab. */
class SettingsSection(
    val id: String,
    val title: String,
    val subtitle: String?,
    val simple: List<ConfigOption>,
    val advanced: List<ConfigOption> = emptyList(),
)

class SettingsTab(
    val id: String,
    val name: String,
    val icon: String,
    val sections: List<SettingsSection>,
    /** Synonyms of the whole page for search ("keybinds" → Shortcuts). */
    val synonyms: List<String> = emptyList(),
    val visible: () -> Boolean = { true },
)
