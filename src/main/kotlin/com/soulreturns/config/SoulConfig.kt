package com.soulreturns.config

import com.soulreturns.ui.hud.movable.HudAnchor
import com.soulreturns.ui.hud.movable.HudLink
import com.soulreturns.ui.hud.movable.HudPlace
import com.soulreturns.ui.hud.movable.HudTargets
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.VanillaIds
import com.soulreturns.sound.ReplaceRule
import com.soulreturns.sound.SoundRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A configurable color: a base ARGB [argb] plus an optional **chroma** (SkyHanni/NEU-style) that,
 * when on, ignores the base color and animates a full-saturation rainbow whose hue cycles at
 * [speed] (higher = faster). [effective] is the color to actually draw this frame.
 *
 * Custom-serialized so a legacy plain-int config value still loads (as a non-chroma color).
 */
@Serializable(with = ChromaColorSerializer::class)
data class ChromaColor(
    val argb: Int,
    val chroma: Boolean = false,
    val speed: Float = 6f,
) {
    fun effective(): Int {
        if (!chroma) return argb
        val a = (argb ushr 24) and 0xFF
        val cycle = (20.0 / speed.coerceAtLeast(0.5f)).coerceIn(1.0, 40.0) // seconds per hue loop
        val hue = ((System.currentTimeMillis() / 1000.0 / cycle) % 1.0).toFloat()
        return (a shl 24) or (rainbow(hue) and 0xFFFFFF)
    }

    /** Full saturation/value hue → 0xFFRRGGBB. */
    private fun rainbow(h: Float): Int {
        val i = (h * 6f).toInt() % 6
        val f = h * 6f - (h * 6f).toInt()
        var r = 0f; var g = 0f; var b = 0f
        when (i) {
            0 -> { r = 1f; g = f; b = 0f }
            1 -> { r = 1f - f; g = 1f; b = 0f }
            2 -> { r = 0f; g = 1f; b = f }
            3 -> { r = 0f; g = 1f - f; b = 1f }
            4 -> { r = f; g = 0f; b = 1f }
            else -> { r = 1f; g = 0f; b = 1f - f }
        }
        return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }
}

object ChromaColorSerializer : KSerializer<ChromaColor> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ChromaColor") {
            element<Int>("argb")
            element<Boolean>("chroma")
            element<Float>("speed")
        }

    override fun serialize(encoder: Encoder, value: ChromaColor) {
        (encoder as JsonEncoder).encodeJsonElement(
            buildJsonObject {
                put("argb", value.argb)
                put("chroma", value.chroma)
                put("speed", value.speed)
            },
        )
    }

    override fun deserialize(decoder: Decoder): ChromaColor {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        // Legacy: a bare int → non-chroma color.
        if (el is JsonPrimitive) el.intOrNull?.let { return ChromaColor(it) }
        if (el is JsonObject) {
            val argb = (el["argb"] as? JsonPrimitive)?.intOrNull ?: 0
            val chroma = (el["chroma"] as? JsonPrimitive)?.booleanOrNull ?: false
            val speed = (el["speed"] as? JsonPrimitive)?.floatOrNull ?: 6f
            return ChromaColor(argb, chroma, speed)
        }
        return ChromaColor(0)
    }
}

@Serializable
data class IslandChestsConfig(
    val enabled: Boolean = false,
    val shareWithCoop: Boolean = true,
    val searchKey: String = "key.keyboard.k",
)

@Serializable
data class StorageOverlayConfig(
    val enabled: Boolean = false,
    val columns: Int = 3,
    val overrideSkyblocker: Boolean = true,
    val openLastPage: Boolean = false,
)

/**
 * The mod's configuration tree — immutable @Serializable data classes persisted as JSON.
 *
 * Defaults follow the opt-in policy: user-facing features default OFF. To mutate, use
 * [ConfigManager.update] which replaces the instance via `copy(...)` and saves. Read everywhere
 * via the top-level [cfg] accessor at point of use (never cache) so toggles apply immediately.
 *
 * New fields with defaults are forward-compatible: older config files simply omit them and the
 * default fills in (Json is configured with `ignoreUnknownKeys`).
 */
@Serializable
data class SoulConfig(
    val general: GeneralConfig = GeneralConfig(),
    val fishing: FishingConfig = FishingConfig(),
    val combo: ComboConfig = ComboConfig(),
    val dungeons: DungeonsConfig = DungeonsConfig(),
    val mining: MiningConfig = MiningConfig(),
    val combat: CombatConfig = CombatConfig(),
    val petHud: PetHudConfig = PetHudConfig(),
    val notifications: NotificationsConfig = NotificationsConfig(),
    val profileViewer: ProfileViewerConfig = ProfileViewerConfig(),
    val commandBindings: List<CommandBinding> = emptyList(),
    val commandKeybinds: CommandKeybindsConfig = CommandKeybindsConfig(),
    val inventoryButtons: InventoryButtonsConfig = InventoryButtonsConfig(),
    val inventoryInfo: InventoryInfoConfig = InventoryInfoConfig(),
    val prices: PricesConfig = PricesConfig(),
    val sacks: SackTrackerConfig = SackTrackerConfig(),
    val islandChests: IslandChestsConfig = IslandChestsConfig(),
    val storageOverlay: StorageOverlayConfig = StorageOverlayConfig(),
    val sounds: SoundsConfig = SoundsConfig(),
    /** The auto updater ([com.soulreturns.platform.updater.UpdateService]). */
    val updates: UpdatesConfig = UpdatesConfig(),
    val dev: DevConfig = DevConfig(),
    /** The config menu's own state (pins, recently changed, seen NEW, layout); not a feature setting. */
    val menu: ConfigMenuState = ConfigMenuState(),
)

/**
 * Auto updater settings. Checking is infrastructure and always on; installing without a click is
 * opt-in ([autoInstall]).
 */
@Serializable
data class UpdatesConfig(
    /** `stable` or `beta`; Beta also receives newer stable releases. Unknown values read as stable. */
    val channel: String = "stable",
    /** Download and place verified updates without asking; they apply on the next start. */
    val autoInstall: Boolean = false,
    /** A version the player skipped: not announced and not auto-installed. A newer one is. */
    val skippedVersion: String = "",
    /**
     * State, not a setting (no menu row): the one-time "turn on automatic updates?" question on the
     * title screen was answered (either way). Absent in older files = not asked yet.
     */
    val autoInstallAsked: Boolean = false,
)

/**
 * Config menu state ([com.soulreturns.config.gui.SoulConfigScreen], spec `mocks/config-menu/`).
 * Feature and Settings-tab ids are the registry's (`f:<card>`, `s:<tab>`).
 */
@Serializable
data class ConfigMenuState(
    /** Feature card ids pinned to Start, in the order the player arranged them. */
    val pins: List<String> = emptyList(),
    /** The last features toggled or edited in the menu, newest first (at most three). */
    val recent: List<String> = emptyList(),
    /** NEW items of [SeenNew.version] already seen; another version means none seen. */
    val seenNew: SeenNew = SeenNew(),
    /** Icons-only sidebar. */
    val sidebarCollapsed: Boolean = false,
    /** The global Advanced switch: every Advanced fold open. */
    val alwaysAdvanced: Boolean = false,
    /** The first-launch "What do you play?" was answered or skipped. */
    val onboardingDone: Boolean = false,
)

@Serializable
data class SeenNew(
    val version: String = "",
    val ids: List<String> = emptyList(),
)

/** Sound replacer ([com.soulreturns.sound.SoundReplacer]); rules apply only while [replacerEnabled]. */
@Serializable
data class SoundsConfig(
    val replacerEnabled: Boolean = false,
    val replaceRules: List<ReplaceRule> = emptyList(),
)

/** A custom command keybind. [enabled] false keeps the key but never fires and never conflicts. */
@Serializable
data class CommandBinding(
    val command: String = "",
    val key: String = "",
    val enabled: Boolean = true,
)

/**
 * Editor state and preset bindings beside [SoulConfig.commandBindings]. [enabled] pauses every
 * command keybind; [presets] holds only bound presets, keyed by
 * [com.soulreturns.features.keybinds.CommandPresets] id (an unknown id is kept, never fired).
 */
@Serializable
data class CommandKeybindsConfig(
    val enabled: Boolean = true,
    val presetsOpen: Boolean = true,
    val presets: Map<String, PresetBinding> = emptyMap(),
    /**
     * Conflict reporting only ([com.soulreturns.features.keybinds.DebugKeyConflicts]): vanilla's
     * debug keys need the Debug Modifier held, so they no longer count as clashing with a plain key.
     */
    val ignoreDebugKeyConflicts: Boolean = true,
)

