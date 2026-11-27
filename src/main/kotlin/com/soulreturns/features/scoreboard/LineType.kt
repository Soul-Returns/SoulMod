package com.soulreturns.features.scoreboard

import com.soulreturns.config.ChromaColor
import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.config.ScoreboardLook
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A kind of scoreboard line (Purse, SkyBlock time, a tab widget, …). Everything the setup screen shows
 * about it (picker row, inspector) comes from here, and [lines] turns the current sources into what it
 * draws. One small `object` per type under `lines/`, listed once in [ScoreboardCatalog].
 *
 * Lines are strings in Minecraft legacy formatting plus `§#rrggbb` and `§z` ([LegacyText]). A blank
 * line is a gap: double gaps fold and gaps at the ends drop ([ScoreboardBuilder]).
 */
abstract class LineType(
    /** Stable id stored in the config; never rename (a stored id that no type has is kept and skipped). */
    val id: String,
    /** Picker group, one of [ScoreboardCatalog.groups]. */
    val group: String,
    val name: String,
    /** One line for the picker and the inspector. */
    val description: String,
    /** What it looks like, colored, one string per line (picker preview). */
    val example: List<String>,
) {
    /** Where it shows, for the inspector ("Mining islands"); null = everywhere it has a value. */
    open val where: String? = null

    /** Picker tag: `Soul` for Soul's own lines, `tab list` for tab widgets, null for none. */
    open val tag: String? = null

    /** The inspector's option rows, in order. */
    open val options: List<LineOption> = emptyList()

    /** The colorable parts and how picked colors apply ([LineColors]). */
    open val colors: LineColors = LineColors.None

    /** [PIN_TOP] / [PIN_BOTTOM] keep it first / last (Header, Footer); no drag, no remove. */
    open val pinned: Int = PIN_NONE

    /** Can be added more than once (Custom text, Empty line); others once per setup. */
    open val repeatable: Boolean = false

    /** Listed in the "+ Add line" picker. */
    open val inPicker: Boolean get() = pinned == PIN_NONE

    /**
     * Sidebar lines this type stands for (indexes into [ScoreboardSnapshot.sidebar]), whether or not it
     * is in the setup. The rest are new to Soul and go to "New lines from Hypixel". Cheap and pure.
     */
    open fun claims(sources: ScoreboardSnapshot): Collection<Int> = emptyList()

    /** What it draws now; empty = not showing now. Pure apart from small per-type trackers (gains). */
    abstract fun lines(ctx: LineContext): List<String>

    /** Drawn instead of nothing when Look › Hide lines with no value is off (`Purse: §8-`); empty = nothing. */
    open fun emptyLines(ctx: LineContext): List<String> = emptyList()

    /** A fresh entry for the picker's "+ Add line" (defaults only). */
    open fun newEntry(): ScoreboardEntry = ScoreboardEntry(id)

    companion object {
        const val PIN_NONE = 0
        const val PIN_TOP = 1
        const val PIN_BOTTOM = 2
    }
}

// ------------------------------------------------------------------------------------------- options

/**
 * One inspector row of a line's options. Values live in [ScoreboardEntry.options] under [key]; absent =
 * [defaultJson]. [visible] hides the row for the current values (Header's text only when "Custom").
 */
sealed class LineOption(
    val key: String,
    val label: String,
    val hint: String?,
    val visible: (OptionValues) -> Boolean,
) {
    abstract val defaultJson: JsonPrimitive
}

/** On/off switch. */
class LineSwitch(key: String, label: String, val default: Boolean, hint: String? = null, visible: (OptionValues) -> Boolean = { true }) :
    LineOption(key, label, hint, visible) {
    override val defaultJson: JsonPrimitive get() = JsonPrimitive(default)
}

/**
 * One of a few values. [segmented] = buttons in a row (2-4 short labels), else a dropdown. Values are
 * strings; a stored value no choice has (a newer mod's) is kept, and reads fall back to [default].
 */
