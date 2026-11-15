package com.soulreturns.features.dungeons.map

import com.soulreturns.config.ChromaColor
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.DungeonMapColors
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.model.ColorOption
import com.soulreturns.config.gui.model.ConfigCategory
import com.soulreturns.config.gui.model.ConfigSection
import com.soulreturns.config.gui.model.ConfigSubcategory
import com.soulreturns.config.gui.model.CycleOption
import com.soulreturns.config.gui.model.SliderOption
import com.soulreturns.config.gui.model.ToggleOption

/**
 * The option tree for [DungeonMapConfigScreen] — the dungeon-map subset of [com.soulreturns.config.SoulConfig],
 * grouped into category tabs (Map / Markers / Rooms / Doors / Sync). Colors are edited with the
 * color picker (background/border support opacity; room/door/class colors are solid). Built once and
 * cached so option identities are stable across frames (the screen keys editing state by identity).
 */
object DungeonMapConfigModel {
    private val defaults = SoulConfig()
    private val dc = defaults.dungeons.colors
    private val POSITIONS = listOf("Top", "Center", "Bottom")

    private fun toggle(
        label: String,
        description: String?,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
        default: Boolean,
        visible: () -> Boolean = { true },
    ) = ToggleOption(label, description, get, set, default, visible)

    private fun slider(label: String, get: () -> Double, set: (Double) -> Unit, default: Double, min: Double, max: Double, step: Double = 0.1, disabled: () -> Boolean = { false }) =
        SliderOption(label, get = get, set = set, default = default, min = min, max = max, step = step, disabled = disabled)

    private fun color(label: String, get: () -> ChromaColor, set: (ChromaColor) -> Unit, default: ChromaColor, hasAlpha: Boolean = false) =
        ColorOption(label, get = get, set = set, default = default, hasAlpha = hasAlpha)

    /** Color bound to one field of the `colors` sub-object. */
    private fun mapColor(label: String, get: (DungeonMapColors) -> ChromaColor, set: (DungeonMapColors, ChromaColor) -> DungeonMapColors, default: ChromaColor) =
        color(label, { get(cfg.dungeons.colors) }, { v -> updateColors { set(this, v) } }, default)