@Serializable
data class PresetBinding(
    val key: String = "",
    val enabled: Boolean = true,
)

/**
 * Market lines appended to item tooltips ([com.soulreturns.features.prices.PriceTooltip]) from
 * [com.soulreturns.data.prices.MarketValues]. The per-line flags pick what shows; with
 * [shiftForDetails] only value and stack total show until Shift is held.
 */
@Serializable
data class PricesConfig(
    val tooltips: Boolean = false,
    val fairValue: Boolean = true,
    val bazaar: Boolean = true,
    val lowestBin: Boolean = true,
    val trend: Boolean = true,
    val sellSpeed: Boolean = true,
    val stackTotal: Boolean = true,
    val shiftForDetails: Boolean = true,
)

/**
 * The sack item tracker ([com.soulreturns.features.sacks.SackTrackerHud]). [tracked] is edited by
 * middle-clicking items in a sack menu and by the panel's per-row remove control; the counts
 * themselves live per profile in `stats.json` ([com.soulreturns.features.sacks.SackState]).
 */
@Serializable
data class SackTrackerConfig(
    /** Master toggle: the panel, middle-click tracking and the tracked-slot markers. */
    val hud: Boolean = false,
    val tracked: List<TrackedSackItem> = emptyList(),
    /** Default: right edge, upper middle. */
    val hudTransform: HudTransform = HudTransform(hAnchor = HudAnchor.END, vAnchor = HudAnchor.CENTER, offsetX = -9.5f, offsetY = -36.5f),
    val hudBackground: Boolean = true,
    val hudBackgroundColor: ChromaColor = ChromaColor(0xD81A1A1A.toInt()),
    /** Session clock in the panel header; toggled from the panel's Move GUI menu. */
    val hudTimer: Boolean = true,
    // ---- panel UI state, written by the panel itself ----
    val sort: String = "session",
    val columns: Map<String, Boolean> = emptyMap(),
)

/**
 * One tracked sack item: SkyBlock [id], the name and name color it had in the sack menu, and its icon
 * look (see `InventoryButtonIcons.ItemLook`), captured when it was middle-clicked.
 */
@Serializable
data class TrackedSackItem(
    val id: String = "",
    val name: String = "",
    val color: Int = 0xFFFFFFFF.toInt(),
    val item: String = "minecraft:paper",
    val skin: String = "",
    val model: String = "",
    val glint: Boolean = false,
)

/**
 * SkyBlock stats drawn in the space the hidden crafting grid leaves in the player inventory
 * ([com.soulreturns.features.inventory.InventoryInfo]). [entries] are ids from
 * [com.soulreturns.features.inventory.InventoryInfoEntries], in display order; unknown ids are skipped.
 */
@Serializable
data class InventoryInfoConfig(
    val enabled: Boolean = false,
    val progressBars: Boolean = true,
    /** Soul's MSDF font; false = the vanilla font. */
    val soulFont: Boolean = true,
    /** 0 = tiles (profile as a header, up to four stat cards), 1 = the label / value list. */
    val layout: Int = 0,
    /** Tooltip with exact XP, progress and cap when hovering a stat. */
    val hoverDetails: Boolean = true,
    /** SkyBlock Level in Hypixel's bracket color. */
    val levelColor: Boolean = true,
    val entries: List<String> = listOf("skyblock_level", "profile", "skill_average", "catacombs", "class_average"),
)

/**
 * Command buttons drawn around container screens
 * ([com.soulreturns.features.inventorybuttons.InventoryButtons]); edited only through
 * [com.soulreturns.features.inventorybuttons.InventoryButtonsScreen].
 *
 * [buttons] is the Default profile, [profiles] the other named button sets. Which set shows where is
 * a separate mapping: [activeEverywhere] (a profile id, [DEFAULT_ID] for Default) unless
 * [activeByArea] names another for the current SkyBlock area (key = area name as reported, or
 * [RUN_KEY] for a dungeon run) — exactly one profile per slot, so nothing can overlap. A slot naming
 * a deleted profile counts as unset. An empty Default with no profiles is the off state.
 * [seenAreas] remembers every SkyBlock area the client has been in, so the area picker can offer it.
 */
@Serializable
data class InventoryButtonsConfig(
    val buttons: List<InventoryButton> = emptyList(),
    val profiles: List<ButtonProfile> = emptyList(),
    val activeEverywhere: String = DEFAULT_ID,
    val activeByArea: Map<String, String> = emptyMap(),
    val seenAreas: List<String> = emptyList(),
    /** Off hides the screen-edge spots (columns `L`/`R`, rows `T`/`B`) and their buttons; their spot ids stay stored. */
    val showOuterButtons: Boolean = true,
) {
    companion object {
        /** Id of the built-in Default profile ([buttons]); no stored profile uses it. */
        const val DEFAULT_ID = "default"

        /** [activeByArea] key of the dungeon-run slot; no Hypixel area name starts with `@`. */
        const val RUN_KEY = "@dungeon-run"
    }
}

/** A named set of buttons. [id] is stable across renames; where it is used lives in [InventoryButtonsConfig]. */
@Serializable
data class ButtonProfile(
    val id: String,
    val name: String,
    val buttons: List<InventoryButton> = emptyList(),
)

/**
 * One button. [spot] is the id of its place on the button grid
 * ([com.soulreturns.features.inventorybuttons.InventoryButtonSpots]: `G:n:m` around the inventory,
 * `L|R:c:m` / `T|B:r:n` at the screen edges); a spot that does not exist at the current GUI scale
 * hides the button. The icon is [item] plus the two components a SkyBlock icon needs: [skin] (a
 * player-head profile, encoded with `ResolvableProfile.CODEC`) and [model] (an `item_model` override).
 */
@Serializable
data class InventoryButton(
    val command: String = "",
    val label: String = "",
    val item: String = "minecraft:paper",
    val skin: String = "",
    val model: String = "",
    val glint: Boolean = false,
    val spot: String = "G:1:0",
    val inventoryOnly: Boolean = true,
)

/**
 * `/spv` — the SkyBlock Profile Viewer
 * ([com.soulreturns.features.profileviewer.ProfileViewerService]).
 *
 * [enabled] defaults **on** as an exception to the opt-in policy, deliberately: it is a command the
 * player has to type, so nothing appears, changes or is sent until they ask for it. Off is for people
 * who want the command out of their completion list.
 *
 * [shareSoulStats] is the opposite kind of switch and defaults **off** — see its own doc.
 */
@Serializable
data class ProfileViewerConfig(
    /** Whether `/spv` and `/soul spv` do anything. */
    val enabled: Boolean = true,
    /**
     * Let other Soul users see statistics **Soul collected about your gameplay** (mineshaft visits
     * and corpses first; other trackers later) when they open your profile — data Hypixel does not
     * publish and nobody could see without this.
     *
     * Off by default and forever opt-in: this is our data about the player, not Hypixel's, and
     * publishing it silently would be indefensible. You can always see your own regardless.
     *
     * Pushed to the backend as an additive field on the presence payload
     * ([com.soulreturns.platform.presence.PresenceState.shareSoulStats]) rather than by its own
     * request, so a flip costs nothing extra.
     */
    val shareSoulStats: Boolean = false,
)

/**
 * Messages the backend pushes to this player (`POST /admin/players/{uuid}/notify` → the realtime
 * channel → [com.soulreturns.features.notifications.BackendNotificationCenter]).
 *
 * The chat line defaults ON and the on-screen toast defaults OFF: a toast the player was looking
 * away from is a lost message, and per the opt-in policy a new on-screen overlay is not switched on
 * for them. Enable the toast in `/soul` → General → Notifications.
 */
@Serializable
data class NotificationsConfig(
    /** Show backend notifications as an on-screen toast ([com.soulreturns.features.notifications.NotificationHud]). */
    val toastEnabled: Boolean = false,
    /** Also print backend notifications to chat — the always-visible fallback. */
    val chatEnabled: Boolean = true,
    /** Seconds each toast stays on screen. */
    val toastSeconds: Float = 6f,
    /** Draw a rounded background card behind the toast. */
    val background: Boolean = true,
    /** Toast background fill (alpha = opacity; chroma supported). */
    val backgroundColor: ChromaColor = ChromaColor(0x90000000.toInt()),
    /** Move-GUI transform. Default: horizontally centered, near the top of the screen. */
    val hud: HudTransform = HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.START, offsetY = 65f),
    /** Played when a notification arrives (null = silent). */
    val sound: SoundRef? = null,
)