class LineChoice(
    key: String,
    label: String,
    val choices: List<Choice>,
    val default: String,
    val segmented: Boolean = choices.size <= 4,
    hint: String? = null,
    visible: (OptionValues) -> Boolean = { true },
) : LineOption(key, label, hint, visible) {
    override val defaultJson: JsonPrimitive get() = JsonPrimitive(default)

    class Choice(val value: String, val label: String)
}

/**
 * A text field. [rich]: the mock's text editor (preview, Color…, Chroma, B, I, ↺ at the cursor);
 * [values]: plus "Insert value" with [ScoreboardValues] placeholders.
 */
class LineText(
    key: String,
    label: String,
    val default: String,
    val rich: Boolean = false,
    val values: Boolean = false,
    val width: Int = 120,
    hint: String? = null,
    visible: (OptionValues) -> Boolean = { true },
) : LineOption(key, label, hint, visible) {
    override val defaultJson: JsonPrimitive get() = JsonPrimitive(default)
}

/** A whole number with a stepper. */
class LineNumber(
    key: String,
    label: String,
    val default: Int,
    val min: Int,
    val max: Int,
    val unit: String? = null,
    hint: String? = null,
    visible: (OptionValues) -> Boolean = { true },
) : LineOption(key, label, hint, visible) {
    override val defaultJson: JsonPrimitive get() = JsonPrimitive(default)
}

/** Shared option rows. */
object LineOptions {
    const val ALIGN = "align"

    /** Left / Center / Right; "" = the Look's alignment. */
    fun align(default: String = "", visible: (OptionValues) -> Boolean = { true }) =
        LineChoice(
            ALIGN, "Alignment",
            listOf(LineChoice.Choice("left", "Left"), LineChoice.Choice("center", "Center"), LineChoice.Choice("right", "Right")),
            default, segmented = true, visible = visible,
        )

    /** Hypixel's leading space on its indented lines. */
    fun indent() = LineSwitch("indent", "Indent like Hypixel", true)

    /** The text in front of a value ("Purse:"). */
    fun label(default: String) = LineText("label", "Label", default, hint = "Text in front of the value")

    fun gain() = LineSwitch("gain", "Show gain", true, "Like (+5) after a change")
}

/** A line's option values with the type's defaults behind them. */
class OptionValues(val json: JsonObject, private val schema: List<LineOption>) {
    private fun prim(key: String): JsonPrimitive? =
        (json[key] as? JsonPrimitive) ?: schema.firstOrNull { it.key == key }?.defaultJson

    fun bool(key: String): Boolean = prim(key)?.booleanOrNull ?: false

    fun int(key: String): Int {
        val o = schema.firstOrNull { it.key == key } as? LineNumber
        val v = prim(key)?.intOrNull ?: o?.default ?: 0
        return if (o != null) v.coerceIn(o.min, o.max) else v
    }

    /** A string option; a choice value no choice has falls back to its default. */
    fun string(key: String): String {
        val raw = prim(key)?.takeIf { it.isString }?.content
        val o = schema.firstOrNull { it.key == key }
        if (o is LineChoice) return raw?.takeIf { v -> o.choices.any { it.value == v } } ?: o.default
        return raw ?: (o as? LineText)?.default ?: ""
    }
}

// -------------------------------------------------------------------------------------------- colors

/**
 * One color swatch of a line. Stored in [ScoreboardEntry.colors] under [key]; absent = [default] when it
 * is set, else the color Hypixel draws (`Hypixel's`, or [noneLabel]). [chromaTarget] is the Look's
 * "Chroma on" part that overrides it (`labels`, `values`, `header`, `footer`).
 */
class ColorPart(
    val key: String,
    val label: String,
    /** The swatch's color while unset (RGB), shown next to "Hypixel's"; null = no swatch color ("None"). */
    val default: Int?,
    val hint: String? = null,
    val chromaTarget: String? = null,
    val noneLabel: String = "Hypixel's",
    val visible: (OptionValues) -> Boolean = { true },
)

