package com.soulreturns.features.scoreboard

import com.soulreturns.config.ChromaColor
import java.util.Locale

/**
 * Scoreboard text: Minecraft's legacy `§` codes plus Soul's two (from the mock): `§#rrggbb` is a picked
 * color and `§z` starts chroma, both running to the next color code like any color. Codes Minecraft does
 * not know (Hypixel's invisible line keys such as `§q`) are skipped, as vanilla does.
 */
object LegacyText {
    const val CHROMA = "§z"

    private const val COLORS = "0123456789abcdef"

    /** Vanilla's RGB for the sixteen legacy colors, in [COLORS] order. */
    private val LEGACY_RGB =
        intArrayOf(
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
        )

    private val CODE = Regex("§(#[0-9a-fA-F]{6}|.)")

    /** One run of text in one style. [rgb] null = the caller's default color; [chroma] wins over [rgb]. */
    data class Run(
        val text: String,
        val rgb: Int?,
        val chroma: Boolean,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strike: Boolean,
        val obfuscated: Boolean,
    ) {
        fun sameStyle(o: Run): Boolean =
            rgb == o.rgb && chroma == o.chroma && bold == o.bold && italic == o.italic &&
                underline == o.underline && strike == o.strike && obfuscated == o.obfuscated
    }

    /** Text without any code. */
    fun strip(text: String): String = if ('§' !in text) text else CODE.replace(text, "")

    /** [text] split into styled runs (empty runs dropped). */
    fun runs(text: String): List<Run> {
        val out = ArrayList<Run>()
        var rgb: Int? = null
        var chroma = false
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var obf = false
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) out += Run(sb.toString(), rgb, chroma, bold, italic, underline, strike, obf)
            sb.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '§' || i + 1 >= text.length) {
                sb.append(c)
                i++
                continue
            }
            val code = text[i + 1].lowercaseChar()
            if (code == '#' && i + 8 <= text.length) {
                val hex = text.substring(i + 2, i + 8).toIntOrNull(16)
                if (hex != null) {
                    flush()
                    rgb = hex
                    chroma = false
                    bold = false; italic = false; underline = false; strike = false; obf = false
                    i += 8
                    continue
                }
            }
            val legacy = COLORS.indexOf(code)
            if (legacy >= 0 || code == 'z' || code == 'r') flush()
            if (legacy >= 0) {
                rgb = LEGACY_RGB[legacy]
                chroma = false
                bold = false; italic = false; underline = false; strike = false; obf = false
            } else if (code == 'z') {
                chroma = true
                bold = false; italic = false; underline = false; strike = false; obf = false
            } else if (code == 'r') {
                rgb = null
                chroma = false
                bold = false; italic = false; underline = false; strike = false; obf = false
            } else if (code == 'l' || code == 'o' || code == 'n' || code == 'm' || code == 'k') {
                flush()
                if (code == 'l') bold = true
                if (code == 'o') italic = true
                if (code == 'n') underline = true
                if (code == 'm') strike = true
                if (code == 'k') obf = true
            }
            // Anything else is a junk key: skipped.
            i += 2
        }
        flush()
        return out
    }

    /**
     * [legacy] with the fewest codes that draw the same: junk keys gone, runs of one style merged
     * (`§bUpper Min§r§b§q§r§b§bes` -> `§bUpper Mines`). What line types match their regexes against.
     */
    fun normalize(legacy: String): String {
        if ('§' !in legacy) return legacy
        val runs = runs(legacy)
        val sb = StringBuilder(legacy.length)
        var prev: Run? = null
        for (run in runs) {
            if (prev == null || !run.sameStyle(prev)) {
                val plain = run.rgb == null && !run.chroma
                if (!(prev == null && plain && !run.hasFormat())) sb.append(colorCode(run))
                if (run.bold) sb.append("§l")
                if (run.italic) sb.append("§o")
                if (run.underline) sb.append("§n")
                if (run.strike) sb.append("§m")
                if (run.obfuscated) sb.append("§k")
            }
            sb.append(run.text)
            prev = run
        }
        return sb.toString()
    }

    private fun Run.hasFormat() = bold || italic || underline || strike || obfuscated

    private fun colorCode(run: Run): String =
        if (run.chroma) CHROMA else if (run.rgb == null) "§r" else rgbCode(run.rgb)

    /** The code for [rgb]: a legacy `§x` when it is one of the sixteen, else `§#rrggbb`. */
    fun rgbCode(rgb: Int): String {
        val i = LEGACY_RGB.indexOf(rgb and 0xFFFFFF)
        return if (i >= 0) "§" + COLORS[i] else String.format(Locale.ROOT, "§#%06x", rgb and 0xFFFFFF)
    }

    /** The code that switches to [color]: `§z` for chroma, else its RGB. */
    fun code(color: ChromaColor): String = if (color.chroma) CHROMA else rgbCode(color.argb)

    /** RGB of a legacy color char (`'6'` -> gold), or null. */
    fun legacyRgb(code: Char): Int? = COLORS.indexOf(code.lowercaseChar()).takeIf { it >= 0 }?.let { LEGACY_RGB[it] }

    /** The codes in effect at the end of [text] (color then formats; empty = default), to continue its style. */
    fun activeCodes(text: String): String {
        val last = runs(text + "\u0000").lastOrNull() ?: return ""
        val sb = StringBuilder()
        if (last.chroma || last.rgb != null) sb.append(colorCode(last))
        if (last.bold) sb.append("§l")
        if (last.italic) sb.append("§o")
        if (last.underline) sb.append("§n")
        if (last.strike) sb.append("§m")
        if (last.obfuscated) sb.append("§k")
        return sb.toString()
    }
}