/**
 * The SkyBlock kill-combo overlay (SBCombo port — see [com.soulreturns.features.combo.ComboTracker]).
 * User-facing, so it defaults OFF. [hud] carries the Move-GUI position/anchor/scale.
 */
@Serializable
data class ComboConfig(
    /** Master toggle for the combo overlay. */
    val enabled: Boolean = false,
    /** Overlay layout: 0 = Full (multi-line), 1 = Compact (single line). */
    val mode: Int = 0,
    /**
     * Hide Hypixel's own `+N Kill Combo …` milestone lines from chat. Display-only: the message still
     * reaches other mods (see [com.soulreturns.util.ChatDisplayFilter]).
     */
    val hideComboMessages: Boolean = false,
    /** Hide Hypixel's `Your Kill Combo has expired!` line from chat (display-only). */
    val hideExpireMessage: Boolean = false,
    /**
     * Clear the overlay this many seconds after the last combo tick (Hypixel only sends milestone
     * lines every 5 kills, so without this the count lingers until the expiry message). 0 = off.
     */
    val decaySeconds: Float = 0f,
    /** Track your best combo and announce a new record in chat. */
    val trackBest: Boolean = false,
    /** Highest combo reached so far (persisted when [trackBest] is on). */
    val bestCombo: Int = 0,
    // Which elements of the combo overlay to draw (toggled together via the "Display" checkbox row).
    /** Show the combo count (`Current Combo: +N` / the `+N` in compact mode). */
    val showCount: Boolean = true,
    /** Show the Magic Find bonus line/segment (incl. the ✦ star). */
    val showMagicFind: Boolean = true,
    /** Show the Coins-per-Kill bonus line/segment. */
    val showCoins: Boolean = true,
    /** Show the Combat Exp bonus line/segment. */
    val showCombatExp: Boolean = true,
    /**
     * Show a red countdown (e.g. `5.8s`) of the estimated time until the combo expires. Resets on
     * each kill when a Blood God Crest/Sigil (best — counts all kills) or a Book of Stats weapon is
     * in your inventory (see [com.soulreturns.features.combo.ComboKillCounter]); otherwise it counts
     * down from the last chat milestone. Assumes a level-100 pet — only the timer changes with pet
     * level (not the bonuses); lower levels aren't modeled yet.
     */
    val showExpireTimer: Boolean = false,
    /** Draw a rounded background card behind the overlay. */
    val background: Boolean = true,
    /** Overlay background fill (alpha = opacity; chroma supported). */
    val backgroundColor: ChromaColor = ChromaColor(0x90000000.toInt()),
    /** Move-GUI transform (position / anchor / scale). Default: horizontally centered, upper third. */
    val hud: HudTransform = HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.CENTER, offsetY = -81f),
)

@Serializable
data class GeneralConfig(
    /** Prefix conversational `/pc` sends with `[Soul] ` so others can identify the mod. */
    val prefixOutgoingMessages: Boolean = false,
    /**
     * Soul UI scale: everything renders at this scale, ignoring Minecraft's GUI Scale. Null = never
     * chosen (a brand-new config): the first client start stores the pick for the monitor
     * ([com.soulreturns.ui.AutoUiScale]), so a stored value is never null after that and never changes by itself.
     */
    val uiScale: Float? = null,
    /**
     * Font for text OUTSIDE the config menu (HUDs, overlays): 0 = Baloo 2, 1 = Nunito, 2 = Inter,
     * 3 = Minecraft (the active texture pack's font). Indices match
     * [com.soulreturns.config.gui.render.MsdfFonts]; default is Baloo 2 (0).
     */
    val fontChoice: Int = 0,
    /**
     * Font for the config menu's own text: 0 = Baloo 2, 1 = Nunito, 2 = Inter. Vector fonts only —
     * the menu can't use Minecraft's texture-pack font (ASCII-only, no `↺`/`›`/… chrome glyphs), so
     * there is deliberately no Minecraft option here. Default is Baloo 2 (0).
     */
    val menuFontChoice: Int = 0,
    /**
     * Render Soul's HUD overlays in the bold (semibold) weight. Each movable HUD can override this
     * per-HUD in the Move GUI (Default / Normal / Bold), where Default follows this global setting.
     */
    val hudBoldFont: Boolean = false,
    /**
     * Draw a drop shadow behind Soul's HUD overlay text (default on — improves legibility on bright
     * backgrounds). Each movable HUD can override this per-HUD in the Move GUI (Default / On / Off),
     * where Default follows this global setting.
     */
    val hudTextShadow: Boolean = true,
    /**
     * HUDs with controls in the inventory (the trackers' tabs, filters, footer) show them only while
     * the cursor is over the panel; off = always shown while the inventory is open.
     */
    val trackerControlsOnHover: Boolean = true,
    /** Move GUI editor settings (its cog menu, Controls panel and bar pin). */
    val moveGui: MoveGuiConfig = MoveGuiConfig(),
    /**
     * "Soul GUI" (Move GUI) and "Soul" (config menu) buttons in the pause menu's bottom left corner
     * ([com.soulreturns.features.qol.PauseMenuButtons]). Default on: the user's chosen entry points.
     */
    val pauseMenuButtons: Boolean = true,
    /** Skip the front-facing view when cycling the camera perspective (F5). */
    val noFrontCamera: Boolean = false,
    /** Keep the sprint key held down so sprinting never drops (no double-tap / key holding). */
    val toggleSprint: Boolean = false,
    /** Also keep sprint held while underwater (fast swimming); only applies when [toggleSprint] is on. */
    val toggleSprintUnderwater: Boolean = false,
    /**
     * Keybind that flips [toggleSprint] on/off (an `InputConstants` key id like `key.keyboard.v`;
     * blank = unbound). Polled per-tick in `features/qol/ToggleSprint.kt`, world-only (ignored while
     * a screen is open). This is the same flag the config checkbox shows — one source of truth.
     */
    val toggleSprintKey: String = "",
    /** Show a small "Sprint toggled" indicator HUD while [toggleSprint] is active (else nothing). */
    val toggleSprintHud: Boolean = false,
    /** Move-GUI transform for the sprint-toggle indicator. Default: attached left of the hotbar, bottoms lined up. */
    val toggleSprintHudTransform: HudTransform =
        HudTransform(
            hAnchor = HudAnchor.CENTER,
            vAnchor = HudAnchor.END,
            offsetX = -138f,
            offsetY = -22f,
            link =
                HudLink(
                    HudTargets.vanilla(VanillaIds.HOTBAR),
                    h = HudPlace.OUT_START,
                    v = HudPlace.OUT_START,
                    offsetX = -4f,
                    follow = true,
                    auto = false,
                ),
        ),
    /** Draw a background card behind the sprint-toggle indicator (default off). */
    val toggleSprintHudBackground: Boolean = false,
    /** Sprint-toggle indicator background fill (alpha = opacity; chroma supported). */
    val toggleSprintHudBackgroundColor: ChromaColor = ChromaColor(0x90000000.toInt()),
    /** Sprint-toggle indicator text color (chroma supported). */
    val toggleSprintHudColor: ChromaColor = ChromaColor(0xFF55FF55.toInt()),
    /** Draw the action bar with Soul's HUD font ([com.soulreturns.features.qol.ActionBarHud]). */
    val soulActionBar: Boolean = false,
    /**
     * Move-GUI transform for the action bar. Until [actionBarCustomPosition] is set only its style
     * (scale, font) counts: the position is vanilla's own action bar spot at the current GUI scale
     * ([com.soulreturns.features.actionbar.ActionBarHud]); the screen-anchor fields here are the
     * stored base, centered on the bottom edge.
     */
    val actionBarTransform: HudTransform = HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.END),
    /** The player moved the action bar: [actionBarTransform] holds its position instead of vanilla's spot. */
    val actionBarCustomPosition: Boolean = false,
    /**
     * Move-GUI transform of the shared alert ([com.soulreturns.ui.hud.alert.AlertHud]) every feature's
     * on-screen warning uses. Default: centered, a bit above the crosshair, at 1.5x.
     */
    val alertTransform: HudTransform = HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.CENTER, offsetY = -65f, scale = 1.5f),
    /**
     * Stop SkyBlock ability items that are backed by a placeable block (Fire Freeze Staff = a
     * dandelion, Turbo Fishing Net = cobweb, Summoning Ring = a player head, …) from placing that
     * block when you right-click a block to use the ability. The ability still fires; only the local
     * block placement is suppressed. See `features/qol/PreventAbilityItemPlace.kt`.
     */
    val preventAbilityItemPlace: Boolean = false,
    /** Hide the floating item name shown above the hotbar when you select/switch an item. */
    val hideHeldItemTooltip: Boolean = false,
    /**
     * Master of "Hide potion effects" ([hideEffectsHud], [hideEffectsInventory]): a part acts only
     * while this is on; the master never changes a part. Read the effective state via [effectsHudHidden].
     */
    val hideEffects: Boolean = false,
    /** Hide the potion effect icons in the HUD (top-right corner). Part of [hideEffects]. */
    val hideEffectsHud: Boolean = true,
    /** Hide the potion effect list shown beside the inventory. Part of [hideEffects]. */
    val hideEffectsInventory: Boolean = true,
    /** Master of "Clean up inventory" ([hideRecipeBook], [hideOffhandSlot], [hideCraftingGrid]). */
    val cleanUpInventory: Boolean = false,
    /** Hide the recipe book and its button in the player inventory (crafting tables keep it). Part of [cleanUpInventory]. */
    val hideRecipeBook: Boolean = true,
    /** Hide the off-hand slot in the player inventory; SkyBlock has no use for it. Part of [cleanUpInventory]. */
    val hideOffhandSlot: Boolean = true,
    /** Hide the 2×2 crafting grid, its result slot and its label in the player inventory. Part of [cleanUpInventory]. */
    val hideCraftingGrid: Boolean = true,
    /** Necklace, cloak, belt and gloves in a slot column left of the inventory, on SkyBlock. */
    val equipmentDisplay: Boolean = false,
    /** Equipment slots flush against the armor column inside an extension of the inventory's own frame. */
    val equipmentJoinFrame: Boolean = false,
    /** Dark container screens in the style of the Skyblock-Dark-UI resource pack. */
    val darkUi: Boolean = false,
    /** Keep the cursor in place when one menu replaces another (storage pages, menu navigation). */
    val keepCursorBetweenMenus: Boolean = false,
    /**
     * Hide decorative foliage (grass, ferns, flowers, bushes — see `HideFoliage.shouldHide`):
     * invisible + silent (no break sound/particles), and mobs standing in it can be attacked.
     * Block interactions stay vanilla (never through foliage — anticheat-safe); crops unaffected;
     * auto-disabled on the private island and in the Garden.
     */
    val hideFoliage: Boolean = false,
    /** Skip the first-person fire overlay while burning (`ScreenEffectRendererMixin`); other overlays stay. */
    val hideFireOverlay: Boolean = false,
    /** Draw burning entities without flames (`EntityRenderDispatcherMixin`); burning itself is untouched. */
    val hideEntityFire: Boolean = false,
    /**
     * Hide stuck skinned Diana mobs (Hub only): re-modelling griffin pet skins (Four Seasons)
     * replace Mythological Ritual mobs with fake player entities, which linger as frozen statues
     * when the real mob dies outside your tracking range — see
     * `features/qol/HideStuckDianaMobs.kt`. Hidden statues are also untargetable so clicks pass
     * through to live mobs behind them.
     */
    val hideStuckDianaMobs: Boolean = false,
    /** Master of "Quiet Implosion" (the three parts below); a part acts only while this is on. */
    val quietImplosion: Boolean = false,
    /** Drop Implosion's explosion particle packets (any player's) — see `features/qol/ImplosionHider.kt`. Part of [quietImplosion]. */
    val hideImplosionParticles: Boolean = true,
    /** Implosion's `entity.generic.explode` part (only the caster hears the sound). Part of [quietImplosion]. */
    val hideImplosionExplosionSound: Boolean = true,
    /** Implosion's `entity.enderman.teleport` part. Part of [quietImplosion]. */
    val hideImplosionTeleportSound: Boolean = true,
) {
    // Effective states of the merged cards' parts: a part acts only while its master is on.
    val effectsHudHidden: Boolean get() = hideEffects && hideEffectsHud
    val effectsInventoryHidden: Boolean get() = hideEffects && hideEffectsInventory
    val recipeBookHidden: Boolean get() = cleanUpInventory && hideRecipeBook
    val offhandSlotHidden: Boolean get() = cleanUpInventory && hideOffhandSlot
    val craftingGridHidden: Boolean get() = cleanUpInventory && hideCraftingGrid
    val implosionParticlesHidden: Boolean get() = quietImplosion && hideImplosionParticles
    val implosionExplosionMuted: Boolean get() = quietImplosion && hideImplosionExplosionSound
    val implosionTeleportMuted: Boolean get() = quietImplosion && hideImplosionTeleportSound
}