/**
 * How a line's picked colors apply. The setup screen lists [parts] (plus [ONE_VALUE_KEY] for
 * [HeadingLabels]); [ScoreboardBuilder] calls [apply] on each drawn line.
 */
sealed class LineColors(val parts: List<ColorPart>) {
    /** Recolor line [index] of the type's output; [code] gives the code for a part, or null = keep Hypixel's. */
    abstract fun apply(line: String, index: Int, code: (String) -> String?): String

    /** No colors (gaps, lines that color themselves from [LineContext.color]). */
    object None : LineColors(emptyList()) {
        override fun apply(line: String, index: Int, code: (String) -> String?) = line
    }

    /** A type that colors its own lines (Header, Footer): parts listed, nothing applied here. */
    class Own(parts: List<ColorPart>) : LineColors(parts) {
        override fun apply(line: String, index: Int, code: (String) -> String?) = line
    }

    /** One text color: replaces the line's main color (the longest run, the name rather than its icon). */
    class Text(default: Int) : LineColors(listOf(ColorPart("t", "Text", default, chromaTarget = "values"))) {
        override fun apply(line: String, index: Int, code: (String) -> String?): String {
            val c = code("t") ?: return line
            return ColorText.replaceMain(line, c)
        }
    }

    /** `Label: value`: the label and the value's first color. */
    class LabelValue(label: Int, value: Int) :
        LineColors(listOf(ColorPart("l", "Label", label, chromaTarget = "labels"), ColorPart("v", "Value", value, chromaTarget = "values"))) {
        override fun apply(line: String, index: Int, code: (String) -> String?): String =
            ColorText.labelValue(line, code("l"), code("v"))
    }

    /** A heading line, then `Label: value` lines; values keep Hypixel's colors unless [ONE_VALUE_KEY] is on. */
    class HeadingLabels(heading: Int, value: Int) :
        LineColors(
            listOf(
                ColorPart("h", "Heading", heading, "The first line", chromaTarget = "labels"),
                ColorPart("l", "Labels", 0xFFFFFF, "Text before a colon", chromaTarget = "labels"),
                ColorPart("v", "Values", value, chromaTarget = "values", visible = { it.bool(ONE_VALUE_KEY) }),
            ),
        ) {
        override fun apply(line: String, index: Int, code: (String) -> String?): String {
            if (index == 0) return ColorText.whole(line, code("h"))
            val v = code("v")
            return if (':' in LegacyText.strip(line)) ColorText.labelValue(line, code("l"), v) else if (v != null) ColorText.whole(line, v) else line
        }
    }

    /** Drawn as Hypixel draws it; a tint recolors the whole line (tab widgets). */
    object Tint : LineColors(listOf(ColorPart("tint", "Tint", null, "Drawn as Hypixel draws it; a tint recolors the whole line", noneLabel = "None"))) {
        override fun apply(line: String, index: Int, code: (String) -> String?) = ColorText.whole(line, code("tint"))
    }

    /** `11/26/26 m14AC`: the date and the server id. */
    object DateServer : LineColors(listOf(ColorPart("d", "Date", 0xAAAAAA, chromaTarget = "values"), ColorPart("s", "Server id", 0x555555, chromaTarget = "values"))) {
        override fun apply(line: String, index: Int, code: (String) -> String?): String {
            val d = code("d")
            val s = code("s")
            if (d == null && s == null) return line
            val text = LegacyText.strip(line).trim()
            val cut = text.indexOf(' ')
            if (cut < 0) return (d ?: "§7") + text
            return (d ?: "§7") + text.substring(0, cut + 1) + (s ?: "§8") + text.substring(cut + 1)
        }
    }

    companion object {
        /** "One color for all values" switch of [HeadingLabels], stored in the entry's options. */
        const val ONE_VALUE_KEY = "oneValueColor"
    }
}