    val categories: List<ConfigCategory> by lazy {
        listOf(
            cat(
                "map", "Map",
                ConfigSection(
                    title = "Map",
                    subtitle = "Soul's own Catacombs map",
                    options = listOf(
                        toggle("Enable dungeon map", "Show the map HUD while in the Catacombs",
                            { cfg.dungeons.mapEnabled }, { v -> update { copy(mapEnabled = v) } }, defaults.dungeons.mapEnabled),
                        toggle("Hide in boss room", "Hide the map once you leave the room grid for the boss arena",
                            { cfg.dungeons.mapHideInBoss }, { v -> update { copy(mapHideInBoss = v) } }, defaults.dungeons.mapHideInBoss),
                        toggle("Show background", "Draw the map's filled background card",
                            { cfg.dungeons.mapBackground }, { v -> update { copy(mapBackground = v) } }, defaults.dungeons.mapBackground),
                        toggle("Show border", "Draw a border outline around the map",
                            { cfg.dungeons.mapBorder }, { v -> update { copy(mapBorder = v) } }, defaults.dungeons.mapBorder),
                        toggle("Rounded corners", "Round the background + border corners (off = square)",
                            { cfg.dungeons.mapRoundedCorners }, { v -> update { copy(mapRoundedCorners = v) } }, defaults.dungeons.mapRoundedCorners),
                        toggle("Show secret counts", "Per-room found/total secrets",
                            { cfg.dungeons.mapShowSecrets }, { v -> update { copy(mapShowSecrets = v) } }, defaults.dungeons.mapShowSecrets),
                        toggle("Show room names", "Identified room names, colored by clear state",
                            { cfg.dungeons.mapShowRoomNames }, { v -> update { copy(mapShowRoomNames = v) } }, defaults.dungeons.mapShowRoomNames),
                        CycleOption("Font", "Font for the map's text (Global Default = the main config's font)",
                            { cfg.dungeons.mapFontChoice }, { v -> update { copy(mapFontChoice = v) } },
                            listOf("Global Default", "Baloo 2", "Nunito", "Inter", "Minecraft"), defaults.dungeons.mapFontChoice),
                        toggle("Center name + count", "Center the name and secret count together as one stacked block",
                            { cfg.dungeons.mapCenterPair }, { v -> update { copy(mapCenterPair = v) } }, defaults.dungeons.mapCenterPair),
                        CycleOption("Secret count position", "Where the found/total count sits in the room",
                            { cfg.dungeons.mapSecretsPosition }, { v -> update { copy(mapSecretsPosition = v) } }, POSITIONS, defaults.dungeons.mapSecretsPosition,
                            visible = { !cfg.dungeons.mapCenterPair }),
                        CycleOption("Room name position", "Where the room name sits in the room",
                            { cfg.dungeons.mapNamePosition }, { v -> update { copy(mapNamePosition = v) } }, POSITIONS, defaults.dungeons.mapNamePosition,
                            visible = { !cfg.dungeons.mapCenterPair }),
                        toggle("Secret count above name", "When centering the pair: count on top (off = name on top)",
                            { cfg.dungeons.mapSecretsAboveName }, { v -> update { copy(mapSecretsAboveName = v) } }, defaults.dungeons.mapSecretsAboveName,
                            visible = { cfg.dungeons.mapCenterPair }),
                        toggle("Center names without secrets", "Center the name in rooms that show no secret count (puzzles, etc.)",
                            { cfg.dungeons.mapCenterNamelessRooms }, { v -> update { copy(mapCenterNamelessRooms = v) } }, defaults.dungeons.mapCenterNamelessRooms),
                        toggle("Auto-scale room names", "Shrink long names so they fit inside the room instead of overflowing",
                            { cfg.dungeons.mapAutoScaleNames }, { v -> update { copy(mapAutoScaleNames = v) } }, defaults.dungeons.mapAutoScaleNames),
                        slider("Text scale", { cfg.dungeons.mapTextScale.toDouble() }, { v -> update { copy(mapTextScale = v.toFloat()) } },
                            defaults.dungeons.mapTextScale.toDouble(), 0.5, 2.0, disabled = { cfg.dungeons.mapAutoScaleNames }),
                    ),
                ),
                ConfigSection(
                    title = "Appearance",
                    subtitle = "Background + border colors (opacity supported)",
                    options = listOf(
                        color("Background color", { cfg.dungeons.mapBackgroundColor }, { v -> update { copy(mapBackgroundColor = v) } }, defaults.dungeons.mapBackgroundColor, hasAlpha = true),
                        color("Border color", { cfg.dungeons.mapBorderColor }, { v -> update { copy(mapBorderColor = v) } }, defaults.dungeons.mapBorderColor, hasAlpha = true),
                    ),
                ),
            ),
            cat(
                "markers", "Markers",
                ConfigSection(
                    title = "Player Markers",
                    subtitle = "How teammates + you appear on the map",
                    options = listOf(
                        toggle("Teammate heads", "Show teammates as their player head (else an arrow)",
                            { cfg.dungeons.mapPlayerHeadsOthers }, { v -> update { copy(mapPlayerHeadsOthers = v) } }, defaults.dungeons.mapPlayerHeadsOthers),
                        toggle("Own head", "Show yourself as your player head (else an arrow)",
                            { cfg.dungeons.mapPlayerHeadSelf }, { v -> update { copy(mapPlayerHeadSelf = v) } }, defaults.dungeons.mapPlayerHeadSelf),
                        toggle("Class-color borders", "Ring teammate heads with their class color",
                            { cfg.dungeons.mapClassColorBorders }, { v -> update { copy(mapClassColorBorders = v) } }, defaults.dungeons.mapClassColorBorders),
                        slider("Teammate head scale", { cfg.dungeons.mapPlayerHeadScale.toDouble() }, { v -> update { copy(mapPlayerHeadScale = v.toFloat()) } }, defaults.dungeons.mapPlayerHeadScale.toDouble(), 0.5, 2.0),
                        slider("Own marker scale", { cfg.dungeons.mapPlayerScaleSelf.toDouble() }, { v -> update { copy(mapPlayerScaleSelf = v.toFloat()) } }, defaults.dungeons.mapPlayerScaleSelf.toDouble(), 0.5, 2.0),
                        toggle("Teammates above text", "Draw teammate markers on top of room names + counts",
                            { cfg.dungeons.mapOthersAboveText }, { v -> update { copy(mapOthersAboveText = v) } }, defaults.dungeons.mapOthersAboveText),
                        toggle("Own marker above text", "Draw your own marker on top of room names + counts",
                            { cfg.dungeons.mapSelfAboveText }, { v -> update { copy(mapSelfAboveText = v) } }, defaults.dungeons.mapSelfAboveText),
                        toggle("Show player names", "Name labels under teammate markers",
                            { cfg.dungeons.mapShowNames }, { v -> update { copy(mapShowNames = v) } }, defaults.dungeons.mapShowNames),
                        toggle("Names only when holding leap", "Show teammate names only while holding Spirit/Infinileap",
                            { cfg.dungeons.mapNamesOnlyWithLeap }, { v -> update { copy(mapNamesOnlyWithLeap = v) } }, defaults.dungeons.mapNamesOnlyWithLeap),
                    ),
                ),
                ConfigSection(
                    title = "Marker Colors",
                    subtitle = "Your marker + per-class colors",
                    options = listOf(
                        mapColor("Own marker", { it.markerSelf }, { c, v -> c.copy(markerSelf = v) }, dc.markerSelf),
                        mapColor("Archer", { it.classArcher }, { c, v -> c.copy(classArcher = v) }, dc.classArcher),
                        mapColor("Berserk", { it.classBerserk }, { c, v -> c.copy(classBerserk = v) }, dc.classBerserk),
                        mapColor("Mage", { it.classMage }, { c, v -> c.copy(classMage = v) }, dc.classMage),
                        mapColor("Healer", { it.classHealer }, { c, v -> c.copy(classHealer = v) }, dc.classHealer),
                        mapColor("Tank", { it.classTank }, { c, v -> c.copy(classTank = v) }, dc.classTank),
                    ),
                ),
            ),
            cat(
                "rooms", "Rooms",
                ConfigSection(
                    title = "Room Colors",
                    subtitle = "Fill color by room type",
                    options = listOf(
                        mapColor("Unopened", { it.unopened }, { c, v -> c.copy(unopened = v) }, dc.unopened),
                        mapColor("Normal", { it.normal }, { c, v -> c.copy(normal = v) }, dc.normal),
                        mapColor("Rare", { it.rare }, { c, v -> c.copy(rare = v) }, dc.rare),
                        mapColor("Entrance", { it.entrance }, { c, v -> c.copy(entrance = v) }, dc.entrance),
                        mapColor("Puzzle", { it.puzzle }, { c, v -> c.copy(puzzle = v) }, dc.puzzle),
                        mapColor("Trap", { it.trap }, { c, v -> c.copy(trap = v) }, dc.trap),
                        mapColor("Miniboss", { it.miniboss }, { c, v -> c.copy(miniboss = v) }, dc.miniboss),
                        mapColor("Fairy", { it.fairy }, { c, v -> c.copy(fairy = v) }, dc.fairy),
                        mapColor("Blood", { it.blood }, { c, v -> c.copy(blood = v) }, dc.blood),
                    ),
                ),
            ),
            cat(
                "doors", "Doors",
                ConfigSection(
                    title = "Door Colors",
                    subtitle = "Normal doors reuse the Normal room color",
                    options = listOf(
                        mapColor("Wither (closed)", { it.doorWither }, { c, v -> c.copy(doorWither = v) }, dc.doorWither),
                        mapColor("Blood", { it.doorBlood }, { c, v -> c.copy(doorBlood = v) }, dc.doorBlood),
                        mapColor("Entrance", { it.doorEntrance }, { c, v -> c.copy(doorEntrance = v) }, dc.doorEntrance),
                    ),
                ),
            ),
            cat(
                "sync", "Sync",
                ConfigSection(
                    title = "Party Sync",
                    subtitle = "Share matched rooms + secret counts with your party",
                    options = listOf(
                        toggle("Secret sync", "Share matched rooms + secret counts with teammates",
                            { cfg.dungeons.secretsSyncEnabled }, { v -> update { copy(secretsSyncEnabled = v) } }, defaults.dungeons.secretsSyncEnabled),
                    ),
                ),
            ),
        )
    }

    private fun cat(id: String, title: String, vararg sections: ConfigSection): ConfigCategory =
        ConfigCategory(id, title, listOf(ConfigSubcategory(id, title, sections.toList())))

    /** Mutate the dungeons block. */
    private inline fun update(crossinline block: com.soulreturns.config.DungeonsConfig.() -> com.soulreturns.config.DungeonsConfig) {
        ConfigManager.update { copy(dungeons = dungeons.block()) }
    }

    /** Mutate the dungeons `colors` sub-object. */
    private inline fun updateColors(crossinline block: DungeonMapColors.() -> DungeonMapColors) {
        ConfigManager.update { copy(dungeons = dungeons.copy(colors = dungeons.colors.block())) }
    }
}