/**
 * Fishing (`com.soulreturns.features.fishing`).
 *
 * [doubleHookMessageEnabled] and [doubleHookMessageText] predate the rest and **must keep their
 * names**: `ignoreUnknownKeys = true` means a renamed key is dropped without a warning, silently
 * resetting whatever the user had typed.
 *
 * Everything else is new and user-facing, so it defaults off. The sea-creature catalog the tracker
 * matches against has deliberately no toggle at all — it is data the feature cannot work without,
 * not an option (see [com.soulreturns.data.fishing.SeaCreatureCatalogClient]).
 */
@Serializable
data class FishingConfig(
    /** Master toggle for the "Woot Woot" double-hook party announcement. */
    val doubleHookMessageEnabled: Boolean = false,
    /** The message body sent to party chat on a double hook. */
    val doubleHookMessageText: String = "Woot Woot!",
    /** Show the fishing tracker panel while you are actually fishing. */
    val trackerHud: Boolean = false,
    /**
     * Move-GUI transform for the tracker panel.
     *
     * Default is the legacy one: top-left, just in from the corner.
     */
    val trackerHudTransform: HudTransform = HudTransform(offsetX = 9.5f, offsetY = 11f),
    /** Draw the panel's background card. */
    val trackerHudBackground: Boolean = true,
    /** Panel background fill (alpha = opacity; chroma supported). */
    val trackerHudBackgroundColor: ChromaColor = ChromaColor(0xD81A1A1A.toInt()),
    /**
     * Keep the creature list at a fixed height with a scroll wheel, instead of letting the panel grow
     * with every creature you have ever caught.
     */
    val trackerScrollable: Boolean = true,
    /** Show the fishing-festival countdown in the panel header while one is running. */
    val trackerFestivalTimer: Boolean = true,
    /**
     * Add a header line with catches and XP per hour of **active** fishing.
     *
     * Off by default: the legacy panel did not have it, and this keeps the default layout identical
     * to the one people already know. The numbers themselves are always tracked.
     */
    val trackerShowRates: Boolean = false,
    // ---- panel UI state. Written by the panel itself as you use it, not by a config row. ----
    /** Active tab, by [com.soulreturns.ui.hud.tracker.TrackerTab] name. */
    val trackerTab: String = "Session",
    /** Active sort id — see `FishingHud.SORT_*`. */
    val trackerSort: String = "catches",
    /** Selected variant filter, as display names. Empty = show everything. */
    val trackerFilter: Set<String> = emptySet(),
    /** Column id → shown. Absent means "whatever the column declares as its default". */
    val trackerColumns: Map<String, Boolean> = emptyMap(),
    /** Say in chat when enough bobbers are in the water nearby ("Bobbin Time"). Client-side only. */
    val bobbinAlert: Boolean = false,
    /** How many bobbers within 30 blocks count as a crowd. */
    val bobbinThreshold: Int = 3,
    /** Played with the Bobbin Time alert (null = silent). */
    val bobbinAlertSound: SoundRef? = null,
    /** Played on every double hook, party or not (null = silent). */
    val doubleHookSound: SoundRef? = null,
)