/** String surgery behind [LineColors]. */
object ColorText {
    private val MAIN = Regex("§(#[0-9a-fA-F]{6}|[0-9a-fA-Fz])([^§]*)")
    private val LABEL = Regex("^((?:§(?:#[0-9a-fA-F]{6}|[0-9a-fA-Fk-oK-OrRzZ])|[^\\w§:])*)([A-Za-z][^§:]*:)( ?)((?:§(?:#[0-9a-fA-F]{6}|[0-9a-fA-Fz]))?)(.*)$")

    /** The whole line in [code], its own colors dropped (leading spaces kept). */
    fun whole(line: String, code: String?): String {
        if (code == null) return line
        val lead = line.takeWhile { it == ' ' }
        return lead + code + LegacyText.strip(line.substring(lead.length))
    }

    /** The color code in front of the longest run of letters/digits replaced by [code]. */
    fun replaceMain(line: String, code: String): String {
        var best: MatchResult? = null
        var bestLen = -1
        for (m in MAIN.findAll(line)) {
            val n = m.groupValues[2].count { it.isLetterOrDigit() }
            if (n > bestLen) {
                bestLen = n
                best = m
            }
        }
        val m = best ?: return whole(line, code)
        val codeLen = 1 + m.groupValues[1].length
        return line.substring(0, m.range.first) + code + line.substring(m.range.first + codeLen)
    }

    /** `Label: §6value`: label in [label], the value's color code swapped for [value] (null = keep). */
    fun labelValue(line: String, label: String?, value: String?): String {
        if (label == null && value == null) return line
        val lead = line.takeWhile { it == ' ' }
        val m = LABEL.matchEntire(line.substring(lead.length)) ?: return if (value != null) whole(line, value) else line
        val (pre, lbl, space, vcode, rest) = m.destructured
        val l = if (label != null) label + lbl + "§r" else lbl
        val v = if (value != null) value + LegacyText.strip(rest) else vcode + rest
        return lead + pre + l + space + v
    }

    /** [text] in [code] throughout, its bold/italic/underline/strike kept (a whole part in chroma). */
    fun recolor(text: String, code: String): String {
        val sb = StringBuilder()
        for (run in LegacyText.runs(text)) {
            sb.append(code)
            if (run.bold) sb.append("§l")
            if (run.italic) sb.append("§o")
            if (run.underline) sb.append("§n")
            if (run.strike) sb.append("§m")
            sb.append(run.text)
        }
        return sb.toString()
    }
}

// ------------------------------------------------------------------------------------------- context

/**
 * What [LineType.lines] gets: the sources, this entry's options and colors, the Look and the time.
 * [activity] is the detected activity id (null = none).
 */
class LineContext(
    val sources: ScoreboardSnapshot,
    val entry: ScoreboardEntry,
    val options: OptionValues,
    val look: ScoreboardLook,
    val nowMs: Long,
    val activity: String? = null,
) {
    val fields: SidebarFields get() = sources.fields

    /** Sidebar line [index] as Hypixel draws it, or null for -1. */
    fun sidebar(index: Int): SourceLine? = sources.sidebar.getOrNull(index)

    /** The code for a picked part color (`§#rrggbb` / `§z`) or the Look's chroma on [chromaTarget]; null = unset. */
    fun color(part: String, chromaTarget: String? = null): String? {
        if (chromaTarget != null && chromaTarget in look.chromaOn) return LegacyText.CHROMA
        val c: ChromaColor = entry.colors[part] ?: return null
        return LegacyText.code(c)
    }

    /** [value] in the Look's number format (long `1,193,004,241` or short `1.19B`). */
    fun number(value: Double): String = ScoreboardFormat.number(value, look.numbers == "short")

    /** `Label: <value>` in the Look's number style; [suffix] (a gain) follows the value. */
    fun labelValue(label: String, valueCode: String, value: String, suffix: String = ""): String =
        ScoreboardFormat.labelValue(look.numberStyle, label, valueCode, value) + suffix

    /** Expand `{value}` placeholders ([ScoreboardValues]). */
    fun expand(text: String): String = ScoreboardValues.expand(text, this)
}
