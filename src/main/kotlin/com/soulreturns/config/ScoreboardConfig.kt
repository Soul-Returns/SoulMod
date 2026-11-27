package com.soulreturns.config

import com.soulreturns.features.scoreboard.ScoreboardDefaults
import com.soulreturns.ui.hud.movable.HudAnchor
import com.soulreturns.ui.hud.movable.HudTransform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The Scoreboard HUD ([com.soulreturns.features.scoreboard.Scoreboard], spec `mocks/scoreboard/`): SkyBlock's
 * sidebar drawn by Soul from a list of lines (rule://scoreboard).
 *
 * Stored values are open strings, never enums: a line type, option or choice this version does not
 * know survives load and save untouched (a newer mod wrote it, or a type was retired).
 */
@Serializable
data class ScoreboardConfig(
    /** Draw Soul's scoreboard (and hide Hypixel's sidebar unless [keepVanilla]). */
    val enabled: Boolean = false,
    /** The "Everywhere" setup: every island and every activity without its own setup. */
    val main: ScoreboardSetup = ScoreboardSetup(ScoreboardDefaults.mainEntries()),
    /** "Different setups per activity". Off keeps [activities] stored but unused. */
    val perActivity: Boolean = false,
    /** Own setups by activity id ([com.soulreturns.features.scoreboard.ScoreboardActivities]); unknown ids are kept. */
    val activities: Map<String, ScoreboardSetup> = emptyMap(),
    /** Activities whose scoreboard sits at its own place ([activityPositions]); only while [perActivity]. */
    val ownPositionIn: List<String> = emptyList(),
    /** Own positions by activity id, kept while the switch for one is off. */
    val activityPositions: Map<String, HudTransform> = emptyMap(),
    /** One look for every setup. */
    val look: ScoreboardLook = ScoreboardLook(),
    /** "Keep Hypixel's sidebar too": off = Soul's scoreboard replaces it. */
    val keepVanilla: Boolean = false,
    /** The one-time "Custom Scoreboard setup found" import offer was shown (accepted or not). */
    val importOffered: Boolean = false,
    /** Move GUI transform of the Everywhere position. Default: 6 px from the right edge, vertically centered (the mock's spot). */
    val hud: HudTransform = HudTransform(hAnchor = HudAnchor.END, vAnchor = HudAnchor.CENTER, offsetX = -6f),
)

/** One setup: the ordered lines, Header first and Footer last. */
@Serializable
data class ScoreboardSetup(
    val entries: List<ScoreboardEntry> = emptyList(),
)

/**
 * One line of a setup. [type] is a [com.soulreturns.features.scoreboard.LineType] id (an id this version
 * does not know is kept and skipped when drawing). [options] holds the type's option values by key
 * (absent = the type's default; unknown keys are kept). [colors] holds picked colors by color-part key
 * (absent = Hypixel's own color).
 */
@Serializable
data class ScoreboardEntry(
    val type: String,
    val hidden: Boolean = false,
    val options: JsonObject = JsonObject(emptyMap()),
    val colors: Map<String, ChromaColor> = emptyMap(),
)

/**
 * The scoreboard's look, shared by every setup. Choice values are strings (see [ScoreboardConfig]).
 * Chroma speed and motion are Soul-wide (`cfg.chroma`), not stored here.
 */
@Serializable
data class ScoreboardLook(
    val background: Boolean = true,
    /** Background opacity in percent (0..100); the color's own alpha is ignored. */
    val backgroundOpacity: Int = 55,
    val backgroundColor: ChromaColor = ChromaColor(0xFF000000.toInt()),
    /** `left`, `center` or `right`: lines without their own alignment. */
    val align: String = "left",
    val textShadow: Boolean = true,
    /** Parts drawn in chroma as a whole: `header`, `labels`, `values`, `footer`, `border`. */
    val chromaOn: List<String> = emptyList(),
    // Advanced
    /** `long` (1,193,004,241) or `short` (1.19B). */
    val numbers: String = "long",
    /** `tcn` Purse: 1,234 · `ctn` all colored · `cnt` 1,234 Purse · `cnrt` 1,234 Purse (white label). */
    val numberStyle: String = "tcn",
    val cornerRadius: Int = 6,
    val padding: Int = 5,
    val border: Boolean = false,
    /** `gradient`: [borderColor] and [borderColor2] blend round the border and turn; `solid`: [borderColor] only. */
    val borderStyle: String = BORDER_GRADIENT,
    /** The border's color (with `gradient`: its first color). */
    val borderColor: ChromaColor = ChromaColor(0xFFAF59FF.toInt()),
    /** The gradient's second color (Custom Scoreboard's default pair: #AF59FF to #7FEDFF). */
    val borderColor2: ChromaColor = ChromaColor(0xFF7FEDFF.toInt()),
    /** How fast the gradient turns: one turn round the border takes `20 / speed` seconds, like chroma. */
    val borderSpeed: Float = 3f,
    val blur: Boolean = false,
    val backgroundImage: Boolean = false,
    /** Extra px between lines. */
    val lineSpacing: Int = 0,
    /** Lines with no value right now are left out (off: they show with a dash). */
    val hideEmpty: Boolean = true,
    /** Ignore the setup: Hypixel's own lines, restyled with this look. */
    val hypixelLinesOnly: Boolean = false,
    /** Keep the last lines while the sidebar is gone during a server switch. */
    val keepWhileSwitching: Boolean = false,
    val clickActions: Boolean = false,
    val hideWithTab: Boolean = false,
    val hideWithChat: Boolean = false,
    val hideWithF3: Boolean = false,
    /** Also replace the sidebar outside SkyBlock (Hypixel's lines, restyled). */
    val outsideSkyblock: Boolean = false,
) {
    companion object {
        const val BORDER_GRADIENT = "gradient"
        const val BORDER_SOLID = "solid"
        const val MIN_BORDER_SPEED = 0.5f
        const val MAX_BORDER_SPEED = 10f
    }
}