@Serializable
data class DungeonsConfig(
    /** Master toggle for Soul's dungeon map HUD. */
    val mapEnabled: Boolean = false,
    /** Hide the map once you leave the room grid, i.e. in the boss arena. */
    val mapHideInBoss: Boolean = false,
    /**
     * Font for the dungeon map's text: 0 = Global Default (use `general.fontChoice`), else
     * 1 = Baloo 2, 2 = Nunito, 3 = Inter, 4 = Minecraft. Lets the map use a different font than
     * the rest of the UI.
     */
    val mapFontChoice: Int = 0,
    /** Map HUD position / anchor / scale — edited in the Move GUI (was mapX/mapY/mapScale). Default: just below-right of the center. */
    val mapHud: HudTransform = HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.CENTER, offsetX = 74f, offsetY = 74f),
    /** Draw the map's filled background card. */
    val mapBackground: Boolean = true,
    /** Draw a border frame (outline) around the map. */
    val mapBorder: Boolean = false,
    /** Rounded corners on the background + border (off = square). */
    val mapRoundedCorners: Boolean = true,
    /** Map background fill color (alpha = opacity; chroma supported). */
    val mapBackgroundColor: ChromaColor = ChromaColor(0xC8101014.toInt()),
    /** Map border frame color (alpha = opacity; chroma supported). */
    val mapBorderColor: ChromaColor = ChromaColor(0xFF3A3A44.toInt()),
    /** Per-type room/door/class colors drawn on the map (all customizable). */
    val colors: DungeonMapColors = DungeonMapColors(),
    /** Draw per-room `found/total` secret counts on the map. */
    val mapShowSecrets: Boolean = true,
    /** Draw identified room names on the map, colored by checkmark state. */
    val mapShowRoomNames: Boolean = true,
    /** Vertical placement of the secret count within a room: 0 = top, 1 = center, 2 = bottom. */
    val mapSecretsPosition: Int = 0,
    /** Vertical placement of the room name within a room: 0 = top, 1 = center, 2 = bottom. */
    val mapNamePosition: Int = 1,
    /** Center the name + secret count together as one stacked block (ignores the two positions). */
    val mapCenterPair: Boolean = false,
    /** When centering the pair: put the secret count above the name (else name above count). */
    val mapSecretsAboveName: Boolean = false,
    /** Center the room name (ignoring [mapNamePosition]) in rooms that show no secret count. */
    val mapCenterNamelessRooms: Boolean = true,
    /** Shrink long room names so they fit inside the room (width + height) instead of overflowing. */
    val mapAutoScaleNames: Boolean = true,
    /** Manual multiplier for all map text (names + secret counts). Ignored while [mapAutoScaleNames] is on. */
    val mapTextScale: Float = 1f,
    /** Draw player names under the map markers. */
    val mapShowNames: Boolean = false,
    /** Override [mapShowNames]: show teammate names only while holding a leap item (Spirit/Infinileap). */
    val mapNamesOnlyWithLeap: Boolean = false,
    /** Render teammates as their player head (vs a plain arrow). */
    val mapPlayerHeadsOthers: Boolean = true,
    /** Render yourself as your player head (vs a plain arrow). */
    val mapPlayerHeadSelf: Boolean = false,
    /** Ring teammate heads with their dungeon-class color (never applied to self). */
    val mapClassColorBorders: Boolean = true,
    /** Size multiplier for teammate head markers. */
    val mapPlayerHeadScale: Float = 1f,
    /** Size multiplier for your own marker — applies whether it's an arrow or a player head. */
    val mapPlayerScaleSelf: Float = 1f,
    /** Render teammate markers above the room text (names + counts), regardless of arrow/head. */
    val mapOthersAboveText: Boolean = false,
    /** Render your own marker above the room text (names + counts), regardless of arrow/head. */
    val mapSelfAboveText: Boolean = false,
    /** Compact debug overlay for the dungeon map + sync (state, channels, rooms). */
    val debugHudEnabled: Boolean = false,
    /** Debug overlay position + scale — independent of the map so it can sit out of the way. */
    val debugHudX: Float = 250f,
    val debugHudY: Float = 10f,
    val debugHudScale: Float = 0.75f,
    /**
     * Sync room identities + secret counts with party members. Enables BOTH channels: Soul's own
     * backend (sky.soulreturns.com, Mercure SSE) for teammates on Soul, and the devonian fallback
     * (ws://wss.docilelm.top) automatically for teammates on devonian. One opt-in, no per-channel
     * toggle — each teammate is reached over whichever transport their mod speaks.
     */
    val secretsSyncEnabled: Boolean = false,
    /**
     * Command keybinds and inventory buttons do not send commands that would leave the dungeon
     * run (`DungeonCommandGuard`). Typed chat is never blocked.
     */
    val blockLeavingCommands: Boolean = false,
    /** With [blockLeavingCommands]: still allow `/warp dh` and `/warp dungeon_hub`. */
    val allowDungeonHubWarp: Boolean = true,
    /** Stop drawing Soulweaver Gloves' Haunted Skulls, everyone's (`HideSoulweaverSkulls`, `EntityRenderDispatcherMixin`). Visual only. */
    val hideSoulweaverSkulls: Boolean = false,
    /** F7/M7 terminal solver (`features/dungeons/terminals/`). */
    val terminals: TerminalSolverConfig = TerminalSolverConfig(),
    /** F7/M7 device solvers (`features/dungeons/devices/`): Simon Says, Lights, Arrow Align. */
    val devices: DeviceSolverConfig = DeviceSolverConfig(),
    /** F7/M7 boss helpers: Storm crush window, boss tick timers, Storm lure guide (`features/dungeons/boss/`). */
    val boss: BossHelpersConfig = BossHelpersConfig(),
    /** Soul's Spirit Leap menu (`features/dungeons/leap/`, spec mocks/leap-menu/SPEC.md). */
    val leap: LeapMenuConfig = LeapMenuConfig(),
    val roles: ArcherRoleConfig = ArcherRoleConfig(),
)

@Serializable
data class ArcherRoleConfig(
    val enabled: Boolean = false,
    val showCue: Boolean = true,
    val markObjective: Boolean = true,
    val highlightLeap: Boolean = true,
)

/** Spirit Leap menu: Odin-sorted corners, where each teammate is. One press sends at most one click on the chosen head. */
@Serializable
data class LeapMenuConfig(
    val enabled: Boolean = false,
    /** `LeapSort` mode: odin (class home corners), class, name, menu (Hypixel's order). */
    val sorting: String = "odin",
    /** Keys 1-4 leap to the top left / top right / bottom left / bottom right teammate. */
    val numberKeys: Boolean = true,
    /** A click on a dead teammate sends nothing. */
    val blockDead: Boolean = true,
    /** The small 1-4 box in each tile's corner. */
    val keyHints: Boolean = true,
    /** The location line: seen now, their party call-out with its age, or a yellow `?`. */
    val showLocations: Boolean = true,
)

/** F7/M7 boss helpers; display only. The crosshair timers' settings are mocks/boss-timers/SPEC.md §8. */
@Serializable
data class BossHelpersConfig(
    /** The boss timers' fixed places at the crosshair (pad tick, phase, action line, crush window), the pillar mark and the logs. */
    val crushWindow: Boolean = false,
    /** 0 = seconds, 1 = ticks. */
    val units: Int = 0,
    /** Digit size (Soul px nominal; the pad digits draw at 0.92 of it, the other places scale from it). */
    val digitSize: Float = 36f,
    /** The action line's top edge below the crosshair center (Soul px; never inside the ring). */
    val offset: Float = 22f,
    /** The pad tick: ring and digits of the 20-tick pad check loop. */
    val showPad: Boolean = true,
    val showGoldor: Boolean = true,
    val showAction: Boolean = true,
    val showPurple: Boolean = true,
    /** Experimental "arm and bait" cue during Storm's high phases (STEP ON for the pad check, then "bounce back" and the bait spot). */
    val showArm: Boolean = true,
    val showWindow: Boolean = true,
    val showGiga: Boolean = true,
    val showHigh: Boolean = true,
    /** The countdown from Storm's death to Goldor's phase (terminals, levers and devices take clicks), in the phase place. */
    val showTerminals: Boolean = true,
    /** 0 = near a pad, 1 = only on a pad, 2 = the whole Storm phase. */
    val padWhen: Int = 0,
    /** STAY counts down to the pad check. */
    val stayTime: Boolean = true,
    /** The Goldor tick's ring around the crosshair; off leaves its digits. */
    val goldorRing: Boolean = false,
    /** A late result shows by how much (`+0.35`), else the word. */
    val lateAmount: Boolean = true,
    /** Outline the pillar's footprint while its window is armed. */
    val markPillar: Boolean = true,
    /** Storm's distance and ETA under the window. */
    val stormEta: Boolean = true,
    /** The next fireball in the High line. */
    val nextFireball: Boolean = false,
    /** Also in Master Mode 7 (its timings are Floor 7's until the fight confirms them). */
    val onM7: Boolean = true,
    /** Goldor's phase: a persistent warning while you stand in a section that has not begun yet. */
    val sectionWarning: Boolean = false,
    /** Played once per open section when the warning first comes up; null = silent. */
    val sectionWarningSound: SoundRef? = SoundRef(id = "minecraft:block.note_block.bass", pitch = 0.8f),
    /** M7 P5: each dragon's spawn countdown at its spawn and the next one in the phase place. */
    val dragonCountdown: Boolean = false,
)

