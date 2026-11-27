package com.soulreturns.config.gui

import com.soulreturns.config.ChromaColor
import com.soulreturns.config.CommissionsConfig
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.DragonsConfig
import com.soulreturns.config.FishingConfig
import com.soulreturns.config.GeneralConfig
import com.soulreturns.config.MetalDetectorConfig
import com.soulreturns.config.MineshaftConfig
import com.soulreturns.config.MineshaftCorpseColors
import com.soulreturns.config.MobHealthConfig
import com.soulreturns.config.MoveGuiConfig
import com.soulreturns.config.NucleusConfig
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.menu.Detector
import com.soulreturns.config.gui.menu.MenuState
import com.soulreturns.config.gui.model.ActionOption
import com.soulreturns.config.gui.model.Activity
import com.soulreturns.config.gui.model.ActivityTab
import com.soulreturns.config.gui.model.CheckboxGroupOption
import com.soulreturns.config.gui.model.CheckboxItem
import com.soulreturns.config.gui.model.ColorOption
import com.soulreturns.config.gui.model.ConfigOption
import com.soulreturns.config.gui.model.CycleOption
import com.soulreturns.config.gui.model.FeatureCard
import com.soulreturns.config.gui.model.KeybindOption
import com.soulreturns.config.gui.model.SettingsSection
import com.soulreturns.config.gui.model.SettingsTab
import com.soulreturns.config.gui.model.SliderOption
import com.soulreturns.config.gui.model.SoundOption
import com.soulreturns.config.gui.model.TextOption
import com.soulreturns.config.gui.model.ToggleOption
import com.soulreturns.features.inventory.InventoryInfo
import com.soulreturns.features.keybinds.CommandKeybinds
import com.soulreturns.features.qol.HideFoliage
import com.soulreturns.features.sacks.SackState
import com.soulreturns.platform.devaccess.DevAccess
import com.soulreturns.platform.presence.PresenceService
import com.soulreturns.sound.SoundRef
import com.soulreturns.ui.AutoUiScale
import com.soulreturns.ui.SoulUi

/**
 * The config menu's tree (spec `mocks/config-menu/`, the placement pass of 2026-11-10): activities ›
 * tabs › feature cards, and the Settings screen's tabs › sections. **Every `SoulConfig` field a
 * player sets has a row here** (fields a panel or editor writes itself, like HUD transforms, do not).
 *
 * Placement rules: a feature lives where a player looks for it while doing that thing; a tab's name
 * describes every card in it; activity-specific beats generic; no one-card catch-alls. A tab with no
 * card is not shown.
 *
 * Built once ([cards] etc. are lazy): option instances must be stable across frames because the
 * screen keys editing state by identity. Options inside a card report edits to [MenuState.touch]
 * (Start › Recently changed); Settings rows do not.
 */
object ConfigRegistry {
    private val d = SoulConfig()

    /** Developer rows: hidden (not greyed out) and out of search until [DevAccess] is unlocked. */
    private val devOnly: () -> Boolean = { DevAccess.unlocked }
    private val always: () -> Boolean = { true }

    const val MOVE_GUI = "openMoveGui"
    const val AUTO_UI_SCALE = "autoUiScale"
    const val UI_SCALE = "UI Scale"
    const val GITHUB_URL = "https://github.com/Soul-Returns/SoulMod"
    const val GITHUB_ISSUES_URL = "https://github.com/Soul-Returns/SoulMod/issues"
    const val DISCORD_URL = "https://discord.gg/Mn5dzEJEaJ"

    val activities: List<Activity> =
        listOf(
            Activity("dungeons", "Dungeons", "skull", tabs("runs" to "Runs & party", "bosses" to "Bosses")),
            Activity(
                "mining", "Mining", "pick",
                tabs("hollows" to "Crystal Hollows", "divan" to "Mines of Divan", "mineshafts" to "Mineshafts", "dwarven" to "Dwarven Mines", "powder" to "Powder & pickaxe"),
            ),
            Activity("fishing", "Fishing", "fish", tabs("alerts" to "Alerts", "trackers" to "Trackers")),
            Activity("combat", "Combat", "sword", tabs("mobs" to "Mobs & weapons", "slayers" to "Slayers", "diana" to "Diana", "bosses" to "Dragons & Kuudra")),
            Activity("inventory", "Inventory & Items", "chest", tabs("inventory" to "Inventory", "menus" to "Menus", "tooltips" to "Tooltips", "sacks" to "Sacks")),
            Activity("everyday", "Everyday", "sun", tabs("controls" to "Controls", "hud" to "HUD", "view" to "Camera & view")),
        )

    private fun tabs(vararg t: Pair<String, String>) = t.map { ActivityTab(it.first, it.second) }

    fun activity(id: String): Activity? = activities.firstOrNull { it.id == id }

    fun tabName(activity: String, tab: String): String = activity(activity)?.tabs?.firstOrNull { it.id == tab }?.name ?: tab

    /** "Mining › Crystal Hollows". */
    fun pathOf(card: FeatureCard): String = "${activity(card.activity)?.name} › ${tabName(card.activity, card.tab)}"

    fun cardsIn(activity: String, tab: String): List<FeatureCard> = cards.filter { it.activity == activity && it.tab == tab }

    /** An activity's tabs that hold at least one card. */
    fun tabsOf(activity: String): List<ActivityTab> = activity(activity)?.tabs.orEmpty().filter { cardsIn(activity, it.id).isNotEmpty() }

    fun card(id: String): FeatureCard? = cardById[id]?.takeIf { !it.secret || cfg.meow.unlocked }

    /** The card a merged-card part belongs to, if any. */
    fun masterOf(part: ConfigOption): FeatureCard? = cards.firstOrNull { c -> c.parts.any { it === part } }

    /** Search synonyms of single Settings rows ("gui scale" → UI Scale). */
    val optionSynonyms: Map<ConfigOption, List<String>> get() = synonymsByOption

    private val synonymsByOption = java.util.IdentityHashMap<ConfigOption, List<String>>()

    private fun <T : ConfigOption> T.syn(vararg words: String): T = also { synonymsByOption[it] = words.toList() }

    // ---------------------------------------------------------------- option builders
    // The card being declared: its options report edits to Start › Recently changed.
    private var owner: String? = null

    private fun touch(): () -> Unit {
        val id = owner ?: return {}
        return { MenuState.touch(id) }
    }

    private fun toggle(label: String, desc: String?, get: () -> Boolean, set: (Boolean) -> Unit, default: Boolean, visible: () -> Boolean = always, disabled: () -> Boolean = { false }): ToggleOption {
        val t = touch()
        return ToggleOption(label, desc, get, { set(it); t() }, default, visible, disabled)
    }

    private fun slider(label: String, desc: String?, get: () -> Double, set: (Double) -> Unit, default: Double, min: Double, max: Double, step: Double, visible: () -> Boolean = always): SliderOption {
        val t = touch()
        return SliderOption(label, desc, get, { set(it); t() }, default, min, max, step, visible = visible)
    }

    private fun color(label: String, desc: String?, get: () -> ChromaColor, set: (ChromaColor) -> Unit, default: ChromaColor, alpha: Boolean = true, visible: () -> Boolean = always): ColorOption {
        val t = touch()
        return ColorOption(label, desc, get, { set(it); t() }, default, alpha, visible)
    }

    private fun text(label: String, desc: String?, get: () -> String, set: (String) -> Unit, default: String): TextOption {
        val t = touch()
        return TextOption(label, desc, get, { set(it); t() }, default)
    }

    private fun cycle(label: String, desc: String?, get: () -> Int, set: (Int) -> Unit, labels: List<String>, default: Int): CycleOption {
        val t = touch()
        return CycleOption(label, desc, get, { set(it); t() }, labels, default)
    }

    private fun keybind(label: String, desc: String?, get: () -> String, set: (String) -> Unit, default: String = ""): KeybindOption {
        val t = touch()
        return KeybindOption(label, desc, get, { set(it); t() }, default)
    }

    private fun sound(label: String, desc: String, purpose: String, get: () -> SoundRef?, set: (SoundRef?) -> Unit, default: SoundRef?, visible: () -> Boolean = always): SoundOption {
        val t = touch()
        return SoundOption(label, desc, purpose, get, { set(it); t() }, default, visible = visible)
    }