/** The F7/M7 Goldor-phase device solvers; each device has its own switch under the card's master. */
@Serializable
data class DeviceSolverConfig(
    val enabled: Boolean = false,
    /** Simon Says: the next button (and the one after it) on the device's button grid. */
    val simonSays: Boolean = true,
    /** Lights: the levers to flip next, as boxes on the grid. */
    val lights: Boolean = true,
    /** Arrow Align: the clicks each arrow still needs, as numbers on the frames. */
    val arrowAlign: Boolean = true,
    /** Sharpshooter: the target block and the ones already hit, as boxes on the grid. Highlight only. */
    val sharpshooter: Boolean = true,
    /** A click on a wrong Simon Says button, a 4th rapid start press, a Lights lever that is already flicked, or an Arrow Align arrow that already points at its target, sends nothing. */
    val blockWrongClicks: Boolean = true,
    /** Arrow Align before Goldor's phase: the click that would finish the device is not sent (a prepared device waits for Goldor). */
    val arrowHoldLast: Boolean = false,
    /** While this holds ("never", "standing" or "sneaking") Simon Says blocks nothing (highlights stay). */
    val simonSaysOverride: String = "never",
    /** The same for Lights. */
    val lightsOverride: String = "never",
    /** The same for Arrow Align (wrong clicks and the held last click). */
    val arrowAlignOverride: String = "never",
    /** Also mark the button after the next one, dimmer. */
    val showAfterNext: Boolean = true,
)

/** The F7/M7 terminal solver: Soul's own terminal GUI (or highlights over the vanilla one). */
@Serializable
data class TerminalSolverConfig(
    val enabled: Boolean = false,
    /** A click on a wrong or already done cell sends nothing. */
    val blockWrongClicks: Boolean = true,
    /** Left clicks go out as middle clicks (CLONE, button 2): no ping-long pick-up onto the cursor. */
    val leftAsMiddle: Boolean = true,
    /** Soul's own terminal GUI; off keeps the vanilla menu and draws highlights over its slots. */
    val customGui: Boolean = true,
    /** Size multiplier of the custom GUI's cells. */
    val guiSize: Float = 1f,
    /** The small "left" / "right" under each count in the color (rubix) terminal of Soul's window. */
    val rubixLabels: Boolean = true,
    val startsWith: Boolean = true,
    val selectColor: Boolean = true,
    val panes: Boolean = true,
    val order: Boolean = true,
    val sameColor: Boolean = true,
    val melody: Boolean = true,
)

/**
 * Per-type colors for the dungeon map (room fills, door fills, class-colored markers). Defaults are
 * the built-in palette; each is a solid ARGB int and independently customizable via the color picker.
 */
@Serializable
data class DungeonMapColors(
    // Room fills
    val unopened: ChromaColor = ChromaColor(0xFF3E3E46.toInt()),
    val normal: ChromaColor = ChromaColor(0xFF8A6142.toInt()),
    val rare: ChromaColor = ChromaColor(0xFFFFCB59.toInt()),
    val entrance: ChromaColor = ChromaColor(0xFF17B01E.toInt()),
    val puzzle: ChromaColor = ChromaColor(0xFF8A4DB0.toInt()),
    val trap: ChromaColor = ChromaColor(0xFFD8823B.toInt()),
    val miniboss: ChromaColor = ChromaColor(0xFFD8D845.toInt()),
    val fairy: ChromaColor = ChromaColor(0xFFEF8FB8.toInt()),
    val blood: ChromaColor = ChromaColor(0xFFA31212.toInt()),
    // Door fills (normal doors reuse [normal]; wither shows [doorWither] until opened)
    val doorWither: ChromaColor = ChromaColor(0xFF17171B.toInt()),
    val doorBlood: ChromaColor = ChromaColor(0xFFA31212.toInt()),
    val doorEntrance: ChromaColor = ChromaColor(0xFF17B01E.toInt()),
    // Player markers
    val markerSelf: ChromaColor = ChromaColor(0xFF22D3EE.toInt()),
    val classArcher: ChromaColor = ChromaColor(com.soulreturns.features.dungeons.DungeonClass.ARCHER.color),
    val classBerserk: ChromaColor = ChromaColor(com.soulreturns.features.dungeons.DungeonClass.BERSERK.color),
    val classMage: ChromaColor = ChromaColor(com.soulreturns.features.dungeons.DungeonClass.MAGE.color),
    val classHealer: ChromaColor = ChromaColor(com.soulreturns.features.dungeons.DungeonClass.HEALER.color),
    val classTank: ChromaColor = ChromaColor(com.soulreturns.features.dungeons.DungeonClass.TANK.color),
)

/** Mining features: Glacite Mineshafts, the Mines of Divan, Crystal Nucleus runs, commissions and particle/weather hiders. */
@Serializable
data class MiningConfig(
    val mineshaft: MineshaftConfig = MineshaftConfig(),
    val metalDetector: MetalDetectorConfig = MetalDetectorConfig(),
    val nucleus: NucleusConfig = NucleusConfig(),
    val commissions: CommissionsConfig = CommissionsConfig(),
    /** Mute the Crystal Hollows weather's sounds (`HollowsWeather`); visuals stay. */
    val muteHollowsWeather: Boolean = false,
    /** Drop the Crystal Hollows weather's particle packets (`HollowsWeather`); sounds stay. */
    val hideHollowsWeatherVisuals: Boolean = false,
    /** Drop Divan's Powder Coating dust around players (`PowderCoatingParticles`). */
    val hidePowderCoatingParticles: Boolean = false,
)

/** Dwarven Mines / Crystal Hollows commissions (`com.soulreturns.features.mining.commissions`). */
@Serializable
data class CommissionsConfig(
    /** Mark COMPLETED commissions in the `Commissions` menu. */
    val highlightCompleted: Boolean = false,
    /** Commission list with progress, from the tab list (menu as fallback). */
    val hud: Boolean = false,
    /** Default: left edge, vertically centered. */
    val hudTransform: HudTransform = HudTransform(hAnchor = HudAnchor.START, vAnchor = HudAnchor.CENTER, offsetX = 9.5f),
    val hudBackground: Boolean = true,
    val hudBackgroundColor: ChromaColor = ChromaColor(0x90000000.toInt()),
)

/** Crystal Nucleus runs in the Crystal Hollows (`com.soulreturns.features.mining.nucleus`). */
@Serializable
data class NucleusConfig(
    /** Run tracker panel while in the Crystal Hollows. */
    val tracker: Boolean = false,
    /** Chat line for a bundle item worth at least [rareDropThresholdM] million coins. */
    val rareDropAlert: Boolean = true,
    /** Rare-drop threshold in millions of coins (alert + gold rows on the panel). */
    val rareDropThresholdM: Double = 1.0,
    /** Default: top-left, like the Metal Detector panel (both only show together while detecting in Divan). */
    val hudTransform: HudTransform = HudTransform(offsetX = 9.5f, offsetY = 11f),
    val hudBackground: Boolean = true,
    val hudBackgroundColor: ChromaColor = ChromaColor(0xD81A1A1A.toInt()),
    val hudScrollable: Boolean = true,
    val hudTab: String = "Session",
    val hudSort: String = "value",
    /** Row groups shown (Loot / Powder / Kit); empty = all. */
    val hudFilter: Set<String> = emptySet(),
    val hudColumns: Map<String, Boolean> = emptyMap(),
    /** Panel price mode, `PriceMode` index (footer selector). */
    val priceMode: Int = 0,
    /** Crystal checklist + splits HUD while in the Crystal Hollows. */
    val checklist: Boolean = false,
    /** Default: right edge, below the sidebar. */
    val checklistTransform: HudTransform = HudTransform(hAnchor = HudAnchor.END, vAnchor = HudAnchor.END, offsetX = -9.5f, offsetY = -89.5f),
    val checklistBackground: Boolean = true,
    val checklistBackgroundColor: ChromaColor = ChromaColor(0xA0101010.toInt()),
    /** Bal status line (up / down + time since / spawning) while in the Crystal Hollows. */
    val bal: Boolean = false,
    /** Default: right edge, under the checklist's default spot. */
    val balTransform: HudTransform = HudTransform(hAnchor = HudAnchor.END, vAnchor = HudAnchor.END, offsetX = -9.5f, offsetY = -41f),
    val balBackground: Boolean = true,
    /** Big warning for a few seconds when entering Khazad-dûm while Bal is up. Part of [bal]. */
    val balWarning: Boolean = true,
    /** Master of "Compact nucleus chat" (the four parts below); a part acts only while this is on. */
    val compactChat: Boolean = false,
    /** One hoverable line instead of the 8-line `CRYSTAL FOUND` block. Part of [compactChat]. */
    val compactFound: Boolean = true,
    /** One hoverable line instead of the ~22-line loot bundle (and its Vault footer). Part of [compactChat]. */
    val compactLoot: Boolean = true,
    /** Hide `You have already obtained this Crystal!` (~21 per run). Part of [compactChat]. */
    val hideAlreadyObtained: Boolean = false,
    /** Hide `There are blocks in the way!` in the Crystal Hollows (failed etherwarps). Part of [compactChat]. */
    val hideBlocksInWay: Boolean = false,
) {
    // Effective states: a part acts only while [compactChat] is on.
    val compactFoundOn: Boolean get() = compactChat && compactFound
    val compactLootOn: Boolean get() = compactChat && compactLoot
    val hideAlreadyObtainedOn: Boolean get() = compactChat && hideAlreadyObtained
    val hideBlocksInWayOn: Boolean get() = compactChat && hideBlocksInWay
}

/** Mines of Divan Metal Detector solver + loot HUD (`com.soulreturns.features.mining.metaldetector`). */
@Serializable
data class MetalDetectorConfig(
    /** Locate the treasure chest from the detector's pings and draw a waypoint to it. */
    val enabled: Boolean = false,
    /**
     * Shared on-screen alert while all four nucleus tools are in the inventory (further tool finds
     * are wasted). Part of the loot HUD, so on by default; it only ever shows while [hud] is on.
     */
    val toolsAlert: Boolean = true,
    /** Played once when the all-four-tools warning starts (null = silent). Default: note block pling at pitch 1.1. */
    val toolsAlertSound: SoundRef? = SoundRef(id = "minecraft:block.note_block.pling", pitch = 1.1f),
    /** Loot tracker panel while using the detector in the Mines of Divan. */
    val hud: Boolean = false,
    /** `Scavenged Tools` / `N/4` readout while in the Mines of Divan. */
    val toolsCounter: Boolean = false,
    /**
     * Default: left of the loot panel with the tops aligned (5 px link gap) while the panel shows;
     * otherwise the top-right corner, 5 px in (the link's `whenHidden` is the screen anchor).
     */
    val toolsCounterTransform: HudTransform =
        HudTransform(
            hAnchor = HudAnchor.END,
            vAnchor = HudAnchor.START,
            offsetX = -5f,
            offsetY = 5f,
            link = HudLink(HudTargets.hud("metal_detector_tracker"), h = HudPlace.OUT_START, v = HudPlace.START, offsetX = -5f, follow = true, auto = false),
        ),
    /** Waypoint box, line and label color (alpha + chroma). */
    val waypointColor: ChromaColor = ChromaColor(0xFF55FFFF.toInt()),
    /** Width of the line from the crosshair to the chest. */
    val lineWidth: Float = 3f,
    /** Default: top-right corner, 5 px in. */
    val hudTransform: HudTransform = HudTransform(hAnchor = HudAnchor.END, vAnchor = HudAnchor.START, offsetX = -5f, offsetY = 5f),
    val hudBackground: Boolean = true,
    val hudBackgroundColor: ChromaColor = ChromaColor(0xD81A1A1A.toInt()),
    val hudScrollable: Boolean = true,
    val hudTab: String = "Session",
    val hudSort: String = "found",
    /**
     * Loot groups shown (Tools / Powder / Other). `"none"` = header only (the default), empty = all
     * (what "Show All" stores), otherwise the chosen groups. See `MetalDetectorHudSettings.filter`.
     */
    val hudFilter: Set<String> = setOf("none"),
    val hudColumns: Map<String, Boolean> = emptyMap(),
)

/**
 * Glacite Mineshafts (`com.soulreturns.features.mining.mineshaft`).
 *
 * Everything user-facing defaults **off** per the opt-in policy — three of these send messages to
 * party chat, which is about as user-facing as a toggle gets. [logVisits] is the exception, as
 * tracking infrastructure rather than a feature, and its config row spells out exactly what leaves
 * the client.
 */
@Serializable
data class MineshaftConfig(
    /**
     * Record each mineshaft visit to the Soul backend: the type and instance (`ONYX_1`), when you
     * entered and left, the corpse counts, whether Littlefoot was there, and whether you found the
     * mineshaft yourself or were warped in (and by whom). Nothing else — no coordinates, no
     * inventory, no chat.
     *
     * Defaults **on**: it is what makes `/soul mineshaft` and the Profile Viewer's Soul tab able to
     * say anything at all, and none of it is information another player could not watch you do.
     */
    val logVisits: Boolean = true,
    /** Show the corpse overlay while inside a mineshaft. */
    val corpsesHud: Boolean = false,
    /** Draw a rounded card behind the corpse overlay. */
    val corpsesHudBackground: Boolean = true,
    /** Corpse overlay background fill (alpha = opacity; chroma supported). */
    val corpsesHudBackgroundColor: ChromaColor = ChromaColor(0x90000000.toInt()),
    /** Move-GUI transform for the corpse overlay. Default: horizontally centered, near the top. */
    val corpsesHudTransform: HudTransform = HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.START, offsetY = 21.5f),
    /** Per-corpse-type colors for the overlay. */
    val corpsesHudColors: MineshaftCorpseColors = MineshaftCorpseColors(),
    /** `/pc !ptme Found Littlefoot` on the first line-of-sight sighting each visit. */
    val littlefootPtme: Boolean = false,
    /** Also `/pc x: N, y: N, z: N` two seconds after the party warp, so waypoint mods pick it up. */
    val littlefootWaypoint: Boolean = false,
    /** `/pc !ptme Found N Lapis Corpses in Mineshaft` when a fresh mineshaft has at least [lapisThreshold]. */
    val lapisPtme: Boolean = false,
    /**
     * How many Lapis corpses are worth telling the party about.
     *
     * Capped at **4** in the config UI: a Glacite Mineshaft holds at most four Frozen Corpses in
     * total, across every type, so a threshold above that is one the alert could never reach — a
     * switch that looks enabled and silently never fires.
     */
    val lapisThreshold: Int = 3,
    /** `/pc !ptme Found Vanguard Corpse in Mineshaft` — there is at most one per mineshaft, so no threshold. */
    val vanguardPtme: Boolean = false,
    /** Played for you when a Littlefoot, Lapis or Vanguard alert fires, even outside a party; never on a warped visit (null = silent). */
    val alertSound: SoundRef? = null,
)

/**
 * Corpse overlay colors, ported from the legacy mod's `§`-code palette.
 *
 * [other] is not a filler: corpse types are **open-ended** — Hypixel adds them without warning and
 * nothing in this mod or the backend validates the name — so a type this build has never heard of
 * still has to be drawn. It gets [other] rather than being dropped.
 */
@Serializable
data class MineshaftCorpseColors(
    val lapis: ChromaColor = ChromaColor(0xFF5555FF.toInt()),
    val umber: ChromaColor = ChromaColor(0xFFFFAA00.toInt()),
    val tungsten: ChromaColor = ChromaColor(0xFFFFFFFF.toInt()),
    val vanguard: ChromaColor = ChromaColor(0xFFAA00AA.toInt()),
    /** Any corpse type this build does not know a color for. */
    val other: ChromaColor = ChromaColor(0xFFDDDDDD.toInt()),
    /** The looted count. */
    val looted: ChromaColor = ChromaColor(0xFF55FF55.toInt()),
    /** The total count. */
    val total: ChromaColor = ChromaColor(0xFFFF5555.toInt()),
    /** The overlay's "Corpses" heading. */
    val title: ChromaColor = ChromaColor(0xFF55FFFF.toInt()),
    /** The "None" placeholder shown in a mineshaft with no corpses. */
    val none: ChromaColor = ChromaColor(0xFFAAAAAA.toInt()),
)

/**
 * Developer settings, shown only while [com.soulreturns.platform.devaccess.DevAccess] is unlocked.
 * That gates *visibility*, not behaviour: a stored value keeps its meaning while locked. Gating the
 * effects would silently switch off things other features depend on — [PresenceConfig] in
 * particular is infrastructure the backend features need, not a toy.
 */