    private fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit, default: Boolean): CheckboxItem {
        val t = touch()
        return CheckboxItem(label, get, { set(it); t() }, default)
    }

    private fun action(label: String, desc: String?, button: String, id: String, visible: () -> Boolean = always) = ActionOption(label, desc, button, id, visible = visible)

    /** The "Position → Move GUI" row every HUD has. */
    private fun position(what: String, visible: () -> Boolean = always) = action("Position", "Drag, scale and anchor $what in the Move GUI", "Move GUI", MOVE_GUI, visible)

    /** Background toggle + its color (shown only while the background is on). */
    private fun background(what: String, get: () -> Boolean, set: (Boolean) -> Unit, default: Boolean, getColor: () -> ChromaColor, setColor: (ChromaColor) -> Unit, defaultColor: ChromaColor, visible: () -> Boolean = always) =
        listOf(
            toggle("Background", "Draw a card behind $what", get, set, default, visible),
            color("Background color", null, getColor, setColor, defaultColor, visible = { visible() && get() }),
        )

    private inline fun card(id: String, build: () -> FeatureCard): FeatureCard {
        owner = id
        try {
            return build().also { check(it.id == id) }
        } finally {
            owner = null
        }
    }

    private fun general(block: GeneralConfig.() -> GeneralConfig) = ConfigManager.update { copy(general = general.block()) }

    private fun mineshaft(block: MineshaftConfig.() -> MineshaftConfig) = ConfigManager.update { copy(mining = mining.copy(mineshaft = mining.mineshaft.block())) }

    private fun corpseColors(block: MineshaftCorpseColors.() -> MineshaftCorpseColors) = mineshaft { copy(corpsesHudColors = corpsesHudColors.block()) }

    private fun detector(block: MetalDetectorConfig.() -> MetalDetectorConfig) = ConfigManager.update { copy(mining = mining.copy(metalDetector = mining.metalDetector.block())) }

    private fun nucleus(block: NucleusConfig.() -> NucleusConfig) = ConfigManager.update { copy(mining = mining.copy(nucleus = mining.nucleus.block())) }

    private fun contests(block: com.soulreturns.config.ContestsConfig.() -> com.soulreturns.config.ContestsConfig) = ConfigManager.update { copy(contests = contests.block()) }

    private fun commissions(block: CommissionsConfig.() -> CommissionsConfig) = ConfigManager.update { copy(mining = mining.copy(commissions = mining.commissions.block())) }

    private fun fishing(block: FishingConfig.() -> FishingConfig) = ConfigManager.update { copy(fishing = fishing.block()) }

    private fun dragons(block: DragonsConfig.() -> DragonsConfig) = ConfigManager.update { copy(combat = combat.copy(dragons = combat.dragons.block())) }

    private fun mobHealth(block: MobHealthConfig.() -> MobHealthConfig) = ConfigManager.update { copy(combat = combat.copy(mobHealth = combat.mobHealth.block())) }

    private fun pangolin(block: com.soulreturns.config.MobWaypointConfig.() -> com.soulreturns.config.MobWaypointConfig) =
        ConfigManager.update { copy(waypoints = waypoints.copy(pangolin = waypoints.pangolin.block())) }

    private fun moveGui(block: MoveGuiConfig.() -> MoveGuiConfig) = general { copy(moveGui = moveGui.block()) }

    private fun boss(block: com.soulreturns.config.BossHelpersConfig.() -> com.soulreturns.config.BossHelpersConfig) = ConfigManager.update { copy(dungeons = dungeons.copy(boss = dungeons.boss.block())) }

    private const val ALERT_SOUND_DESC = "Played for you when a mineshaft alert fires, in a party or not"

    private fun mineshaftAlertSound() = sound("Alert sound", ALERT_SOUND_DESC, "Mineshaft alerts", { cfg.mining.mineshaft.alertSound }, { v -> mineshaft { copy(alertSound = v) } }, d.mining.mineshaft.alertSound)

    /** The switch of the double hook sound card: on = a sound is picked. */
    private val DEFAULT_PING: SoundRef = SoundRef.vanilla("minecraft:block.note_block.pling")

    private val HOLLOWS = listOf("Crystal Hollows")
    private val MINESHAFTS = listOf("Mineshaft")
    private val COMMISSION_AREAS = listOf("Dwarven Mines", "Crystal Hollows")
    private val FISHING_AREAS = listOf("Backwater Bayou", "Crimson Isle")

    // ---------------------------------------------------------------- feature cards
    /** Every card a player can see: a secret one ([FeatureCard.secret]) joins only once found (`meow.unlocked`). */
    val cards: List<FeatureCard> get() = if (cfg.meow.unlocked) allCards else shownCards

    private val shownCards: List<FeatureCard> by lazy { allCards.filter { !it.secret } }

    private val allCards: List<FeatureCard> by lazy {
        listOf(
            // Dungeons › Runs & party
            card("dmap") {
                FeatureCard(
                    "dmap", "Dungeon map", "Catacombs map with party secret sync", "dungeons", "runs", "map",
                    "Shows the map HUD while you are in the Catacombs. Position, size, colors, markers and party sync live in the map settings, with a live preview.",
                    master = toggle("Dungeon map", null, { cfg.dungeons.mapEnabled }, { v -> ConfigManager.update { copy(dungeons = dungeons.copy(mapEnabled = v)) } }, d.dungeons.mapEnabled),
                    simple = listOf(action("Map settings", "Position, size, colors, markers and party sync, with a live preview", "Open", "openMapConfig")),
                    where = listOf("Dungeon"), contexts = listOf(Detector.DUNGEON), hud = true,
                    synonyms = listOf("map", "secrets", "catacombs", "rooms", "sync"),
                )
            },
            card("leavecmd") {
                FeatureCard(
                    "leavecmd", "Block leaving commands", "Misclicked keybinds and buttons cannot warp you out of a run", "dungeons", "runs", "exit",
                    "During a run, command keybinds and inventory buttons do not send commands that leave it (/warp, /hub, /is, /lobby, …). Party commands and anything you type in chat are never blocked.",
                    master = toggle("Block leaving commands", null, { cfg.dungeons.blockLeavingCommands }, { v -> ConfigManager.update { copy(dungeons = dungeons.copy(blockLeavingCommands = v)) } }, d.dungeons.blockLeavingCommands),
                    simple = listOf(
                        toggle("Allow /warp dungeon_hub", "Still send /warp dh and /warp dungeon_hub from keybinds and buttons",
                            { cfg.dungeons.allowDungeonHubWarp }, { v -> ConfigManager.update { copy(dungeons = dungeons.copy(allowDungeonHubWarp = v)) } }, d.dungeons.allowDungeonHubWarp),
                    ),
                    where = listOf("Dungeon"), contexts = listOf(Detector.DUNGEON),
                    synonyms = listOf("warp", "hub", "misclick", "score", "leave"),
                )
            },
            card("soulskulls") {
                FeatureCard(
                    "soulskulls", "Hide Soulweaver skulls", "The Haunted Skulls floating around you stop being drawn", "dungeons", "runs", "eyeoff",
                    "Soulweaver Gloves keep up to three Haunted Skulls circling you in the Catacombs, and your party's gloves do the same. Purely visual: the skulls are still there and still stun, nothing is sent. Room decorations and other heads are never hidden.",
                    master = toggle("Hide Soulweaver skulls", null, { cfg.dungeons.hideSoulweaverSkulls }, { v -> ConfigManager.update { copy(dungeons = dungeons.copy(hideSoulweaverSkulls = v)) } }, d.dungeons.hideSoulweaverSkulls),
                    where = listOf("Dungeon"), contexts = listOf(Detector.DUNGEON),
                    synonyms = listOf("soulweaver", "gloves", "haunted skull", "skulls", "floating heads", "heads", "clutter"),
                )
            },
            card("leapmenu") {
                val l = { cfg.dungeons.leap }
                fun leap(block: com.soulreturns.config.LeapMenuConfig.() -> com.soulreturns.config.LeapMenuConfig) = ConfigManager.update { copy(dungeons = dungeons.copy(leap = dungeons.leap.block())) }
                val dl = d.dungeons.leap
                val modes = com.soulreturns.features.dungeons.leap.LeapSort.MODES
                FeatureCard(
                    "leapmenu", "Leap menu", "Spirit Leap with each class in its own corner and where everyone is", "dungeons", "runs", "user",
                    "The Spirit Leap menu opens as four big tiles, one per teammate, each class always in the same corner (Odin's order). Click anywhere in a corner or press 1-4. Each tile shows where that teammate is: seen right now, last seen or their own party message with its age, or a yellow ?. Every click is yours: one click sends one leap, never on its own.",
                    master = toggle("Leap menu", null, { l().enabled }, { v -> leap { copy(enabled = v) } }, dl.enabled),
                    simple = listOf(
                        cycle("Sorting", "Odin: every class keeps its own corner. The others fill the corners in order", { modes.indexOf(l().sorting).coerceAtLeast(0) }, { v -> leap { copy(sorting = modes.getOrElse(v) { "odin" }) } }, listOf("Odin", "Class A-Z", "Name A-Z", "As Hypixel"), 0),
                        toggle("Number keys 1-4", "1 top left, 2 top right, 3 bottom left, 4 bottom right", { l().numberKeys }, { v -> leap { copy(numberKeys = v) } }, dl.numberKeys),
                    ),
                    advanced = listOf(
                        toggle("Block clicks on dead teammates", "A click on a dead teammate sends nothing", { l().blockDead }, { v -> leap { copy(blockDead = v) } }, dl.blockDead),
                        toggle("Key hints on the tiles", "The small 1-4 in each tile's corner", { l().keyHints }, { v -> leap { copy(keyHints = v) } }, dl.keyHints, visible = { l().numberKeys }),
                        toggle("Show where teammates are", "The line under the class: seen now, last seen or their party message, or ?", { l().showLocations }, { v -> leap { copy(showLocations = v) } }, dl.showLocations),
                    ),
                    where = listOf("Dungeon"), contexts = listOf(Detector.DUNGEON),
                    synonyms = listOf("spirit leap", "infinileap", "leap", "teleport", "odin", "ee2", "ee3", "teammates"),
                )
            },
            card("archerroles") {
                val r = { cfg.dungeons.roles }
                fun roles(block: com.soulreturns.config.ArcherRoleConfig.() -> com.soulreturns.config.ArcherRoleConfig) =
                    ConfigManager.update { copy(dungeons = dungeons.copy(roles = dungeons.roles.block())) }
                val dr = d.dungeons.roles
                FeatureCard(
                    "archerroles", "Archer roles", "Your next objective and who to leap to in F7/M7", "dungeons", "runs", "target",
                    "Guides the F7/M7 Archer through the left crystal route, boss leaps, terminals and levers. Confirmed completions advance your route; an objective already finished by a teammate is skipped. Early leaps show wait advice when the destination or usable death protection is unconfirmed. Guidance only: every movement and leap stays yours.",
                    master = toggle("Archer roles", null, { r().enabled }, { v -> roles { copy(enabled = v) } }, dr.enabled),
                    simple = listOf(
                        toggle("Leap cue", "Show a quiet leap instruction below the boss timers. Off keeps wait advice and the leap-menu recommendation", { r().showCue }, { v -> roles { copy(showCue = v) } }, dr.showCue),
                        toggle("Mark next objective", "Outline your next crystal, terminal, lever or gate in the world", { r().markObjective }, { v -> roles { copy(markObjective = v) } }, dr.markObjective),
                        toggle("Leap-menu recommendation", "Mark the recommended teammate without changing the menu's order or blocking your choice", { r().highlightLeap }, { v -> roles { copy(highlightLeap = v) } }, dr.highlightLeap),
                    ),
                    where = listOf("Dungeon"), contexts = listOf(Detector.DUNGEON), beta = true,
                    synonyms = listOf("m7", "archer", "role", "route", "leap", "crystal", "preleap", "terminal", "lever"),
                )
            },
            // Dungeons › Bosses
            card("terminals") {
                val t = { cfg.dungeons.terminals }
                fun terminals(block: com.soulreturns.config.TerminalSolverConfig.() -> com.soulreturns.config.TerminalSolverConfig) = ConfigManager.update { copy(dungeons = dungeons.copy(terminals = dungeons.terminals.block())) }
                val dt = d.dungeons.terminals
                FeatureCard(
                    "terminals", "Terminal solver", "Goldor's terminals in a clear Soul window", "dungeons", "bosses", "grid",
                    "F7 and M7 terminals open in Soul's own window: what to click is highlighted, the rest dimmed. Every click is yours: one click sends one click, never on its own. Anything unexpected shows the normal menu.",
                    master = toggle("Terminal solver", null, { t().enabled }, { v -> terminals { copy(enabled = v) } }, dt.enabled),
                    simple = listOf(
                        toggle("Block wrong clicks", "A click on a wrong or already done cell sends nothing", { t().blockWrongClicks }, { v -> terminals { copy(blockWrongClicks = v) } }, dt.blockWrongClicks),
                    ),
                    advanced = listOf(
                        toggle("Left click as middle click", "Items don't jump onto your cursor until the server answers (lag). Sent as a real middle click", { t().leftAsMiddle }, { v -> terminals { copy(leftAsMiddle = v) } }, dt.leftAsMiddle),
                        toggle("Custom GUI", "Off keeps the normal menu and only highlights its slots", { t().customGui }, { v -> terminals { copy(customGui = v) } }, dt.customGui),
                        slider("GUI size", "Size of the cells in Soul's window", { t().guiSize.toDouble() }, { v -> terminals { copy(guiSize = v.toFloat()) } }, dt.guiSize.toDouble(), 0.6, 1.6, 0.05, visible = { t().customGui }),
                        toggle("Show left / right labels", "The small mouse-button hint under each number in the color terminal", { t().rubixLabels }, { v -> terminals { copy(rubixLabels = v) } }, dt.rubixLabels, visible = { t().customGui }),
                        toggle("Show title", "The terminal name and the \"left\" counter above the board", { t().showTitle }, { v -> terminals { copy(showTitle = v) } }, dt.showTitle, visible = { t().customGui }),
                        toggle("What starts with", "\"What starts with: 'X'?\"", { t().startsWith }, { v -> terminals { copy(startsWith = v) } }, dt.startsWith),
                        toggle("Select all the color", "\"Select all the <color> items!\"", { t().selectColor }, { v -> terminals { copy(selectColor = v) } }, dt.selectColor),
                        toggle("Correct all the panes", "\"Correct all the panes!\"", { t().panes }, { v -> terminals { copy(panes = v) } }, dt.panes),
                        toggle("Click in order", "\"Click in order!\"", { t().order }, { v -> terminals { copy(order = v) } }, dt.order),
                        toggle("Change all to same color", "Shows how many left or right clicks each pane needs", { t().sameColor }, { v -> terminals { copy(sameColor = v) } }, dt.sameColor),
                        toggle("Click the button on time", "Melody: shows when to lock in", { t().melody }, { v -> terminals { copy(melody = v) } }, dt.melody),
                    ),
                    where = listOf("Dungeon"), contexts = listOf(Detector.DUNGEON),
                    synonyms = listOf("terminals", "f7", "m7", "goldor", "melody", "rubix", "same color", "starts with", "panes", "order"),
                )
            },
            card("devices") {
                val c = { cfg.dungeons.devices }
                fun devices(block: com.soulreturns.config.DeviceSolverConfig.() -> com.soulreturns.config.DeviceSolverConfig) = ConfigManager.update { copy(dungeons = dungeons.copy(devices = dungeons.devices.block())) }
                val dd = d.dungeons.devices
                FeatureCard(
                    "devices", "Device solvers", "Goldor's devices: which button, lever, arrow or target comes next", "dungeons", "bosses", "cursor",
                    "Simon Says: the button to press next lights up green on the device, the one after it dimmer, with how many presses are left. Follows the three-press skip on the start button (the first light does not count then). Shows nothing when it did not see the whole sequence. Lights: the fewest levers that still light every dark lamp are boxed on the grid. Arrow Align: every arrow shows how many clicks it still needs. Sharpshooter: the target block is boxed green and the ones already hit dim red (highlight only, nothing is blocked). Every click is yours: nothing is ever pressed for you.",
                    master = toggle("Device solvers", null, { c().enabled }, { v -> devices { copy(enabled = v) } }, dd.enabled),
                    simple = listOf(
                        toggle("Block wrong clicks", "A click on a wrong Simon Says button, a 4th rapid start press (it breaks the skip) a Lights lever that is already flicked or an Arrow Align arrow that already points at its target sends nothing", { c().blockWrongClicks }, { v -> devices { copy(blockWrongClicks = v) } }, dd.blockWrongClicks),
                    ),
                    advanced = listOf(
                        toggle("Arrow Align: hold the last click before Goldor", "While Goldor's phase has not started (seen from the boss chat; unknown holds nothing) the click that would finish Arrow Align is not sent; the bottom-left-most arrow that needs clicks is left one click short. Works without \"Block wrong clicks\"", { c().arrowHoldLast }, { v -> devices { copy(arrowHoldLast = v) } }, dd.arrowHoldLast),
                        toggle("Simon Says", "The 4×4 button device in the first section", { c().simonSays }, { v -> devices { copy(simonSays = v) } }, dd.simonSays),
                        cycle("Override blocking while", "While this holds, this device blocks nothing: every click goes through, the highlights stay", { com.soulreturns.features.dungeons.devices.BlockOverride.MODES.indexOf(c().simonSaysOverride).coerceAtLeast(0) }, { v -> devices { copy(simonSaysOverride = com.soulreturns.features.dungeons.devices.BlockOverride.MODES.getOrElse(v) { "never" }) } }, listOf("Never", "Standing", "Sneaking"), 0),
                        toggle("Lights", "The 5×4 lever grid in the second section: the levers to flip next", { c().lights }, { v -> devices { copy(lights = v) } }, dd.lights),
                        cycle("Override blocking while", "While this holds, this device blocks nothing: every click goes through, the highlights stay", { com.soulreturns.features.dungeons.devices.BlockOverride.MODES.indexOf(c().lightsOverride).coerceAtLeast(0) }, { v -> devices { copy(lightsOverride = com.soulreturns.features.dungeons.devices.BlockOverride.MODES.getOrElse(v) { "never" }) } }, listOf("Never", "Standing", "Sneaking"), 0),
                        toggle("Arrow Align", "The 5×5 item-frame wall in the third section: the clicks each arrow still needs", { c().arrowAlign }, { v -> devices { copy(arrowAlign = v) } }, dd.arrowAlign),
                        cycle("Override blocking while", "While this holds, this device blocks nothing: every click goes through, the highlights stay", { com.soulreturns.features.dungeons.devices.BlockOverride.MODES.indexOf(c().arrowAlignOverride).coerceAtLeast(0) }, { v -> devices { copy(arrowAlignOverride = com.soulreturns.features.dungeons.devices.BlockOverride.MODES.getOrElse(v) { "never" }) } }, listOf("Never", "Standing", "Sneaking"), 0),
                        toggle("Sharpshooter", "The 3×3 target grid in the fourth section: the target block and the ones already hit. Highlight only", { c().sharpshooter }, { v -> devices { copy(sharpshooter = v) } }, dd.sharpshooter),
                        toggle("Show the button after the next", "Marked dimmer, so you can move on without waiting", { c().showAfterNext }, { v -> devices { copy(showAfterNext = v) } }, dd.showAfterNext),
                    ),
                    where = listOf("Dungeon"), contexts = listOf(Detector.DUNGEON),
                    synonyms = listOf("simon says", "ss", "lights", "lever", "levers", "arrow align", "arrows", "item frames", "sharpshooter", "sharp shooter", "targets", "target practice", "devices", "device", "f7", "m7", "goldor", "skip", "buttons"),
                )
            },
            card("bosstimers") {
                val b = { cfg.dungeons.boss }
                val db = d.dungeons.boss
                val on = { b().crushWindow }
                FeatureCard(
                    "bosstimers", "Boss timers", "Fixed places at your crosshair: pad tick, what to do on the pads, crush window, Storm's phase", "dungeons", "bosses", "target",
                    "Timers on the server's own clock, each in its own place at the crosshair that always means the same thing. Above it: the pad tick (a thin ring and its digits, the 20-tick pad check loop, while you are near a pad in Storm's phase; Goldor's death tick in his) and Storm's phase (Lightning, Giga, High, Storm returns; after his death the countdown until terminals open). Below it: the action line (STEP ON PURPLE, STAY until the check, STEP OFF, or off too early) and the armed crush window in the pillar's color with Storm's distance and ETA, then its result (crushed, late by, missed). While a window is armed its pillar's area is outlined on the floor. Display only.",
                    master = toggle("Boss timers", null, on, { v -> boss { copy(crushWindow = v) } }, db.crushWindow),
                    simple = listOf(
                        cycle("Show", "Seconds or server ticks", { b().units }, { v -> boss { copy(units = v) } }, listOf("Seconds", "Ticks"), db.units),
                        toggle("Pad tick", "The 20-tick pad check loop: a ring around the crosshair and its digits above it", { b().showPad }, { v -> boss { copy(showPad = v) } }, db.showPad),
                        toggle("Goldor death tick", "Every 60 ticks from the start of Goldor's phase (the floor collapse when someone is already down, else when you drop in), re-synced by a death or \"What do you think you are doing there!\"", { b().showGoldor }, { v -> boss { copy(showGoldor = v) } }, db.showGoldor),
                        toggle("Action line", "Under the crosshair: STEP ON, STAY until the pad check, STEP OFF, or off too early", { b().showAction }, { v -> boss { copy(showAction = v) } }, db.showAction),
                        toggle("Action: step on Purple", "When to step on the purple pad for crush 1, and too late", { b().showPurple }, { v -> boss { copy(showPurple = v) } }, db.showPurple),
                        toggle("Action: arm cue (experimental)", "During Storm's high phase: which pad check to be on, then \"bounce back\" and a spot by the pillar. Not proven yet; every try goes to the log", { b().showArm }, { v -> boss { copy(showArm = v) } }, db.showArm),
                        toggle("Crush window and result", "Countdown while a pillar can crush, then crushed / late / missed for 3 s", { b().showWindow }, { v -> boss { copy(showWindow = v) } }, db.showWindow),
                        toggle("Phase: Lightning / Giga", "To the strike: the first from the phase start, later ones from their countdown", { b().showGiga }, { v -> boss { copy(showGiga = v) } }, db.showGiga),
                        toggle("Phase: High / Storm returns", "From Storm's climb to when he leaves the top (162 ticks), then to his return", { b().showHigh }, { v -> boss { copy(showHigh = v) } }, db.showHigh),
                        toggle("Phase: Terminals open", "After Storm's death, the time until terminals, levers and devices take clicks and Goldor's death tick starts (about 3 s, the idea of Odin's Start timer)", { b().showTerminals }, { v -> boss { copy(showTerminals = v) } }, db.showTerminals),
                    ),
                    advanced = listOf(
                        cycle("Pad tick shows", "The pad only reacts on its tick: show the loop near a pad, only on one, or all phase", { b().padWhen }, { v -> boss { copy(padWhen = v) } }, listOf("Near a pad", "On a pad", "Whole phase"), db.padWhen),
                        toggle("STAY shows the time", "The time to the pad check after STAY", { b().stayTime }, { v -> boss { copy(stayTime = v) } }, db.stayTime),
                        toggle("Ring for the Goldor tick", "The death tick loop around the crosshair too; off keeps only its digits", { b().goldorRing }, { v -> boss { copy(goldorRing = v) } }, db.goldorRing),
                        slider("Digit size", "Size of the pad digits; the other places scale from it", { b().digitSize.toDouble() }, { v -> boss { copy(digitSize = v.toFloat()) } }, db.digitSize.toDouble(), 16.0, 80.0, 1.0),
                        slider("Offset below the crosshair", "Where the action line starts under the crosshair; never inside the ring", { b().offset.toDouble() }, { v -> boss { copy(offset = v.toFloat()) } }, db.offset.toDouble(), 0.0, 160.0, 1.0),
                        toggle("Mark the pillar", "Outline the pillar's area on the floor while its window is armed", { b().markPillar }, { v -> boss { copy(markPillar = v) } }, db.markPillar),
                        toggle("Late result shows how late", "+0.35 instead of the word", { b().lateAmount }, { v -> boss { copy(lateAmount = v) } }, db.lateAmount),
                        toggle("Storm distance and ETA", "Under the window: how far Storm is and when he gets there", { b().stormEta }, { v -> boss { copy(stormEta = v) } }, db.stormEta),
                        toggle("Next fireball", "In the High line", { b().nextFireball }, { v -> boss { copy(nextFireball = v) } }, db.nextFireball),
                        toggle("Also in Master Mode", "M7 uses Floor 7's measured timings", { b().onM7 }, { v -> boss { copy(onM7 = v) } }, db.onM7),
                    ),
                    where = listOf("Dungeon"), contexts = listOf(Detector.DUNGEON),
                    synonyms = listOf("tick timer", "pad timer", "storm", "crush", "pillar", "goldor", "lightning", "py", "purple", "bait", "stay", "step off", "f7", "m7", "p2"),
                )
            },
            card("sectionwarning") {
                val b = { cfg.dungeons.boss }
                val db = d.dungeons.boss
                FeatureCard(
                    "sectionwarning", "Section not open warning", "Goldor's phase: a warning while you stand in a section that has not begun", "dungeons", "bosses", "bell",
                    "In Goldor's phase, a warning stays up the whole time you stand in a later section than the open one, until that section really begins: all of the previous section's terminals, levers and device done and its gate destroyed (the core opens after the fourth). Goldor's death tick hits players there: all 36 hits with a known position in 8 M7 runs. Uses the shared warning element (Move GUI). Display only.",
                    master = toggle("Section not open warning", null, { b().sectionWarning }, { v -> boss { copy(sectionWarning = v) } }, db.sectionWarning),
                    advanced = listOf(
                        sound("Warning sound", "Played once per section when the warning first appears", "Section not open", { b().sectionWarningSound },
                            { v -> boss { copy(sectionWarningSound = v) } }, db.sectionWarningSound),
                    ),
                    where = listOf("Dungeon"), contexts = listOf(Detector.DUNGEON),
                    synonyms = listOf("goldor", "death tick", "death zone", "section", "terminals", "gate", "ahead", "f7", "m7", "p3"),
                )
            },
            card("dragoncountdown") {
                val b = { cfg.dungeons.boss }
                val db = d.dungeons.boss
                FeatureCard(
                    "dragoncountdown", "Dragon spawn countdown", "M7 dragons: the time until each spawns, where it spawns", "dungeons", "bosses", "target",
                    "In the Wither King's phase of Master Mode 7, a dragon appears exactly 100 ticks after its first spawn particles. Each pending dragon gets its color name and countdown at its spawn, visible through blocks, and the next one shows above the crosshair in the boss timers' phase place. On the server's clock. Display only.",
                    master = toggle("Dragon spawn countdown", null, { b().dragonCountdown }, { v -> boss { copy(dragonCountdown = v) } }, db.dragonCountdown),
                    where = listOf("Dungeon"), contexts = listOf(Detector.DUNGEON),
                    synonyms = listOf("dragon", "dragons", "p5", "wither king", "spawn", "timer", "m7", "relics"),
                )
            },
            // Mining › Crystal Hollows
            card("nucleus") {
                val alert = toggle("Rare drop alert", "A chat line for a bundle item worth at least the threshold; those rows turn gold on the panel",
                    { cfg.mining.nucleus.rareDropAlert }, { v -> nucleus { copy(rareDropAlert = v) } }, d.mining.nucleus.rareDropAlert)
                FeatureCard(
                    "nucleus", "Nucleus run tracker", "Runs, run times, loot and profit per hour", "mining", "hollows", "panel",
                    "A panel in the Crystal Hollows: runs, last / median / best run time, runs and profit per hour, loot per item with drop rate and value, and the kit you used. A run ends at the loot bundle. Tabs, price mode and columns are on the panel itself in your inventory.",
                    master = toggle("Nucleus run tracker", null, { cfg.mining.nucleus.tracker }, { v -> nucleus { copy(tracker = v) } }, d.mining.nucleus.tracker),
                    simple = listOf(position("the panel"), alert),
                    advanced = listOf(
                        slider("Rare drop threshold", "In million coins", { cfg.mining.nucleus.rareDropThresholdM }, { v -> nucleus { copy(rareDropThresholdM = v) } },
                            d.mining.nucleus.rareDropThresholdM, 0.1, 50.0, 0.1, visible = { cfg.mining.nucleus.rareDropAlert }),
                    ),
                    where = HOLLOWS, contexts = listOf(Detector.NUCLEUS), hud = true,
                    synonyms = listOf("nucleus", "crystal", "profit", "loot bundle", "runs", "tracker"),
                )
            },
            card("checklist") {
                FeatureCard(
                    "checklist", "Crystal checklist", "The five crystals with your splits and a \"go place\" cue", "mining", "hollows", "list",
                    "Missing, found (time since the run started) or placed, each leg against your median for that crystal, and the run timer. Restored from the tab list's Crystals widget when you show it.",
                    master = toggle("Crystal checklist", null, { cfg.mining.nucleus.checklist }, { v -> nucleus { copy(checklist = v) } }, d.mining.nucleus.checklist),
                    simple = listOf(position("the checklist")),
                    advanced = listOf(
                        color("Background color", "Shared by the checklist and the Bal line", { cfg.mining.nucleus.checklistBackgroundColor }, { v -> nucleus { copy(checklistBackgroundColor = v) } }, d.mining.nucleus.checklistBackgroundColor),
                    ),
                    where = HOLLOWS, contexts = listOf(Detector.NUCLEUS), hud = true,
                    synonyms = listOf("crystals", "jade", "amber", "amethyst", "sapphire", "topaz", "splits"),
                )
            },
            card("bal") {
                FeatureCard(
                    "bal", "Bal status", "Whether Bal is down and the Topaz is yours to take", "mining", "hollows", "burst",
                    "Bal's state from chat: down (with the time since he retreated), up or spawning. Per lobby, unknown until you see one of his lines. Respawn time is not predicted (60 s to 6 min).",
                    master = toggle("Bal status", null, { cfg.mining.nucleus.bal }, { v -> nucleus { copy(bal = v) } }, d.mining.nucleus.bal),
                    simple = listOf(
                        position("the Bal line and the warning"),
                        toggle("Warn when entering Khazad-dûm while Bal is up", "Big on-screen warning for 4 seconds: bring your Bal kit",
                            { cfg.mining.nucleus.balWarning }, { v -> nucleus { copy(balWarning = v) } }, d.mining.nucleus.balWarning),
                    ),
                    where = HOLLOWS, contexts = listOf(Detector.NUCLEUS), hud = true,
                    synonyms = listOf("bal", "topaz", "khazad", "boss"),
                )
            },
            card("nucchat") {
                val found = toggle("Compact crystal found", "One line with the crystal, n/5 and your split instead of the 8-line block",
                    { cfg.mining.nucleus.compactFound }, { v -> nucleus { copy(compactFound = v) } }, d.mining.nucleus.compactFound)
                val loot = toggle("Compact loot bundle", "One line with item count, value, run time and rare drops instead of ~22 lines",
                    { cfg.mining.nucleus.compactLoot }, { v -> nucleus { copy(compactLoot = v) } }, d.mining.nucleus.compactLoot)
                val obtained = toggle("Hide 'You have already obtained this Crystal!'", null,
                    { cfg.mining.nucleus.hideAlreadyObtained }, { v -> nucleus { copy(hideAlreadyObtained = v) } }, d.mining.nucleus.hideAlreadyObtained)
                val blocks = toggle("Hide 'There are blocks in the way!'", "Failed etherwarps",
                    { cfg.mining.nucleus.hideBlocksInWay }, { v -> nucleus { copy(hideBlocksInWay = v) } }, d.mining.nucleus.hideBlocksInWay)
                FeatureCard(
                    "nucchat", "Compact nucleus chat", "One line per crystal and per loot bundle", "mining", "hollows", "chat",
                    "In the Crystal Hollows only, display only: other mods and the trackers here still read every line. Hover a compact line to see the original lines.",
                    master = toggle("Compact nucleus chat", null, { cfg.mining.nucleus.compactChat }, { v -> nucleus { copy(compactChat = v) } }, d.mining.nucleus.compactChat),
                    simple = listOf(found, loot), advanced = listOf(obtained, blocks), parts = listOf(found, loot, obtained, blocks),
                    where = HOLLOWS, synonyms = listOf("chat", "spam", "compact", "loot bundle"),
                )
            },
            card("weathermute") {
                FeatureCard(
                    "weathermute", "Mute Hollows weather", "Silence Rockfall creaking and Breeze wind", "mining", "hollows", "mute",
                    "Weather sounds only, in the Crystal Hollows; the visuals stay. Hypixel can only turn sounds and visuals off together, this does them separately.",
                    master = toggle("Mute Hollows weather", null, { cfg.mining.muteHollowsWeather }, { v -> ConfigManager.update { copy(mining = mining.copy(muteHollowsWeather = v)) } }, d.mining.muteHollowsWeather),
                    where = HOLLOWS, synonyms = listOf("weather", "rockfall", "breeze", "sound", "mute"),
                )
            },
            card("weatherhide") {
                FeatureCard(
                    "weatherhide", "Hide Hollows weather visuals", "The Rockfall's dust, debris and small explosions", "mining", "hollows", "cloud",
                    "Hides the Rockfall's falling dust, debris and small explosions in the Crystal Hollows; sounds stay.",
                    master = toggle("Hide Hollows weather visuals", null, { cfg.mining.hideHollowsWeatherVisuals }, { v -> ConfigManager.update { copy(mining = mining.copy(hideHollowsWeatherVisuals = v)) } }, d.mining.hideHollowsWeatherVisuals),
                    where = HOLLOWS, synonyms = listOf("weather", "rockfall", "particles", "fps", "lag"),
                )
            },
            // Mining › Mines of Divan
            card("mdsolver") {
                FeatureCard(
                    "mdsolver", "Metal Detector solver", "Points you to the Mines of Divan treasure", "mining", "divan", "target",
                    "Draws a waypoint and a line to the treasure chest from the Metal Detector's pings while you hold it. Works while moving and teleporting; shows up to three faint guesses until the chest is certain.",
                    master = toggle("Metal Detector solver", null, { cfg.mining.metalDetector.enabled }, { v -> detector { copy(enabled = v) } }, d.mining.metalDetector.enabled),
                    simple = listOf(color("Waypoint color", null, { cfg.mining.metalDetector.waypointColor }, { v -> detector { copy(waypointColor = v) } }, d.mining.metalDetector.waypointColor)),
                    advanced = listOf(slider("Line width", "Of the line to the chest", { cfg.mining.metalDetector.lineWidth.toDouble() }, { v -> detector { copy(lineWidth = v.toFloat()) } }, d.mining.metalDetector.lineWidth.toDouble(), 1.0, 8.0, 0.5)),
                    where = HOLLOWS, contexts = listOf(Detector.METAL_DETECTOR),
                    synonyms = listOf("metal detector", "divan", "treasure", "md", "waypoint", "solver"),
                )
            },
            card("mdloot") {
                FeatureCard(
                    "mdloot", "Divan loot tracker", "Session timer, tool-set times and loot per item", "mining", "divan", "panel",
                    "A panel while you hold the Metal Detector in the Mines of Divan; it stays a minute after and hides when you leave. Tools show by default; add powder and other loot from the Groups filter in your inventory.",
                    master = toggle("Divan loot tracker", null, { cfg.mining.metalDetector.hud }, { v -> detector { copy(hud = v) } }, d.mining.metalDetector.hud),
                    simple = listOf(
                        position("the panel"),
                        toggle("All four tools warning", "Big warning when all four Scavenged tools are in your inventory: store them, more tool finds are wasted",
                            { cfg.mining.metalDetector.toolsAlert }, { v -> detector { copy(toolsAlert = v) } }, d.mining.metalDetector.toolsAlert),
                    ),
                    advanced = listOf(
                        sound("Warning sound", "Played once when the warning appears", "Metal Detector tools", { cfg.mining.metalDetector.toolsAlertSound },
                            { v -> detector { copy(toolsAlertSound = v) } }, d.mining.metalDetector.toolsAlertSound, visible = { cfg.mining.metalDetector.toolsAlert }),
                    ),
                    where = HOLLOWS, contexts = listOf(Detector.METAL_DETECTOR), hud = true,
                    synonyms = listOf("divan", "metal detector", "loot", "tools", "scavenged"),
                )
            },
            card("toolscounter") {
                FeatureCard(
                    "toolscounter", "Scavenged tools counter", "How many of the four Divan tools you carry", "mining", "divan", "tools",
                    "A small \"Scavenged Tools N/4\" readout in the Mines of Divan: top right, left of the loot panel while that shows. Movable in the Move GUI.",
                    master = toggle("Scavenged tools counter", null, { cfg.mining.metalDetector.toolsCounter }, { v -> detector { copy(toolsCounter = v) } }, d.mining.metalDetector.toolsCounter),
                    simple = listOf(position("the counter")),
                    where = HOLLOWS, contexts = listOf(Detector.METAL_DETECTOR), hud = true,
                    synonyms = listOf("scavenged", "tools", "divan", "counter"),
                )
            },
            // Mining › Dwarven Mines
            card("commissionshud") {
                FeatureCard(
                    "commissionshud", "Commissions HUD", "Your commissions with their progress on screen", "mining", "dwarven", "list",
                    "One row per commission with its progress, DONE in green. Read from the tab list's Commissions widget; without that widget, from the last time you opened the Commissions menu, while you are where commissions are.",
                    master = toggle("Commissions HUD", null, { cfg.mining.commissions.hud }, { v -> commissions { copy(hud = v) } }, d.mining.commissions.hud),
                    simple = listOf(position("the list")),
                    advanced = background("the list", { cfg.mining.commissions.hudBackground }, { v -> commissions { copy(hudBackground = v) } }, d.mining.commissions.hudBackground,
                        { cfg.mining.commissions.hudBackgroundColor }, { v -> commissions { copy(hudBackgroundColor = v) } }, d.mining.commissions.hudBackgroundColor),
                    where = COMMISSION_AREAS, hud = true,
                    synonyms = listOf("commissions", "comms", "king", "progress", "tab list"),
                )
            },
            card("commissionsdone") {
                FeatureCard(
                    "commissionsdone", "Highlight completed commissions", "Finished commissions stand out in the Commissions menu", "mining", "dwarven", "check",
                    "In the Commissions menu, every commission that reads COMPLETED gets a green mark, however many commission slots you have.",
                    master = toggle("Highlight completed commissions", null, { cfg.mining.commissions.highlightCompleted }, { v -> commissions { copy(highlightCompleted = v) } }, d.mining.commissions.highlightCompleted),
                    where = COMMISSION_AREAS, synonyms = listOf("commissions", "comms", "claim", "completed", "highlight"),
                )
            },
            // Mining › Powder & pickaxe
            card("powdercoating") {
                FeatureCard(
                    "powdercoating", "Hide Powder Coating particles", "The white and gold dust around coated Divan armor", "mining", "powder", "eyeoff",
                    "Hides the dust Divan's Powder Coating puts around anyone wearing coated Armor of Divan, you and other players. Only that exact dust close to a player is hidden; the coating's effect stays.",
                    master = toggle("Hide Powder Coating particles", null, { cfg.mining.hidePowderCoatingParticles }, { v -> ConfigManager.update { copy(mining = mining.copy(hidePowderCoatingParticles = v)) } }, d.mining.hidePowderCoatingParticles),
                    synonyms = listOf("divan", "powder coating", "particles", "dust", "armor", "fps"),
                )
            },
            // Mining › Mineshafts
            card("corpses") {
                val c = { cfg.mining.mineshaft.corpsesHudColors }
                val dc = d.mining.mineshaft.corpsesHudColors
                FeatureCard(
                    "corpses", "Corpse overlay", "Frozen corpses in this mineshaft, looted / total", "mining", "mineshafts", "snow",
                    "One row per corpse type while you are inside a Glacite Mineshaft. Corpse types Soul does not know a color for show as \"Other\" rather than vanish.",
                    master = toggle("Corpse overlay", null, { cfg.mining.mineshaft.corpsesHud }, { v -> mineshaft { copy(corpsesHud = v) } }, d.mining.mineshaft.corpsesHud),
                    simple = listOf(position("the overlay")),
                    advanced = background("the overlay", { cfg.mining.mineshaft.corpsesHudBackground }, { v -> mineshaft { copy(corpsesHudBackground = v) } }, d.mining.mineshaft.corpsesHudBackground,
                        { cfg.mining.mineshaft.corpsesHudBackgroundColor }, { v -> mineshaft { copy(corpsesHudBackgroundColor = v) } }, d.mining.mineshaft.corpsesHudBackgroundColor) +
                        listOf(
                            color("Lapis", null, { c().lapis }, { v -> corpseColors { copy(lapis = v) } }, dc.lapis, alpha = false),
                            color("Umber", null, { c().umber }, { v -> corpseColors { copy(umber = v) } }, dc.umber, alpha = false),
                            color("Tungsten", null, { c().tungsten }, { v -> corpseColors { copy(tungsten = v) } }, dc.tungsten, alpha = false),
                            color("Vanguard", null, { c().vanguard }, { v -> corpseColors { copy(vanguard = v) } }, dc.vanguard, alpha = false),
                            color("Other corpse types", null, { c().other }, { v -> corpseColors { copy(other = v) } }, dc.other, alpha = false),
                            color("Looted count", null, { c().looted }, { v -> corpseColors { copy(looted = v) } }, dc.looted, alpha = false),
                            color("Total count", null, { c().total }, { v -> corpseColors { copy(total = v) } }, dc.total, alpha = false),
                            color("Heading", null, { c().title }, { v -> corpseColors { copy(title = v) } }, dc.title, alpha = false),
                        ),
                    where = MINESHAFTS, hud = true,
                    synonyms = listOf("corpse", "glacite", "lapis", "umber", "tungsten", "vanguard", "mineshaft"),
                )
            },
            card("corpselocator") {
                FeatureCard(
                    "corpselocator", "Corpse locator", "Points you to Frozen Corpses from the Organ Donor ding and what you see", "mining", "mineshafts", "target",
                    "Finds corpses from the Organ Donor Talisman, Ring or Artifact in your Accessory Bag, and any corpse you can see.",
                    master = toggle("Corpse locator", null, { cfg.mining.mineshaft.corpseLocator }, { v -> mineshaft { copy(corpseLocator = v) } }, d.mining.mineshaft.corpseLocator),
                    simple = listOf(
                        color("Waypoint color", "Until you have seen which corpse it is", { cfg.mining.mineshaft.corpseLocatorColor }, { v -> mineshaft { copy(corpseLocatorColor = v) } }, d.mining.mineshaft.corpseLocatorColor),
                        toggle("Mute the ding", "Silences the Organ Donor ding in mineshafts; the locator still uses it. Without spot data it plays until you have seen its corpse",
                            { cfg.mining.mineshaft.corpseLocatorMute }, { v -> mineshaft { copy(corpseLocatorMute = v) } }, d.mining.mineshaft.corpseLocatorMute),
                        toggle("Dim corpses without a key", "Umber, Tungsten and Vanguard corpses fade and lose their line while their key is not in your inventory",
                            { cfg.mining.mineshaft.corpseLocatorDimKeyless }, { v -> mineshaft { copy(corpseLocatorDimKeyless = v) } }, d.mining.mineshaft.corpseLocatorDimKeyless),
                        toggle("Share with party", "Party members in the same mineshaft see the corpses you found, if they use Soul with this on",
                            { cfg.mining.mineshaft.corpseLocatorShare }, { v -> mineshaft { copy(corpseLocatorShare = v) } }, d.mining.mineshaft.corpseLocatorShare),
                    ),
                    advanced = listOf(
                        toggle("Line: Lapis", null, { cfg.mining.mineshaft.corpseLineLapis }, { v -> mineshaft { copy(corpseLineLapis = v) } }, d.mining.mineshaft.corpseLineLapis),
                        toggle("Line: Umber", null, { cfg.mining.mineshaft.corpseLineUmber }, { v -> mineshaft { copy(corpseLineUmber = v) } }, d.mining.mineshaft.corpseLineUmber),
                        toggle("Line: Tungsten", null, { cfg.mining.mineshaft.corpseLineTungsten }, { v -> mineshaft { copy(corpseLineTungsten = v) } }, d.mining.mineshaft.corpseLineTungsten),
                        toggle("Line: Vanguard", null, { cfg.mining.mineshaft.corpseLineVanguard }, { v -> mineshaft { copy(corpseLineVanguard = v) } }, d.mining.mineshaft.corpseLineVanguard),
                        toggle("Line: not identified yet", "Corpses you have not seen yet, and types Soul does not know",
                            { cfg.mining.mineshaft.corpseLineUnknown }, { v -> mineshaft { copy(corpseLineUnknown = v) } }, d.mining.mineshaft.corpseLineUnknown),
                    ),
                    where = MINESHAFTS,
                    synonyms = listOf("corpse", "organ donor", "talisman", "waypoint", "frozen corpse", "vanguard", "glacite", "mineshaft", "mute", "ding", "key", "line", "party", "share"),
                )
            },
            card("littlefoot") {
                FeatureCard(
                    "littlefoot", "Littlefoot alert", "Tells your party when you spot Littlefoot", "mining", "mineshafts", "paw",
                    "Sends \"/pc !ptme Found Littlefoot\" the first time you can actually see Littlefoot in a mineshaft you found yourself. Never in one a party leader warped you into.",
                    master = toggle("Littlefoot alert", null, { cfg.mining.mineshaft.littlefootPtme }, { v -> mineshaft { copy(littlefootPtme = v) } }, d.mining.mineshaft.littlefootPtme),
                    simple = listOf(
                        toggle("Share Littlefoot waypoint", "Two seconds after the party warp, send \"/pc x: N, y: N, z: N\" so waypoint mods place a marker",
                            { cfg.mining.mineshaft.littlefootWaypoint }, { v -> mineshaft { copy(littlefootWaypoint = v) } }, d.mining.mineshaft.littlefootWaypoint),
                    ),
                    advanced = listOf(mineshaftAlertSound()),
                    where = MINESHAFTS, synonyms = listOf("littlefoot", "ptme", "party", "mineshaft"),
                )
            },
            card("lapis") {
                FeatureCard(
                    "lapis", "Lapis corpse alert", "Tells your party when a fresh mineshaft is Lapis-rich", "mining", "mineshafts", "gem",
                    "A raw protocol message to party chat, so other mods' parsers match it. Hypixel drops /pc when you are not in a party.",
                    master = toggle("Lapis corpse alert", null, { cfg.mining.mineshaft.lapisPtme }, { v -> mineshaft { copy(lapisPtme = v) } }, d.mining.mineshaft.lapisPtme),
                    // A mineshaft never holds more than four corpses in total, so 4 is the ceiling.
                    simple = listOf(
                        slider("Lapis threshold", "How many Lapis corpses are worth a message (a mineshaft holds at most 4)",
                            { cfg.mining.mineshaft.lapisThreshold.toDouble() }, { v -> mineshaft { copy(lapisThreshold = v.toInt()) } }, d.mining.mineshaft.lapisThreshold.toDouble(), 1.0, 4.0, 1.0),
                    ),
                    advanced = listOf(mineshaftAlertSound()),
                    where = MINESHAFTS, synonyms = listOf("lapis", "corpse", "ptme", "party"),
                )
            },
            card("vanguard") {
                FeatureCard(
                    "vanguard", "Vanguard corpse alert", "Tells your party when a mineshaft has a Vanguard corpse", "mining", "mineshafts", "shield",
                    "At most one per mineshaft, so there is nothing to set a threshold for.",
                    master = toggle("Vanguard corpse alert", null, { cfg.mining.mineshaft.vanguardPtme }, { v -> mineshaft { copy(vanguardPtme = v) } }, d.mining.mineshaft.vanguardPtme),
                    advanced = listOf(mineshaftAlertSound()),
                    where = MINESHAFTS, synonyms = listOf("vanguard", "corpse", "ptme", "party"),
                )
            },
            card("vanguardguild") {
                FeatureCard(
                    "vanguardguild", "Vanguard guild call", "Posts in guild chat when you find a Vanguard shaft and invites whoever answers \"me\"", "mining", "mineshafts", "shield",
                    "When you find a mineshaft with a Vanguard corpse, posts a message in guild chat and invites every guild member who answers \"me\" to your party, one by one, until the shaft collapses. Only in a shaft you found yourself. Use \"!warp\" with another mod once your party is ready.",
                    master = toggle("Vanguard guild call", null, { cfg.mining.mineshaft.vanguardGuild }, { v -> mineshaft { copy(vanguardGuild = v) } }, d.mining.mineshaft.vanguardGuild),
                    simple = listOf(
                        toggle("Skip when in a party", "Do nothing if you are already in a party when you enter the shaft",
                            { cfg.mining.mineshaft.vanguardGuildSkipInParty }, { v -> mineshaft { copy(vanguardGuildSkipInParty = v) } }, d.mining.mineshaft.vanguardGuildSkipInParty),
                    ),
                    where = MINESHAFTS, synonyms = listOf("vanguard", "guild", "gc", "invite", "party", "warp", "me", "gamble"),
                )
            },
            card("logvisits") {
                FeatureCard(
                    "logvisits", "Log mineshaft visits", "Your spawn and loot odds in /soul mineshaft and the Profile Viewer", "mining", "mineshafts", "upload",
                    "Sends the Soul backend: the mineshaft type and variant (ONYX_1), when you entered and left, the corpse counts, whether Littlefoot was there, whether you found it yourself or were warped in (and by whom), and where the corpses you saw lay, so the corpse locator can learn every spot. Nothing else.",
                    master = toggle("Log mineshaft visits", null, { cfg.mining.mineshaft.logVisits }, { v -> mineshaft { copy(logVisits = v) } }, d.mining.mineshaft.logVisits),
                    where = MINESHAFTS, synonyms = listOf("backend", "odds", "statistics", "privacy"),
                )
            },
            // Fishing › Alerts
            card("dhmsg") {
                FeatureCard(
                    "dhmsg", "Double hook message", "Tells your party about every double hook", "fishing", "alerts", "hook",
                    "Sends your message to party chat on a double hook. Only in a party: Hypixel drops /pc otherwise.",
                    master = toggle("Double hook message", null, { cfg.fishing.doubleHookMessageEnabled }, { v -> fishing { copy(doubleHookMessageEnabled = v) } }, d.fishing.doubleHookMessageEnabled),
                    simple = listOf(text("Message", "What your party reads", { cfg.fishing.doubleHookMessageText }, { v -> fishing { copy(doubleHookMessageText = v) } }, d.fishing.doubleHookMessageText)),
                    where = FISHING_AREAS, contexts = listOf(Detector.FISHING),
                    synonyms = listOf("double hook", "party", "woot"),
                )
            },
            card("dhsound") {
                FeatureCard(
                    "dhsound", "Double hook sound", "Hear every double hook, in a party or not", "fishing", "alerts", "music",
                    "Plays a sound of your choice on every double hook.",
                    master = toggle("Double hook sound", null, { cfg.fishing.doubleHookSound != null },
                        { v -> fishing { copy(doubleHookSound = if (v) doubleHookSound ?: DEFAULT_PING else null) } }, d.fishing.doubleHookSound != null),
                    simple = listOf(sound("Sound", "Played on every double hook", "Double hook", { cfg.fishing.doubleHookSound }, { v -> fishing { copy(doubleHookSound = v) } }, d.fishing.doubleHookSound)),
                    where = FISHING_AREAS, contexts = listOf(Detector.FISHING),
                    synonyms = listOf("double hook", "sound", "ding"),
                )
            },
            card("bobbin") {
                FeatureCard(
                    "bobbin", "Bobbin Time alert", "Says in chat when the pond gets crowded", "fishing", "alerts", "bell",
                    "Said once, only to you, when enough bobbers are in the water within 30 blocks.",
                    master = toggle("Bobbin Time alert", null, { cfg.fishing.bobbinAlert }, { v -> fishing { copy(bobbinAlert = v) } }, d.fishing.bobbinAlert),
                    simple = listOf(slider("Bobber threshold", "How many bobbers count as a crowd", { cfg.fishing.bobbinThreshold.toDouble() }, { v -> fishing { copy(bobbinThreshold = v.toInt()) } }, d.fishing.bobbinThreshold.toDouble(), 1.0, 10.0, 1.0)),
                    advanced = listOf(sound("Alert sound", "Played with the Bobbin Time message", "Bobbin Time", { cfg.fishing.bobbinAlertSound }, { v -> fishing { copy(bobbinAlertSound = v) } }, d.fishing.bobbinAlertSound)),
                    where = FISHING_AREAS, contexts = listOf(Detector.FISHING),
                    synonyms = listOf("bobbin", "bobber", "crowd"),
                )
            },
            // Fishing › Trackers
            card("fishtracker") {
                FeatureCard(
                    "fishtracker", "Fishing tracker", "Catches per creature, double hooks and an active-fishing stopwatch", "fishing", "trackers", "panel",
                    "Appears once you cast on a fishing island with a SkyBlock rod. The stopwatch only runs while you are actually fishing. Open your inventory to switch tabs, sort, filter and choose columns.",
                    master = toggle("Fishing tracker", null, { cfg.fishing.trackerHud }, { v -> fishing { copy(trackerHud = v) } }, d.fishing.trackerHud),
                    simple = listOf(
                        position("the panel"),
                        toggle("Show rates", "Catches and XP per hour of active fishing, not wall-clock", { cfg.fishing.trackerShowRates }, { v -> fishing { copy(trackerShowRates = v) } }, d.fishing.trackerShowRates),
                    ),
                    advanced = listOf(
                        toggle("Scrolling creature list", "A fixed height with a scroll wheel instead of growing with every creature", { cfg.fishing.trackerScrollable }, { v -> fishing { copy(trackerScrollable = v) } }, d.fishing.trackerScrollable),
                        toggle("Festival countdown", "The fishing-festival timer in the panel header while one runs", { cfg.fishing.trackerFestivalTimer }, { v -> fishing { copy(trackerFestivalTimer = v) } }, d.fishing.trackerFestivalTimer),
                    ) + background("the panel", { cfg.fishing.trackerHudBackground }, { v -> fishing { copy(trackerHudBackground = v) } }, d.fishing.trackerHudBackground,
                        { cfg.fishing.trackerHudBackgroundColor }, { v -> fishing { copy(trackerHudBackgroundColor = v) } }, d.fishing.trackerHudBackgroundColor) +
                        action("Session counters", "Clears the Session numbers and the stopwatch. All-time and Festival stay.", "Reset session", "resetFishingSession"),
                    where = FISHING_AREAS, contexts = listOf(Detector.FISHING, Detector.FESTIVAL), hud = true,
                    synonyms = listOf("sea creatures", "catches", "stopwatch", "rates", "tracker"),
                )
            },
            // Combat › Mobs & weapons
            card("mobhealth") {
                FeatureCard(
                    "mobhealth", "Mob health", "Level, name and health bar of the mob you look at", "combat", "mobs", "heart",
                    "For the mob you look at (up to 32 blocks) or last hit, read from its nametag. Stays 2 s after you look away, 10 s after your last hit.",
                    master = toggle("Mob health", null, { cfg.combat.mobHealth.enabled }, { v -> mobHealth { copy(enabled = v) } }, d.combat.mobHealth.enabled),
                    simple = listOf(position("the HUD")),
                    advanced = background("the HUD", { cfg.combat.mobHealth.background }, { v -> mobHealth { copy(background = v) } }, d.combat.mobHealth.background,
                        { cfg.combat.mobHealth.backgroundColor }, { v -> mobHealth { copy(backgroundColor = v) } }, d.combat.mobHealth.backgroundColor),
                    hud = true, synonyms = listOf("health", "hp", "nametag", "mob"),
                )
            },
            card("implosion") {
                val particles = toggle("Hide explosion particles", "Yours and other players'", { cfg.general.hideImplosionParticles }, { v -> general { copy(hideImplosionParticles = v) } }, d.general.hideImplosionParticles)
                val bang = toggle("Mute explosion sound", "The bang of your own Implosion", { cfg.general.hideImplosionExplosionSound }, { v -> general { copy(hideImplosionExplosionSound = v) } }, d.general.hideImplosionExplosionSound)
                val whoosh = toggle("Mute teleport sound", "The whoosh of your own Implosion. Aspect of the Void teleports still play (a few ms late while on)",
                    { cfg.general.hideImplosionTeleportSound }, { v -> general { copy(hideImplosionTeleportSound = v) } }, d.general.hideImplosionTeleportSound)
                FeatureCard(
                    "implosion", "Quiet Implosion", "Hyperion and the other Wither blades, without the noise", "combat", "mobs", "burst",
                    "Pick what to hide. Other explosions stay as they are.",
                    master = toggle("Quiet Implosion", null, { cfg.general.quietImplosion }, { v -> general { copy(quietImplosion = v) } }, d.general.quietImplosion),
                    simple = listOf(particles, bang, whoosh), parts = listOf(particles, bang, whoosh),
                    synonyms = listOf("hyperion", "wither blade", "astraea", "scylla", "valkyrie", "implosion", "explosion", "particles"),
                )
            },
            card("combo") {
                val c = { cfg.combo }
                fun combo(block: com.soulreturns.config.ComboConfig.() -> com.soulreturns.config.ComboConfig) = ConfigManager.update { copy(combo = combo.block()) }
                FeatureCard(
                    "combo", "Kill Combo overlay", "Your Grandma Wolf combo and its stacked bonuses", "combat", "mobs", "paw",
                    "Tracks the kill combo from chat and adds up the Combat Wisdom and coins per kill each milestone gives. The live count is exact with a Blood God Crest/Sigil, close with a Book of Stats weapon, otherwise it follows the every-5-kills milestones. The timer learns your Grandma Wolf's durations when you open the Pets menu.",
                    master = toggle("Kill Combo overlay", null, { c().enabled }, { v -> combo { copy(enabled = v) } }, d.combo.enabled),
                    simple = listOf(
                        cycle("Layout", "Full: several lines with labels. Compact: one line", { c().mode }, { v -> combo { copy(mode = v) } }, listOf("Full", "Compact"), d.combo.mode),
                        position("the overlay"),
                        CheckboxGroupOption(
                            "Show", null,
                            listOf(
                                check("Combo", { c().showCount }, { v -> combo { copy(showCount = v) } }, d.combo.showCount),
                                check("Combat Wisdom", { c().showCombatWisdom }, { v -> combo { copy(showCombatWisdom = v) } }, d.combo.showCombatWisdom),
                                check("Coins", { c().showCoins }, { v -> combo { copy(showCoins = v) } }, d.combo.showCoins),
                                check("Timer", { c().showExpireTimer }, { v -> combo { copy(showExpireTimer = v) } }, d.combo.showExpireTimer),
                            ),
                        ),
                    ),
                    advanced = listOf(
                        slider("Auto-hide delay", "Seconds after the last kill (0 = only when it expires)", { c().decaySeconds.toDouble() }, { v -> combo { copy(decaySeconds = v.toFloat()) } }, d.combo.decaySeconds.toDouble(), 0.0, 15.0, 0.5),
                    ) + background("the overlay", { c().background }, { v -> combo { copy(background = v) } }, d.combo.background, { c().backgroundColor }, { v -> combo { copy(backgroundColor = v) } }, d.combo.backgroundColor) +
                        listOf(
                            toggle("Hide combo messages", "The \"+N Kill Combo\" milestone lines", { c().hideComboMessages }, { v -> combo { copy(hideComboMessages = v) } }, d.combo.hideComboMessages),
                            toggle("Hide expiry message", "\"Your Kill Combo has expired!\"", { c().hideExpireMessage }, { v -> combo { copy(hideExpireMessage = v) } }, d.combo.hideExpireMessage),
                            toggle("Track best combo", "Say in chat when you beat your record", { c().trackBest }, { v -> combo { copy(trackBest = v) } }, d.combo.trackBest),
                        ),
                    hud = true, synonyms = listOf("combo", "grandma wolf", "combat wisdom", "magic find", "kill combo", "sbcombo"),
                )
            },
            card("pangolin") {
                val c = { cfg.waypoints.pangolin }
                FeatureCard(
                    "pangolin", "Pangolin waypoints", "Marks every Pangolin you see in Torrhus Canyon, rolled in or not", "combat", "mobs", "paw",
                    "A waypoint on each Pangolin in sight, in its own color while it is rolled in. When it slips out of view the waypoint stays where you last saw it for a minute, or until you look there and it is gone. Shared ones show who sent them.",
                    master = toggle("Pangolin waypoints", null, { c().enabled }, { v -> pangolin { copy(enabled = v) } }, d.waypoints.pangolin.enabled),
                    simple = listOf(
                        color("Waypoint color", "Not rolled in, and where you last saw it", { c().color }, { v -> pangolin { copy(color = v) } }, d.waypoints.pangolin.color),
                        color("Rolled-in color", "It rolls in when a player comes close", { c().stateColor }, { v -> pangolin { copy(stateColor = v) } }, d.waypoints.pangolin.stateColor),
                        toggle("Share with party", "Each new Pangolin you see goes to party chat once (at most 3 a minute), and you see the ones your party sends",
                            { c().share }, { v -> pangolin { copy(share = v) } }, d.waypoints.pangolin.share),
                    ),
                    advanced = listOf(
                        toggle("Line", "A line from your crosshair to each Pangolin", { c().line }, { v -> pangolin { copy(line = v) } }, d.waypoints.pangolin.line),
                    ),
                    where = listOf("Torrhus Canyon"),
                    synonyms = listOf("pangolin", "armadillo", "waypoint", "hunting", "shard", "lasso", "torrhus", "galatea", "party", "share"),
                )
            },
            // Combat › Diana
            card("diana") {
                FeatureCard(
                    "diana", "Hide stuck Diana mobs", "Removes the frozen griffin statues other players leave", "combat", "diana", "eyeoff",
                    "When another player's Diana mob dies out of range it stays behind as a frozen statue. Hub only; hidden statues also stop blocking your attacks.",
                    master = toggle("Hide stuck Diana mobs", null, { cfg.general.hideStuckDianaMobs }, { v -> general { copy(hideStuckDianaMobs = v) } }, d.general.hideStuckDianaMobs),
                    where = listOf("Hub"), synonyms = listOf("diana", "griffin", "mythological", "statue", "burrow"),
                )
            },
            // Combat › Dragons & Kuudra
            card("dragons") {
                val dr = { cfg.combat.dragons }
                FeatureCard(
                    "dragons", "Dragon profit tracker", "What the End dragons drop for you, minus your eyes", "combat", "bosses", "eye",
                    "Counts what the seven End dragons drop for you at Dragon's Nest, values it at live prices and subtracts what your Summoning Eyes cost. Off: nothing watches, scans or sends.",
                    master = toggle("Dragon profit tracker", null, { dr().enabled }, { v -> dragons { copy(enabled = v) } }, d.combat.dragons.enabled),
                    simple = listOf(
                        toggle("Show profit panel", "Only at Dragon's Nest, not in the rest of The End or in M7", { dr().profitHud }, { v -> dragons { copy(profitHud = v) } }, d.combat.dragons.profitHud),
                        position("the panel"),
                        toggle("Announce profit per kill", "What the kill made, net of the eyes you placed", { dr().sendDragonProfit }, { v -> dragons { copy(sendDragonProfit = v) } }, d.combat.dragons.sendDragonProfit),
                    ),
                    advanced = listOf(
                        toggle("Send profit to party chat", "Off keeps it to yourself", { dr().sendDragonProfitToPartyChat }, { v -> dragons { copy(sendDragonProfitToPartyChat = v) } },
                            d.combat.dragons.sendDragonProfitToPartyChat, visible = { dr().sendDragonProfit }),
                        toggle("Announce Legion on each death", "Your nearby-player count and the damage boost it is worth", { dr().sendLegionOnDeath }, { v -> dragons { copy(sendLegionOnDeath = v) } }, d.combat.dragons.sendLegionOnDeath),
                        toggle("Send Legion to party chat", "Off keeps it to yourself", { dr().sendLegionToPartyChat }, { v -> dragons { copy(sendLegionToPartyChat = v) } },
                            d.combat.dragons.sendLegionToPartyChat, visible = { dr().sendLegionOnDeath }),
                        toggle("Use the NPC price when it is higher", "For drops worth more to a vendor. Never applied to your eyes", { dr().useNpcPriceIfHigher }, { v -> dragons { copy(useNpcPriceIfHigher = v) } }, d.combat.dragons.useNpcPriceIfHigher),
                        toggle("Scrolling drop list", "A fixed height with a scroll wheel", { dr().profitScrollable }, { v -> dragons { copy(profitScrollable = v) } }, d.combat.dragons.profitScrollable),
                    ) + background("the panel", { dr().profitHudBackground }, { v -> dragons { copy(profitHudBackground = v) } }, d.combat.dragons.profitHudBackground,
                        { dr().profitHudBackgroundColor }, { v -> dragons { copy(profitHudBackgroundColor = v) } }, d.combat.dragons.profitHudBackgroundColor) +
                        listOf(
                            action("Session counters", "Clears the Session numbers. All-time stays.", "Reset session", "resetDragonSession"),
                            action("All-time counters", "Clears every dragon, drop and eye on THIS profile, for good. Asks first.", "Reset all-time", "resetDragonAllTime"),
                        ),
                    where = listOf("The End"), hud = true,
                    synonyms = listOf("dragon", "eyes", "summoning eye", "dragon's nest", "profit", "legion"),
                )
            },
            // Inventory & Items › Menus
            card("darkui") {
                FeatureCard(
                    "darkui", "Dark UI", "Dark inventories and menus, filler panes blended in", "inventory", "menus", "moon",
                    "Dark inventory and menu backgrounds with SkyBlock's filler glass blended in. Works with the hidden inventory elements.",
                    master = toggle("Dark UI", null, { cfg.general.darkUi }, { v -> general { copy(darkUi = v) } }, d.general.darkUi),
                    beta = true, synonyms = listOf("dark mode", "theme", "black", "night"),
                )
            },
            card("keepcursor") {
                FeatureCard(
                    "keepcursor", "Keep cursor between menus", "The cursor stays put when a menu switches pages", "inventory", "menus", "cursor",
                    "When a menu switches to the next one (storage pages, going back in SkyBlock menus) the cursor stays where it was instead of jumping to the center.",
                    master = toggle("Keep cursor between menus", null, { cfg.general.keepCursorBetweenMenus }, { v -> general { copy(keepCursorBetweenMenus = v) } }, d.general.keepCursorBetweenMenus),
                    synonyms = listOf("mouse", "cursor", "storage", "reset"),
                )
            },
            card("experiments") {
                val e = { cfg.experiments }
                fun experiments(block: com.soulreturns.config.ExperimentsConfig.() -> com.soulreturns.config.ExperimentsConfig) = ConfigManager.update { copy(experiments = experiments.block()) }
                val de = d.experiments
                FeatureCard(
                    "experiments", "Experimentation Table", "Chronomatron, Ultrasequencer and Superpairs in Soul's window", "inventory", "menus", "grid",
                    "The whole table opens in Soul's window: where to click next is ringed, clicks during the reveal and wrong answers send nothing, and Superpairs remembers every card. Every click is yours: one click sends one click, never on its own. Closing always works. Anything unexpected shows the normal menu.",
                    master = toggle("Experimentation Table", null, { e().enabled }, { v -> experiments { copy(enabled = v) } }, de.enabled),
                    simple = listOf(
                        toggle("Block misclicks", "Wrong answers, clicks while the pattern plays and known Superpairs mismatches send nothing", { e().blockClicks }, { v -> experiments { copy(blockClicks = v) } }, de.blockClicks),
                        toggle("Rare reward warning", "Banner and sound when Superpairs reveals a rare card (open /rng once so Soul learns which ones)", { e().rareWarning }, { v -> experiments { copy(rareWarning = v) } }, de.rareWarning),
                        sound("Rare reward sound", "Played with the rare reward banner", "Rare Superpairs reward", { e().rareSound }, { v -> experiments { copy(rareSound = v) } }, de.rareSound, visible = { e().rareWarning }),
                    ),
                    beta = true,
                    synonyms = listOf("experiments", "experimentation", "enchanting", "superpairs", "chronomatron", "ultrasequencer", "add-ons", "rng meter", "solver"),
                )
            },
            // Inventory & Items › Inventory
            card("searcheverywhere") {
                FeatureCard(
                    "searcheverywhere", "Search everywhere", "One search over your island chests, storage and sacks", "inventory", "inventory", "search",
                    "Finds an item in everything Soul remembers for this profile: chests you opened on your island and the storage pages you looked at, plus the sack counts Soul keeps. Press the key or use /soul search anywhere on SkyBlock. Each source takes part while its own feature is on. Its Forge tab (Ctrl+Tab) plans a forge recipe down to what you own.",
                    master = null, openAction = "openSearchEverywhere",
                    simple = listOf(
                        action("Find stored items", "Search every remembered island chest, storage page and counted sack item", "Search", "openSearchEverywhere"),
                        keybind("Search key", "Open search, or return to it while locating or from a page it opened. Also editable in /soul keybinds", { cfg.islandChests.searchKey }, { v ->
                            ConfigManager.update { CommandKeybinds.rebind(this, CommandKeybinds.Ref.IslandChests, v) }
                        }, d.islandChests.searchKey),
                        toggle("Search sacks", "Include the sack tracker's counts from menus, [Sacks] messages and the SkyBlock API. Works with the Sack tracker panel off", { cfg.sacks.searchSacks }, { v ->
                            ConfigManager.update { copy(sacks = sacks.copy(searchSacks = v)) }
                            if (v) SackState.prefill()
                        }, d.sacks.searchSacks),
                    ),
                    beta = true,
                    synonyms = listOf("search", "find", "items", "where", "chest", "storage", "sacks", "ender chest", "backpack", "forge", "recipe", "quick forge"),
                )
            },
            card("islandchests") {
                FeatureCard(
                    "islandchests", "Island chests", "Remember what is in your island's chests", "inventory", "inventory", "chest",
                    "Remembers real chests you open on your own island, separately for each SkyBlock profile. Search everywhere finds their items from anywhere and highlights every matching chest on your island. Counts reflect the last observation, not unseen changes.",
                    master = toggle("Island chests", null, { cfg.islandChests.enabled }, { v ->
                        ConfigManager.update { copy(islandChests = islandChests.copy(enabled = v)) }
                    }, d.islandChests.enabled),
                    simple = listOf(
                        action("Find stored items", "Opens Search everywhere", "Search", "openSearchEverywhere"),
                        toggle("Share with co-op", "Sync observed chest contents and locations through the backend with verified members of this profile", { cfg.islandChests.shareWithCoop }, { v ->
                            ConfigManager.update { copy(islandChests = islandChests.copy(shareWithCoop = v)) }
                            com.soulreturns.features.islandchests.IslandChests.refresh()
                        }, d.islandChests.shareWithCoop),
                    ),
                    beta = true,
                    synonyms = listOf("chest", "storage", "island", "items", "search", "find", "coop", "co-op", "ironman"),
                )
            },
            card("storageoverlay") {
                FeatureCard(
                    "storageoverlay", "Storage overlay", "Your Ender Chest and backpacks in one searchable wall", "inventory", "inventory", "chest",
                    "Remembers pages you open, separately for each profile. Click a preview to open its real page; only the open page can move items. Manage storage returns to the normal menu for backpack changes.",
                    master = toggle("Storage overlay", null, { cfg.storageOverlay.enabled }, { v ->
                        ConfigManager.update { copy(storageOverlay = storageOverlay.copy(enabled = v)) }
                    }, d.storageOverlay.enabled),
                    simple = listOf(
                        toggle("Open last storage page", "Make /storage open the last Ender Chest or backpack page used on this profile",
                            { cfg.storageOverlay.openLastPage }, { v ->
                                ConfigManager.update { copy(storageOverlay = storageOverlay.copy(openLastPage = v)) }
                            }, d.storageOverlay.openLastPage),
                        slider("Pages per row", "The wall scrolls instead of shrinking items to fit",
                            { cfg.storageOverlay.columns.toDouble() }, { v ->
                                ConfigManager.update { copy(storageOverlay = storageOverlay.copy(columns = v.toInt().coerceIn(1, 6))) }
                            }, d.storageOverlay.columns.toDouble(), 1.0, 6.0, 1.0),
                        toggle("Override Skyblocker storage", "Use Soul's overlay even when Skyblocker's storage overlay is enabled; its settings stay unchanged",
                            { cfg.storageOverlay.overrideSkyblocker }, { v ->
                                ConfigManager.update { copy(storageOverlay = storageOverlay.copy(overrideSkyblocker = v)) }
                            }, d.storageOverlay.overrideSkyblocker),
                    ),
                    beta = true,
                    synonyms = listOf("storage", "ender chest", "backpack", "search", "items"),
                )
            },
            card("cleanup") {
                val book = toggle("Hide recipe book", "The book and its button; crafting tables keep theirs", { cfg.general.hideRecipeBook }, { v -> general { copy(hideRecipeBook = v) } }, d.general.hideRecipeBook)
                val offhand = toggle("Hide off-hand slot", "SkyBlock doesn't use it", { cfg.general.hideOffhandSlot }, { v -> general { copy(hideOffhandSlot = v) } }, d.general.hideOffhandSlot)
                val grid = toggle("Hide crafting grid", "Items left in it come back when you close the inventory", { cfg.general.hideCraftingGrid }, { v -> general { copy(hideCraftingGrid = v) } }, d.general.hideCraftingGrid)
                FeatureCard(
                    "cleanup", "Clean up inventory", "Hide the recipe book, off-hand slot and crafting grid", "inventory", "inventory", "grid",
                    "Pick what leaves your inventory. With all three hidden, the free space can show SkyBlock info (your level, skills and Catacombs).",
                    master = toggle("Clean up inventory", null, { cfg.general.cleanUpInventory }, { v -> general { copy(cleanUpInventory = v) } }, d.general.cleanUpInventory),
                    simple = listOf(book, offhand, grid), parts = listOf(book, offhand, grid),
                    synonyms = listOf("recipe book", "offhand", "off hand", "crafting grid", "2x2", "clutter"),
                )
            },
            card("sbinfo") {
                FeatureCard(
                    "sbinfo", "SkyBlock info", "Your level, skills and Catacombs where the crafting grid was", "inventory", "inventory", "chart",
                    "Progress bars for what you pick, in the space the hidden crafting grid, recipe book and off-hand slot leave.",
                    master = toggle("SkyBlock info", null, { cfg.inventoryInfo.enabled }, { v -> ConfigManager.update { copy(inventoryInfo = inventoryInfo.copy(enabled = v)) } }, d.inventoryInfo.enabled),
                    simple = listOf(action("What to show", "Pick and order the bars, with a live preview", "Customize", "openInventoryInfo")),
                    synonyms = listOf("skill average", "level", "catacombs", "stats"),
                    blockedBy = { if (InventoryInfo.requirementsMet()) null else "Needs the crafting grid, recipe book and off-hand slot hidden" },
                )
            },
            card("equipment") {
                FeatureCard(
                    "equipment", "Equipment display", "Necklace, cloak, belt and gloves beside your armor", "inventory", "inventory", "amulet",
                    "Loaded from your profile and kept current whenever /equipment or /loadouts is open.",
                    master = toggle("Equipment display", null, { cfg.general.equipmentDisplay }, { v -> general { copy(equipmentDisplay = v) } }, d.general.equipmentDisplay),
                    simple = listOf(toggle("Join inventory frame", "Extend the inventory's border around the slots so they look like part of it", { cfg.general.equipmentJoinFrame }, { v -> general { copy(equipmentJoinFrame = v) } }, d.general.equipmentJoinFrame)),
                    synonyms = listOf("equipment", "necklace", "cloak", "belt", "gloves", "gauntlet"),
                )
            },
            card("invbuttons") {
                FeatureCard(
                    "invbuttons", "Inventory buttons", "Command buttons around your inventory and menus", "inventory", "inventory", "grid",
                    "Place, edit and remove buttons on a live preview of your inventory. Right-click a button in game to jump to the editor.",
                    master = null, openAction = "openInventoryButtons",
                    simple = listOf(
                        action("Buttons", "Place, edit and remove them on a live preview", "Open editor", "openInventoryButtons"),
                        toggle("Show outer buttons", "Buttons at the screen edges, outside the two rings. Off hides them; they come back when switched on",
                            { cfg.inventoryButtons.showOuterButtons }, { v -> ConfigManager.update { copy(inventoryButtons = inventoryButtons.copy(showOuterButtons = v)) } }, d.inventoryButtons.showOuterButtons),
                    ),
                    synonyms = listOf("buttons", "shortcuts", "commands", "warp"),
                )
            },
            // Inventory & Items › Tooltips
            card("prices") {
                val p = { cfg.prices }
                fun prices(block: com.soulreturns.config.PricesConfig.() -> com.soulreturns.config.PricesConfig) = ConfigManager.update { copy(prices = prices.block()) }
                FeatureCard(
                    "prices", "Price tooltips", "Live market values under every SkyBlock item", "inventory", "tooltips", "tag",
                    "Adds market lines from the Soul backend under SkyBlock items in every inventory and menu. Items without market data stay untouched.",
                    master = toggle("Price tooltips", null, { p().tooltips }, { v -> prices { copy(tooltips = v) } }, d.prices.tooltips),
                    simple = listOf(
                        CheckboxGroupOption(
                            "Lines", "Value is the fair price; time to sell and volume come from the last week",
                            listOf(
                                check("Value", { p().fairValue }, { v -> prices { copy(fairValue = v) } }, d.prices.fairValue),
                                check("Bazaar buy/sell", { p().bazaar }, { v -> prices { copy(bazaar = v) } }, d.prices.bazaar),
                                check("Lowest BIN", { p().lowestBin }, { v -> prices { copy(lowestBin = v) } }, d.prices.lowestBin),
                                check("24h trend", { p().trend }, { v -> prices { copy(trend = v) } }, d.prices.trend),
                                check("Time to sell / volume", { p().sellSpeed }, { v -> prices { copy(sellSpeed = v) } }, d.prices.sellSpeed),
                                check("Stack total", { p().stackTotal }, { v -> prices { copy(stackTotal = v) } }, d.prices.stackTotal),
                            ),
                        ),
                    ),
                    advanced = listOf(toggle("Hold Shift for details", "Only value and stack total until Shift is held", { p().shiftForDetails }, { v -> prices { copy(shiftForDetails = v) } }, d.prices.shiftForDetails)),
                    synonyms = listOf("price", "prices", "bazaar", "bin", "lbin", "lowest bin", "value", "worth", "auction"),
                )
            },
            // Inventory & Items › Sacks
            card("sacks") {
                FeatureCard(
                    "sacks", "Sack tracker", "Middle-click sack items to track amount and rate", "inventory", "sacks", "sack",
                    "Middle-click an item in any sack to add or remove it; the panel shows the amount in your sacks, the change this session and per hour.",
                    master = toggle("Sack tracker", null, { cfg.sacks.hud }, { v ->
                        ConfigManager.update { copy(sacks = sacks.copy(hud = v)) }
                        if (v) SackState.prefill()
                    }, d.sacks.hud),
                    simple = listOf(position("the panel")),
                    advanced = background("the panel", { cfg.sacks.hudBackground }, { v -> ConfigManager.update { copy(sacks = sacks.copy(hudBackground = v)) } }, d.sacks.hudBackground,
                        { cfg.sacks.hudBackgroundColor }, { v -> ConfigManager.update { copy(sacks = sacks.copy(hudBackgroundColor = v)) } }, d.sacks.hudBackgroundColor),
                    contexts = listOf(Detector.SACK_MENU), hud = true,
                    synonyms = listOf("sacks", "gemstones", "rate", "per hour", "tracker"),
                )
            },
            // Everyday › HUD: Friends & Contests (no master: it does nothing until you add a friend or join a contest)
            card("contests") {
                val anims = listOf("full", "small", "off")
                val variants = listOf("podium", "rollup")
                FeatureCard(
                    "contests", "Friends & Contests", "Soul friends, and contests like most Nucleus profit in 50 runs", "everyday", "hud", "trophy",
                    "Add Soul friends by name and compete in contests: the server prices every run with prices frozen at the start and keeps the leaderboard. Soul records runs only while you play in a running contest, on the profile you joined with. /soul contests, /soul friends, /soul friends add <name>.",
                    master = null, openAction = "openContests",
                    simple = listOf(
                        toggle("Contest HUD", "Only in the Crystal Hollows, while you are in a running contest", { cfg.contests.hud }, { v -> contests { copy(hud = v) } }, d.contests.hud),
                        toggle("Toasts", "Someone passes you, someone finishes", { cfg.contests.toasts }, { v -> contests { copy(toasts = v) } }, d.contests.toasts),
                        cycle("Contest end animation", "Full results reveal, a compact banner, or a chat line and a toast",
                            { anims.indexOf(cfg.contests.endAnimation).coerceAtLeast(0) }, { i -> contests { copy(endAnimation = anims[i]) } },
                            listOf("Full", "Small", "Off"), anims.indexOf(d.contests.endAnimation)),
                        toggle("Friends can see where I am", "Area and spot from Soul; off shows them only that you are online", { cfg.contests.shareArea },
                            { v -> com.soulreturns.features.contests.ContestsStore.setShareArea(v) }, d.contests.shareArea),
                        position("the contest HUD"),
                    ),
                    advanced = listOf(
                        cycle("Full animation", "The reveal the Full setting plays", { variants.indexOf(cfg.contests.fullVariant).coerceAtLeast(0) },
                            { i -> contests { copy(fullVariant = variants[i]) } }, listOf("Podium", "Roll-up"), variants.indexOf(d.contests.fullVariant)),
                        toggle("Expanded HUD", "Every player instead of the ones around you", { cfg.contests.hudExpanded }, { v -> contests { copy(hudExpanded = v) } }, d.contests.hudExpanded),
                    ),
                    hud = true,
                    synonyms = listOf("contests", "friends", "contest", "competition", "leaderboard", "nucleus", "compete"),
                )
            },
            // Everyday › HUD
            card("effects") {
                val hudPart = toggle("In the HUD", "The effect icons in the top-right corner", { cfg.general.hideEffectsHud }, { v -> general { copy(hideEffectsHud = v) } }, d.general.hideEffectsHud)
                val invPart = toggle("Beside the inventory", "The effect list next to your inventory", { cfg.general.hideEffectsInventory }, { v -> general { copy(hideEffectsInventory = v) } }, d.general.hideEffectsInventory)
                FeatureCard(
                    "effects", "Hide potion effects", "In the corner of your screen and beside the inventory", "everyday", "hud", "flask",
                    "Pick where they go away.",
                    master = toggle("Hide potion effects", null, { cfg.general.hideEffects }, { v -> general { copy(hideEffects = v) } }, d.general.hideEffects),
                    simple = listOf(hudPart, invPart), parts = listOf(hudPart, invPart),
                    synonyms = listOf("potion", "effects", "god potion", "clutter"),
                )
            },
            card("actionbar") {
                FeatureCard(
                    "actionbar", "Action bar", "Health, defense and mana as a movable HUD", "everyday", "hud", "bar",
                    "Draws the line above the hotbar with the HUD font instead of Minecraft's. It starts where vanilla puts it.",
                    master = toggle("Action bar", null, { cfg.general.soulActionBar }, { v -> general { copy(soulActionBar = v) } }, d.general.soulActionBar),
                    simple = listOf(position("the action bar")),
                    hud = true, synonyms = listOf("action bar", "health", "mana", "defense"),
                )
            },
            card("scoreboard") {
                fun sb(block: com.soulreturns.config.ScoreboardConfig.() -> com.soulreturns.config.ScoreboardConfig) = ConfigManager.update { copy(scoreboard = scoreboard.block()) }
                FeatureCard(
                    "scoreboard", "Scoreboard", "SkyBlock's sidebar drawn by Soul, with the lines you pick", "everyday", "hud", "list",
                    "Draws the scoreboard on the right with the HUD font in place of Hypixel's sidebar, which it hides while on. Lines Hypixel adds after an update show at the bottom.",
                    master = toggle("Scoreboard", null, { cfg.scoreboard.enabled }, { v -> sb { copy(enabled = v) } }, d.scoreboard.enabled),
                    simple = listOf(
                        action("Lines", "Pick, order and style the lines on a live preview (/soul scoreboard)", "Set up", "openScoreboardSetup"),
                        position("the scoreboard"),
                    ),
                    advanced = listOf(
                        toggle("Keep Hypixel's sidebar too", "Off: the Soul scoreboard replaces it", { cfg.scoreboard.keepVanilla }, { v -> sb { copy(keepVanilla = v) } }, d.scoreboard.keepVanilla),
                    ),
                    hud = true, synonyms = listOf("scoreboard", "sidebar", "custom scoreboard", "purse", "bits", "objective"),
                )
            },
            card("pethud") {
                val p = { cfg.petHud }
                fun pet(block: com.soulreturns.config.PetHudConfig.() -> com.soulreturns.config.PetHudConfig) = ConfigManager.update { copy(petHud = petHud.block()) }
                val compact = { p().style == 1 }
                val t = touch()
                FeatureCard(
                    "pethud", "Pet HUD", "Your active pet with its level, XP bar and XP per hour", "everyday", "hud", "paw",
                    "Follows autopet, summons and the tab Pet widget. Held items come from your profile and update when you open /pets.",
                    master = toggle("Pet HUD", null, { p().enabled }, { v -> pet { copy(enabled = v) } }, d.petHud.enabled),
                    simple = listOf(
                        position("the pet HUD"),
                        cycle("Style", null, { p().style }, { v -> pet { copy(style = v) } }, listOf("Card", "Compact"), d.petHud.style),
                        cycle("Without a pet", null, { p().noPet }, { v -> pet { copy(noPet = v) } }, listOf("Show \"No pet\"", "Hide"), d.petHud.noPet),
                        CheckboxGroupOption(
                            "Show", "The name always shows",
                            listOf(
                                check("Icon", { p().icon }, { v -> pet { copy(icon = v) } }, d.petHud.icon),
                                check("Level", { p().level }, { v -> pet { copy(level = v) } }, d.petHud.level),
                                check("XP bar", { p().xpBar }, { v -> pet { copy(xpBar = v) } }, d.petHud.xpBar),
                                check("Percent", { p().percent }, { v -> pet { copy(percent = v) } }, d.petHud.percent),
                                check("Held item", { p().heldItem }, { v -> pet { copy(heldItem = v) } }, d.petHud.heldItem),
                                check("XP per hour", { p().xpPerHour }, { v -> pet { copy(xpPerHour = v) } }, d.petHud.xpPerHour),
                            ),
                        ),
                    ),
                    advanced = listOf(
                        toggle("XP numbers (cur / need)", "Card only", { p().xpNumbers }, { v -> pet { copy(xpNumbers = v) } }, d.petHud.xpNumbers, disabled = compact),
                        toggle("Time to next level", null, { p().timeToLevel }, { v -> pet { copy(timeToLevel = v) } }, d.petHud.timeToLevel),
                        toggle("Overflow XP at max", "Card only", { p().overflow }, { v -> pet { copy(overflow = v) } }, d.petHud.overflow, disabled = compact),
                        toggle("Name in rarity color", null, { p().rarityName }, { v -> pet { copy(rarityName = v) } }, d.petHud.rarityName),
                        toggle("Skin star", null, { p().skinStar }, { v -> pet { copy(skinStar = v) } }, d.petHud.skinStar),
                        toggle("Progress to max level", "Card only", { p().progressToMax }, { v -> pet { copy(progressToMax = v) } }, d.petHud.progressToMax, disabled = compact),
                        toggle("Swap / level-up badges", null, { p().badges }, { v -> pet { copy(badges = v) } }, d.petHud.badges),
                        toggle("Animations", null, { p().animations }, { v -> pet { copy(animations = v) } }, d.petHud.animations),
                        CycleOption("Number format", "Card only", { p().numberFormat }, { v -> pet { copy(numberFormat = v) }; t() }, listOf("196.2k", "196,209"), d.petHud.numberFormat,
                            disabled = { compact() || (!p().xpNumbers && !p().overflow) }),
                        cycle("Bar color", null, { p().barMode }, { v -> pet { copy(barMode = v) } }, listOf("Fixed", "Rarity"), d.petHud.barMode),
                        ColorOption("Color", "The XP bar's fill; chroma joins the rainbow every chroma color in Soul shares", { p().barColor }, { v -> pet { copy(barColor = v) }; t() }, d.petHud.barColor, false, disabled = { p().barMode == 1 }),
                    ) + background("the pet HUD", { p().background }, { v -> pet { copy(background = v) } }, d.petHud.background,
                        { p().backgroundColor }, { v -> pet { copy(backgroundColor = v) } }, d.petHud.backgroundColor),
                    hud = true,
                    synonyms = listOf("pet", "autopet", "pet xp", "pet level", "taming", "golden dragon", "held item"),
                )
            },
            card("petmark") {
                fun pet(block: com.soulreturns.config.PetHudConfig.() -> com.soulreturns.config.PetHudConfig) = ConfigManager.update { copy(petHud = petHud.block()) }
                FeatureCard(
                    "petmark", "Highlight active pet", "The summoned pet stands out in the Pets menu", "everyday", "hud", "paw",
                    "In the Pets menu, the pet that reads \"Click to despawn!\" gets a colored mark. Only the page you are on is marked.",
                    master = toggle("Highlight active pet", null, { cfg.petHud.highlightActive }, { v -> pet { copy(highlightActive = v) } }, d.petHud.highlightActive),
                    simple = listOf(
                        ColorOption("Color", null, { cfg.petHud.highlightColor }, { v -> pet { copy(highlightColor = v) } }, d.petHud.highlightColor, false),
                    ),
                    synonyms = listOf("pet", "pets menu", "active pet", "summoned", "despawn", "highlight"),
                )
            },
            card("helditem") {
                FeatureCard(
                    "helditem", "Hide held item name", "No item name floating over the hotbar", "everyday", "hud", "eyeoff",
                    "Hides the name shown above the hotbar when you select or switch an item.",
                    master = toggle("Hide held item name", null, { cfg.general.hideHeldItemTooltip }, { v -> general { copy(hideHeldItemTooltip = v) } }, d.general.hideHeldItemTooltip),
                    synonyms = listOf("tooltip", "item name", "hotbar"),
                )
            },
            // Everyday › Controls
            // The secret Auto meow (S-70, `mocks/meow/`): in no list, search or What's new until found
            // (features/chat/AutoMeow: "meow" typed into the search, or /soul meow).
            card("meow") {
                fun meowCfg(block: com.soulreturns.config.AutoMeowConfig.() -> com.soulreturns.config.AutoMeowConfig) = ConfigManager.update { copy(meow = meow.block()) }
                FeatureCard(
                    "meow", "Auto meow", "Meows back when someone meows in chat", "everyday", "controls", "paw",
                    "When someone writes exactly \"meow\" in a chat you pick, Soul answers \"meow\" in the same chat. Just the word, never with a [Soul] tag. One answer, then every meow is ignored for 20 seconds.",
                    master = toggle("Auto meow", null, { cfg.meow.enabled }, { v -> meowCfg { copy(enabled = v) } }, d.meow.enabled),
                    simple = listOf(
                        toggle("Co-op chat", "Answers with /cc", { cfg.meow.coop }, { v -> meowCfg { copy(coop = v) } }, d.meow.coop),
                        toggle("Party chat", "Answers with /pc", { cfg.meow.party }, { v -> meowCfg { copy(party = v) } }, d.meow.party),
                        toggle("Private messages", "Answers the sender with /r", { cfg.meow.privateMessages }, { v -> meowCfg { copy(privateMessages = v) } }, d.meow.privateMessages),
                        toggle("Guild chat", "Answers with /gc", { cfg.meow.guild }, { v -> meowCfg { copy(guild = v) } }, d.meow.guild),
                        toggle("All chat", "Answers with /ac", { cfg.meow.all }, { v -> meowCfg { copy(all = v) } }, d.meow.all),
                    ),
                    synonyms = listOf("meow", "cat", "secret"),
                    secret = true,
                )
            },
            card("sprint") {
                val indicator = { cfg.general.toggleSprintHud }
                FeatureCard(
                    "sprint", "Toggle sprint", "Sprint stays on, no double-tapping", "everyday", "controls", "run",
                    "Keeps sprint held down so it never drops. Optionally shows \"Sprint toggled\" on screen while it is on.",
                    master = toggle("Toggle sprint", null, { cfg.general.toggleSprint }, { v -> general { copy(toggleSprint = v) } }, d.general.toggleSprint),
                    simple = listOf(
                        keybind("Toggle sprint key", "Switches Toggle sprint on and off in game (Esc while binding = unbind)", { cfg.general.toggleSprintKey }, { v -> general { copy(toggleSprintKey = v) } }, d.general.toggleSprintKey),
                        hudTag(toggle("Show indicator", "\"Sprint toggled\" on screen while it is on", indicator, { v -> general { copy(toggleSprintHud = v) } }, d.general.toggleSprintHud)),
                        position("the indicator", visible = indicator),
                        color("Indicator text color", null, { cfg.general.toggleSprintHudColor }, { v -> general { copy(toggleSprintHudColor = v) } }, d.general.toggleSprintHudColor, alpha = false, visible = indicator),
                    ),
                    advanced = listOf(
                        toggle("Toggle sprint underwater", "Also keep sprinting while underwater (fast swimming)", { cfg.general.toggleSprintUnderwater }, { v -> general { copy(toggleSprintUnderwater = v) } }, d.general.toggleSprintUnderwater),
                    ) + background("the indicator", { cfg.general.toggleSprintHudBackground }, { v -> general { copy(toggleSprintHudBackground = v) } }, d.general.toggleSprintHudBackground,
                        { cfg.general.toggleSprintHudBackgroundColor }, { v -> general { copy(toggleSprintHudBackgroundColor = v) } }, d.general.toggleSprintHudBackgroundColor, visible = indicator),
                    synonyms = listOf("sprint", "run", "auto sprint", "indicator", "sprint hud"),
                )
            },
            card("noplace") {
                FeatureCard(
                    "noplace", "Don't place ability items", "Right-click uses the ability, never places the block", "everyday", "controls", "hand",
                    "Fire Freeze Staff, Turbo Fishing Net, Summoning Ring and other ability items backed by a placeable block.",
                    master = toggle("Don't place ability items", null, { cfg.general.preventAbilityItemPlace }, { v -> general { copy(preventAbilityItemPlace = v) } }, d.general.preventAbilityItemPlace),
                    synonyms = listOf("fire freeze", "summoning ring", "turbo net", "place"),
                )
            },
            // Everyday › Camera & view
            card("frontcam") {
                FeatureCard(
                    "frontcam", "No front camera", "F5 skips the view of your own face", "everyday", "view", "camera",
                    "Skips the front-facing view when you cycle the perspective.",
                    master = toggle("No front camera", null, { cfg.general.noFrontCamera }, { v -> general { copy(noFrontCamera = v) } }, d.general.noFrontCamera),
                    synonyms = listOf("f5", "perspective", "camera", "third person"),
                )
            },
            card("foliage") {
                FeatureCard(
                    "foliage", "Hide foliage", "Grass, ferns, flowers and bushes disappear", "everyday", "view", "leaf",
                    "Invisible and silent; mobs in foliage stay hittable. Off on your Private Island and the Garden, crops are never touched.",
                    master = toggle("Hide foliage", null, { cfg.general.hideFoliage }, { v -> HideFoliage.setEnabled(v) }, d.general.hideFoliage),
                    synonyms = listOf("grass", "flowers", "fps", "lag", "clutter"),
                )
            },
            card("hidefire") {
                FeatureCard(
                    "hidefire", "Hide fire", "No flames on your screen or on burning entities", "everyday", "view", "eyeoff",
                    "Purely visual: you still burn, take damage and hear the sounds. Other screen overlays (water, in a block, pumpkin, portal) stay.",
                    master = null,
                    simple = listOf(
                        toggle("Hide fire overlay", "The fire covering your screen while you burn", { cfg.general.hideFireOverlay }, { v -> general { copy(hideFireOverlay = v) } }, d.general.hideFireOverlay),
                        toggle("Hide fire on entities", "Burning players and mobs, and you in third person", { cfg.general.hideEntityFire }, { v -> general { copy(hideEntityFire = v) } }, d.general.hideEntityFire),
                    ),
                    synonyms = listOf("fire", "flames", "burning", "overlay", "clutter"),
                )
            },
        )
    }

    private val cardById: Map<String, FeatureCard> by lazy { allCards.associateBy { it.id } }

    /** Rows that carry the HUD chip in their label (a panel row that adds a HUD to its card). */
    val hudTagged: Set<ConfigOption> get() = hudTaggedRows

    private val hudTaggedRows: MutableSet<ConfigOption> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    private fun <T : ConfigOption> hudTag(opt: T): T = also { hudTaggedRows.add(opt) }.let { opt }

    // ---------------------------------------------------------------- Settings (Soul's own)
    val settingsTabs: List<SettingsTab> by lazy {
        listOf(
            SettingsTab(
                "interface", "Interface", "monitor",
                listOf(
                    SettingsSection(
                        "scale", "Scale", "Menus and HUDs use this scale instead of Minecraft's GUI Scale",
                        listOf(
                            SliderOption(
                                UI_SCALE, "Applies with the Apply button so the menu does not jump while you drag",
                                { SoulUi.scale.toDouble() }, { v -> general { copy(uiScale = v.toFloat()) } }, AutoUiScale.forCurrentMonitor().toDouble(),
                                SoulUi.MIN_SCALE.toDouble(), SoulUi.MAX_SCALE.toDouble(), 0.1, applyImmediately = false,
                            ).syn("gui scale", "size", "zoom", "bigger", "smaller"),
                            action(
                                "Auto size", "Picks the size that fits this monitor from its resolution and saves it. It stays that size until you change it",
                                "Auto", AUTO_UI_SCALE,
                            ).syn("auto scale", "monitor", "resolution", "fit screen"),
                        ),
                    ),
                    SettingsSection(
                        "font", "Font", "Separate for this menu and for in-game HUDs",
                        listOf(
                            CycleOption("Menu font", "Vector fonts only: the Minecraft font cannot be used here", { cfg.general.menuFontChoice }, { v -> general { copy(menuFontChoice = v) } },
                                listOf("Baloo 2", "Nunito", "Inter"), d.general.menuFontChoice).syn("font"),
                            CycleOption("HUD font", "Minecraft = your texture pack's font (ASCII only)", { cfg.general.fontChoice }, { v -> general { copy(fontChoice = v) } },
                                listOf("Baloo 2", "Nunito", "Inter", "Minecraft"), d.general.fontChoice).syn("font"),
                        ),
                        advanced = listOf(
                            ToggleOption("Use bold font", "Each HUD can override it in the Move GUI", { cfg.general.hudBoldFont }, { v -> general { copy(hudBoldFont = v) } }, d.general.hudBoldFont),
                            ToggleOption("HUD text shadow", "Each HUD can override it in the Move GUI", { cfg.general.hudTextShadow }, { v -> general { copy(hudTextShadow = v) } }, d.general.hudTextShadow),
                        ),
                    ),
                    SettingsSection(
                        "chroma", "Chroma", "How every chroma color in Soul moves; turn chroma on in any color picker",
                        listOf(
                            ChromaOptions.motion().syn("rainbow", "chroma", "color"),
                            ChromaOptions.speed().syn("rainbow", "chroma speed"),
                        ),
                    ),
                    SettingsSection(
                        "menu", "This menu", "How the config menu itself behaves",
                        listOf(
                            ToggleOption("Compact sidebar", "Icons only; hover the sidebar to see the names", { cfg.menu.sidebarCollapsed }, { v -> MenuState.set { copy(sidebarCollapsed = v) } }, false).syn("sidebar"),
                            ToggleOption("Always show Advanced", "Opens every Advanced fold; the same switch sits top right", { cfg.menu.alwaysAdvanced }, { v -> MenuState.set { copy(alwaysAdvanced = v) } }, false),
                            action("What do you play?", "The first-launch question again", "Ask again", "replayOnboarding"),
                        ),
                    ),
                ),
            ),
            SettingsTab(
                "huds", "HUDs", "move",
                listOf(
                    SettingsSection(
                        "movegui", "Move GUI", "The editor that places every HUD (/soul gui); the same settings are under its cog",
                        listOf(action("Move GUI", "Drag, scale, anchor and link every Soul HUD", "Open", MOVE_GUI)),
                        advanced = listOf(
                            SliderOption("Edge snap", "Snap line this far inside each screen edge", { cfg.general.moveGui.edgeSnap.toDouble() }, { v -> moveGui { copy(edgeSnap = v.toFloat()) } }, d.general.moveGui.edgeSnap.toDouble(), 0.0, 20.0, 0.5),
                            SliderOption("Link gap", "Space a new link leaves between a HUD and what it attaches to", { cfg.general.moveGui.linkGap.toDouble() }, { v -> moveGui { copy(linkGap = v.toFloat()) } }, d.general.moveGui.linkGap.toDouble(), 0.0, 40.0, 0.5),
                            ToggleOption("Confirm before detaching", "Ask when the link icon on a HUD is clicked", { cfg.general.moveGui.confirmDetach }, { v -> moveGui { copy(confirmDetach = v) } }, d.general.moveGui.confirmDetach),
                            ToggleOption("Open settings when selecting", "Off: right-click a HUD (or its … button) to open them", { cfg.general.moveGui.openOnSelect }, { v -> moveGui { copy(openOnSelect = v) } }, d.general.moveGui.openOnSelect),
                        ),
                    ),
                    SettingsSection(
                        "invhuds", "HUDs in the inventory", "Trackers and panels you can click while your inventory is open",
                        listOf(ToggleOption("Show controls on hover", "Tabs, filters and dropdowns only while the mouse is over the panel", { cfg.general.trackerControlsOnHover }, { v -> general { copy(trackerControlsOnHover = v) } }, d.general.trackerControlsOnHover)),
                    ),
                ),
                synonyms = listOf("hud editor", "position", "move gui"),
            ),
            SettingsTab(
                "sounds", "Sounds", "music",
                listOf(
                    SettingsSection(
                        "library", "Sound library", "Minecraft's sounds and your own .ogg, .wav and .mp3 files, for every feature that plays one",
                        listOf(action("Sound library", "Browse and preview sounds, keep favorites, import your own files", "Open library", "openSoundLibrary")),
                    ),
                    SettingsSection(
                        "replacer", "Sound replacer", "Mute a sound or play another one in its place",
                        listOf(
                            ToggleOption("Sound replacer", "Applies your replacer rules", { cfg.sounds.replacerEnabled }, { v -> ConfigManager.update { copy(sounds = sounds.copy(replacerEnabled = v)) } }, d.sounds.replacerEnabled),
                            action("Rules", "Find a sound by ear in what you just heard, then mute or replace it", "Open sound replacer", "openSoundReplacer"),
                        ),
                    ),
                ),
            ),
            SettingsTab(
                "shortcuts", "Shortcuts", "keys",
                listOf(
                    SettingsSection(
                        "cmdkeys", "Command keybinds", "Run commands with keyboard or mouse shortcuts",
                        listOf(
                            ToggleOption("Command keybinds", "Off pauses every binding; their keys stay bound", { cfg.commandKeybinds.enabled },
                                { v -> ConfigManager.update { copy(commandKeybinds = commandKeybinds.copy(enabled = v)) } }, d.commandKeybinds.enabled).syn("hotkey", "macro", "shortcut", "keybind"),
                            action("Bindings", "Bind Hypixel menus and your own commands; conflicts show in the editor", "Open editor", "openCommandKeybinds"),
                        ),
                        advanced = listOf(
                            ToggleOption("Ignore debug key conflicts", "Vanilla debug keys only work with F3 held, so sharing one is not shown as a conflict", { cfg.commandKeybinds.ignoreDebugKeyConflicts },
                                { v -> ConfigManager.update { copy(commandKeybinds = commandKeybinds.copy(ignoreDebugKeyConflicts = v)) } }, d.commandKeybinds.ignoreDebugKeyConflicts),
                        ),
                    ),
                    SettingsSection(
                        "pause", "Pause menu", "The menu Escape opens in a world",
                        listOf(ToggleOption("Pause menu buttons", "\"Soul GUI\" and \"Soul Config\" in the bottom left corner", { cfg.general.pauseMenuButtons }, { v -> general { copy(pauseMenuButtons = v) } }, d.general.pauseMenuButtons)),
                    ),
                ),
                synonyms = listOf("keybinds", "hotkeys"),
            ),
            SettingsTab(
                "chat", "Chat & notifications", "chat",
                listOf(
                    SettingsSection(
                        "partychat", "Party chat", "What Soul itself says in /pc",
                        listOf(
                            ToggleOption("Prefix outgoing messages", "Puts [Soul] before conversational /pc sends; protocol messages (!ptme, coordinates) stay raw for other mods",
                                { cfg.general.prefixOutgoingMessages }, { v -> general { copy(prefixOutgoingMessages = v) } }, d.general.prefixOutgoingMessages).syn("party chat", "prefix", "pc"),
                        ),
                    ),
                    notificationsSection(),
                ),
            ),
            SettingsTab(
                "profile", "Profile Viewer", "user",
                listOf(
                    SettingsSection(
                        "spv", "Profile Viewer", "/sp <name> [profile] looks up any player's SkyBlock profile",
                        listOf(
                            ToggleOption("Enable /sp", "Turns /sp, /soul sp and Soul's optional /pv on or off", { cfg.profileViewer.enabled }, { v -> ConfigManager.update { copy(profileViewer = profileViewer.copy(enabled = v)) } }, d.profileViewer.enabled),
                            ToggleOption("Use /pv", "Replaces other mods' /pv with Soul's Profile Viewer while on. Off returns /pv to other mods or the server immediately", { cfg.profileViewer.pvCommand }, { v -> ConfigManager.update { copy(profileViewer = profileViewer.copy(pvCommand = v)) } }, d.profileViewer.pvCommand),
                        ),
                    ),
                    SettingsSection(
                        "sharing", "Sharing", "What other Soul users may see about you",
                        listOf(
                            ToggleOption(
                                "Share my Soul stats", "Mineshaft visits, corpses found and later trackers, when they open your profile. You always see your own",
                                { cfg.profileViewer.shareSoulStats },
                                { v ->
                                    ConfigManager.update { copy(profileViewer = profileViewer.copy(shareSoulStats = v)) }
                                    // The backend learns this from presence; nudge it now so the switch takes effect when flipped.
                                    PresenceService.onPresencePreferenceChanged()
                                },
                                d.profileViewer.shareSoulStats,
                            ),
                        ),
                    ),
                ),
            ),
            SettingsTab(
                "about", "About", "info",
                listOf(
                    updatesSection(),
                    SettingsSection(
                        "software", "Used software", "What Soul builds on, thanks to their authors",
                        UsedSoftware.entries.map { source(it.name, it.text, it.url) },
                    ),
                ),
            ),
            devTab(),
        )
    }

    /** Settings tabs as shown: Dev only while developer access is unlocked. */
    fun visibleSettingsTabs(): List<SettingsTab> = settingsTabs.filter { it.visible() }

    private fun source(name: String, what: String, url: String) = ActionOption(name, what, "Source", url = url)

    private fun updatesSection(): SettingsSection {
        val u = { cfg.updates }
        val busy = { com.soulreturns.platform.updater.UpdateService.snapshot.phase == com.soulreturns.platform.updater.UpdatePhase.DOWNLOADING }
        fun updates(block: com.soulreturns.config.UpdatesConfig.() -> com.soulreturns.config.UpdatesConfig) = ConfigManager.update { copy(updates = updates.block()) }
        return SettingsSection(
            "updates", "Updates", null,
            listOf(
                CycleOption("Update channel", "Beta gets new features days earlier and may have bugs. Stable is what most players use. Never a downgrade", { if (u().channel == "beta") 1 else 0 },
                    { v -> updates { copy(channel = if (v == 1) "beta" else "stable") }; com.soulreturns.platform.updater.UpdateService.checkNow() },
                    listOf("Stable", "Beta"), if (d.updates.channel == "beta") 1 else 0, disabled = busy).syn("update", "version", "beta"),
                ToggleOption("Install updates automatically", "New versions download in the background and load the next time you start Minecraft. You still get a chat line",
                    { u().autoInstall }, { v -> updates { copy(autoInstall = v) } }, d.updates.autoInstall).syn("auto update"),
                ActionOption("Check for updates", "Soul also checks when you start the game and every hour", "Check now", "updateCheck", disabled = busy),
                action("Skipped version", "You will not be reminded about it. Newer versions still show", "Unskip", "updateUnskip", visible = { u().skippedVersion.isNotBlank() }),
                action("Download page", "sky.soulreturns.com/download: the current jar and install steps, for friends who don't have Soul yet", "Copy link", "updateCopyLink").syn("download", "share", "install"),
            ),
        )
    }

    private fun notificationsSection(): SettingsSection {
        val n = { cfg.notifications }
        val toast = { n().toastEnabled }
        fun notifications(block: com.soulreturns.config.NotificationsConfig.() -> com.soulreturns.config.NotificationsConfig) = ConfigManager.update { copy(notifications = notifications.block()) }
        return SettingsSection(
            "backend", "Backend notifications", "Messages from the Soul backend: announcements, warnings",
            listOf(
                ToggleOption("Show in chat", "Visible even if you looked away", { n().chatEnabled }, { v -> notifications { copy(chatEnabled = v) } }, d.notifications.chatEnabled),
                ToggleOption("Show on-screen toast", "Also as a short overlay", toast, { v -> notifications { copy(toastEnabled = v) } }, d.notifications.toastEnabled),
                SoundOption("Notification sound", "Played when a notification arrives", "Notifications", { n().sound }, { v -> notifications { copy(sound = v) } }, d.notifications.sound),
            ),
            advanced = listOf(
                SliderOption("Toast duration", "Seconds each toast stays", { n().toastSeconds.toDouble() }, { v -> notifications { copy(toastSeconds = v.toFloat()) } }, d.notifications.toastSeconds.toDouble(), 1.0, 30.0, 1.0, visible = toast),
                position("the toast stack", visible = toast),
                ToggleOption("Background", "A card behind each toast", { n().background }, { v -> notifications { copy(background = v) } }, d.notifications.background, visible = toast),
                ColorOption("Background color", null, { n().backgroundColor }, { v -> notifications { copy(backgroundColor = v) } }, d.notifications.backgroundColor, hasAlpha = true, visible = { toast() && n().background }),
            ),
        )
    }

    /** Developer access only: hidden (not greyed out) while locked, so search never sees it. */
    private fun devTab(): SettingsTab {
        fun dev(block: com.soulreturns.config.DevConfig.() -> com.soulreturns.config.DevConfig) = ConfigManager.update { copy(dev = dev.block()) }
        fun devKeys(block: com.soulreturns.config.DevKeybindsConfig.() -> com.soulreturns.config.DevKeybindsConfig) = dev { copy(keybinds = keybinds.block()) }
        fun dungeons(block: com.soulreturns.config.DungeonsConfig.() -> com.soulreturns.config.DungeonsConfig) = ConfigManager.update { copy(dungeons = dungeons.block()) }
        val k = { cfg.dev.keybinds }
        return SettingsTab(
            "dev", "Dev", "tools",
            listOf(
                SettingsSection(
                    "access", "Developer access", "Here because developer access is unlocked on this client. The wrong value here breaks features quietly rather than loudly.",
                    listOf(action("Lock developer mode", "Hide this tab, /soul dev and the dev keybinds again on this client", "Lock", "lockDevAccess")),
                ),
                SettingsSection(
                    "logging", "Logging", null,
                    listOf(
                        ToggleOption("Debug logging", "Print info/debug lines to the console", { cfg.dev.debugMode }, { v -> dev { copy(debugMode = v) } }, d.dev.debugMode),
                        ToggleOption("Sync / WebSocket logging", "Trace every secret-sync + websocket action (auth, connect, subscribe, publish, receive)", { cfg.dev.logWebsocket }, { v -> dev { copy(logWebsocket = v) } }, d.dev.logWebsocket),
                    ),
                ),
                SettingsSection(
                    "backend", "Backend", "Blank = default. -Dsoul.backendUrl / SOUL_BACKEND_URL win unless you Apply.",
                    listOf(
                        TextOption("Backend URL", null, { cfg.dev.backendUrl }, { v -> dev { copy(backendUrl = v.trim()) } }, d.dev.backendUrl),
                        ToggleOption("Realtime always on", "Keep the realtime (SSE) connection open off Hypixel too, for testing against a local backend", { cfg.dev.realtimeAlwaysOn }, { v -> dev { copy(realtimeAlwaysOn = v) } }, d.dev.realtimeAlwaysOn),
                        ToggleOption(
                            "Share presence",
                            "Report where you are to the Soul backend: the server address, whether you are on SkyBlock, your area, sublocation and profile name. Other backend features are built on this.",
                            { cfg.dev.presence.enabled }, { v -> dev { copy(presence = presence.copy(enabled = v)) } }, d.dev.presence.enabled,
                        ),
                        action("Apply & reconnect", "Reconnect and re-authenticate against the URL above. Overrides -Dsoul.backendUrl / SOUL_BACKEND_URL for this session.", "Apply", "applyBackendUrl"),
                    ),
                ),
                SettingsSection(
                    "dungeondebug", "Dungeon debug overlay", "Compact map/sync debug panel with its own position and scale",
                    listOf(
                        ToggleOption("Debug overlay", null, { cfg.dungeons.debugHudEnabled }, { v -> dungeons { copy(debugHudEnabled = v) } }, d.dungeons.debugHudEnabled),
                        SliderOption("Debug overlay X", null, { cfg.dungeons.debugHudX.toDouble() }, { v -> dungeons { copy(debugHudX = v.toFloat()) } }, d.dungeons.debugHudX.toDouble(), 0.0, 800.0, 1.0),
                        SliderOption("Debug overlay Y", null, { cfg.dungeons.debugHudY.toDouble() }, { v -> dungeons { copy(debugHudY = v.toFloat()) } }, d.dungeons.debugHudY.toDouble(), 0.0, 500.0, 1.0),
                        SliderOption("Debug overlay scale", null, { cfg.dungeons.debugHudScale.toDouble() }, { v -> dungeons { copy(debugHudScale = v.toFloat()) } }, d.dungeons.debugHudScale.toDouble(), 0.4, 2.0),
                    ),
                ),
                SettingsSection(
                    "devkeys", "Clipboard dumps", "Click a box, then press a key to bind (Esc while binding = unbind). Works inside GUIs.",
                    listOf(
                        KeybindOption("Copy Opened GUI", "Copies a JSON dump of the currently open container screen (chest contents, slot map, title) to the clipboard", { k().copyOpenedGui }, { v -> devKeys { copy(copyOpenedGui = v) } }),
                        KeybindOption("Copy Item Under Cursor", "Copies a JSON dump of the item the cursor is hovering inside a container", { k().copyItemUnderCursor }, { v -> devKeys { copy(copyItemUnderCursor = v) } }),
                        KeybindOption("Copy Held Item", "Copies a JSON dump of the item in the player's main hand", { k().copyHeldItem }, { v -> devKeys { copy(copyHeldItem = v) } }),
                        KeybindOption("Copy Scoreboard", "Copies the scoreboard sidebar lines (with reconstructed text) as JSON", { k().copyScoreboard }, { v -> devKeys { copy(copyScoreboard = v) } }),
                        KeybindOption("Copy Tablist", "Copies the tab list players (name, uuid, display name, latency) as JSON", { k().copyTablist }, { v -> devKeys { copy(copyTablist = v) } }),
                        KeybindOption("Copy Nearby Entities", "Copies a JSON dump of entities within 30 blocks", { k().copyNearbyEntities }, { v -> devKeys { copy(copyNearbyEntities = v) } }),
                    ),
                ),
            ),
            visible = devOnly,
        )
    }

    // ---------------------------------------------------------------- first launch
    /** "What do you play?": each answer switches on these cards (spec: `PICKS` in the mock). */
    class OnboardingPick(val id: String, val name: String, val icon: String, val cards: List<String>)

    val onboardingPicks: List<OnboardingPick> =
        listOf(
            OnboardingPick("dungeons", "Dungeons", "skull", listOf("dmap", "leavecmd")),
            OnboardingPick("mining", "Mining", "pick", listOf("nucleus", "checklist", "bal", "mdsolver", "mdloot", "corpses")),
            OnboardingPick("fishing", "Fishing", "fish", listOf("fishtracker", "bobbin", "dhsound")),
            OnboardingPick("combat", "Slayers & combat", "sword", listOf("mobhealth", "combo", "implosion")),
            OnboardingPick("dragons", "Dragons", "eye", listOf("dragons")),
            OnboardingPick("qol", "Just quality of life", "sun", listOf("sprint", "prices", "keepcursor", "sacks")),
        )
}