@Serializable
data class DevConfig(
    /** When on, `info`/`debug` logs reach the console; otherwise only warn/error do. */
    val debugMode: Boolean = false,
    /**
     * Verbose trace of every secret-sync / websocket action (auth, connect, subscribe, publish,
     * inbound frames, apply decisions) at INFO — independent of [debugMode]. For diagnosing sync.
     */
    val logWebsocket: Boolean = false,
    /**
     * Override the backend base URL (e.g. `http://localhost:8080` for local backend dev). Blank =
     * use the default. The `-Dsoul.backendUrl` system property and `SOUL_BACKEND_URL` env var take
     * precedence over this field — see [com.soulreturns.platform.http.SoulHttp.backendBaseUrl].
     */
    val backendUrl: String = "",
    /**
     * Hold the realtime (Mercure SSE) connection open even when not on a `hypixel.net` server.
     * Normally the connection is gated on Hypixel — there is no point streaming events on the title
     * screen. Turn this on to exercise the realtime path against a local backend from singleplayer
     * or a test server. A backend bearer is still required either way.
     */
    val realtimeAlwaysOn: Boolean = false,
    /** What Soul tells the backend about where you are — see [PresenceConfig]. */
    val presence: PresenceConfig = PresenceConfig(),
    /** Global dev keybinds (clipboard data dumps) — see [DevKeybindsConfig]. */
    val keybinds: DevKeybindsConfig = DevKeybindsConfig(),
)

/**
 * [com.soulreturns.platform.presence.PresenceService] — the `POST /presence/state` pushes that make
 * the backend's "who is online, and where" surface work.
 *
 * Tracking, so it defaults **on** per the default-behaviour policy. The switch lives under **Dev**
 * (shown only with developer access) rather than in a user-facing Privacy section: the backend
 * features being built on top of presence treat it as mandatory infrastructure, so an ordinary user
 * turning it off would silently break them. Its text still names every field that is sent.
 */
@Serializable
data class PresenceConfig(
    /**
     * Send the server address, whether you are on SkyBlock, your area, sublocation and profile name
     * to the Soul backend. Off means no `/presence/state` request ever leaves the client — checked at
     * push time, so turning it off takes effect immediately.
     */
    val enabled: Boolean = true,
)

/**
 * Keys for the dev clipboard dumps ([com.soulreturns.features.dev.DevKeybindHandler]). Each value
 * is an `InputConstants` key translation id (`key.keyboard.f6`, `key.mouse.4`, …); blank = unbound.
 * Polled per tick, bypassing Minecraft's controls menu, so they work inside container GUIs too.
 */
@Serializable
data class DevKeybindsConfig(
    /** Dump the open container screen (title, menu class, non-player slots) as JSON. */
    val copyOpenedGui: String = "",
    /** Dump the item under the cursor inside a container as JSON. */
    val copyItemUnderCursor: String = "",
    /** Dump the item in the main hand as JSON. */
    val copyHeldItem: String = "",
    /** Dump the scoreboard sidebar lines (with reconstructed text) as JSON. */
    val copyScoreboard: String = "",
    /** Dump the tab list players as JSON. */
    val copyTablist: String = "",
    /** Dump all entities within 30 blocks (type, name, position, passengers) as JSON. */
    val copyNearbyEntities: String = "",
)

/**
 * Combat features: [dragons] and the [mobHealth] HUD; slayer and boss trackers are the obvious next
 * things to land beside them.
 */
@Serializable
data class CombatConfig(
    val dragons: DragonsConfig = DragonsConfig(),
    val mobHealth: MobHealthConfig = MobHealthConfig(),
)

/** Move GUI editor settings, edited in its cog menu (and General → Interface → Move GUI). */
@Serializable
data class MoveGuiConfig(
    /** Snap line this far inside each screen edge, Soul px. */
    val edgeSnap: Float = 5f,
    /** Space a new link leaves to its target, Soul px. */
    val linkGap: Float = 5f,
    /** Ask before the link icon on a HUD detaches it. */
    val confirmDetach: Boolean = true,
    /** Selecting a HUD opens its settings panel (else right-click or "…" opens it). */
    val openOnSelect: Boolean = false,
    /** The top bar stays (else it slides away when the cursor leaves it). */
    val barPinned: Boolean = true,
    /** The Controls reference is expanded. */
    val controlsOpen: Boolean = true,
    /** The Controls reference shows its Advanced rows. */
    val controlsAdvanced: Boolean = false,
)

/** The mob health HUD ([com.soulreturns.features.mobhealth.MobHealthHud]). */
@Serializable
data class MobHealthConfig(
    /** Show the level, name and health of the mob you look at or last hit. */
    val enabled: Boolean = false,
    /** Draw a rounded card behind the HUD. */
    val background: Boolean = true,
    /** Card fill (alpha = opacity; chroma supported). */
    val backgroundColor: ChromaColor = ChromaColor(0x90000000.toInt()),
    /** Move-GUI transform. Default: centered, just below the crosshair. */
    val hud: HudTransform = HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.CENTER, offsetY = 50f),
)

/**
 * The End-island dragon profit tracker
 * ([com.soulreturns.features.profit.dragon.DragonProfitTracker]).
 *
 * Every user-facing switch here defaults **off**, per the opt-in policy. [enabled] is the master: with
 * it off nothing listens for a dragon, nothing scans, nothing is sent and the panel does not draw.
 *
 * The four `profit{Tab,Sort,Filter,Columns}` fields plus [profitSourceFilter] are **panel UI state the
 * panel writes itself** as you use it — they have no rows in `ConfigRegistry`, exactly like fishing's.
 * The legacy mod gave them a `dragon_profit_hud.json` with its own dirty flag, debounce and atomic
 * write: a fourth copy of machinery that already existed three times over.
 */
@Serializable
data class DragonsConfig(
    /** Master toggle for the whole feature — tracking, panel, announcements. */
    val enabled: Boolean = false,
    /** Show the profit panel at Dragon's Nest. */
    val profitHud: Boolean = false,
    /**
     * Move-GUI transform. Default is the legacy position: pinned to the top-right, because the End
     * has the boss bar across the top and chat in the bottom-left, and the right edge is the most
     * reliably empty real estate there.
     */
    val profitHudTransform: HudTransform = HudTransform(hAnchor = HudAnchor.END, offsetX = -9.5f, offsetY = 11f),
    /** Draw the panel's background card. */
    val profitHudBackground: Boolean = true,
    /** Panel background fill (alpha = opacity; chroma supported). */
    val profitHudBackgroundColor: ChromaColor = ChromaColor(0xD81A1A1A.toInt()),
    /** Keep the drop list at a fixed height with a scroll wheel instead of growing the panel. */
    val profitScrollable: Boolean = true,
    /** Value a drop at its NPC sell price when that is higher than the chosen mode's price. */
    val useNpcPriceIfHigher: Boolean = false,
    /** Announce each kill's net profit. */
    val sendDragonProfit: Boolean = false,
    /** Send that announcement to party chat rather than only to yourself. */
    val sendDragonProfitToPartyChat: Boolean = false,
    /** Announce the nearby-player count and Legion damage boost on each dragon death. */
    val sendLegionOnDeath: Boolean = false,
    /** Send that announcement to party chat rather than only to yourself. */
    val sendLegionToPartyChat: Boolean = false,
    // ---- panel UI state. Written by the panel itself as you use it, not by a config row. ----
    /** Active tab, by [com.soulreturns.ui.hud.tracker.TrackerTab] name. */
    val profitTab: String = "Session",
    /** Active sort id — see `DragonProfitHud.SORT_*`. */
    val profitSort: String = "value",
    /** Selected dragon filter, as display names. Empty = every dragon. */
    val profitFilter: Set<String> = emptySet(),
    /** Column id → shown. Absent means "whatever the column declares as its default". */
    val profitColumns: Map<String, Boolean> = emptyMap(),
    /**
     * The Source picker: `"ALL"`, `"SUMMONED"` or `"LOOTSHARE"`. A string rather than the enum
     * because `"ALL"` is the no-filter sentinel and has no [com.soulreturns.features.profit.dragon.KillSource]
     * entry to be.
     */
    val profitSourceFilter: String = "ALL",
    /**
     * How the player trades: 0 = instant, 1 = order, 2 = NPC. Read through
     * [com.soulreturns.data.prices.PriceMode.byIndex], which falls back to the default rather than
     * throwing on an index this build does not know.
     *
     * **Deliberately not the old `priceSource` key.** That field was an index into a *venue* picker
     * (bazaar buy / bazaar sell / lowest BIN / NPC) and the venue turned out not to be the player's
     * choice at all — an item is a bazaar item or an auction-house item, never both. The meanings
     * moved, so reusing the key would have silently reinterpreted a stored `2` as something else.
     * A new key means an old config just falls back to the default, and `ignoreUnknownKeys` drops the
     * dead one on the next save.
     */
    val priceMode: Int = 0,
)
