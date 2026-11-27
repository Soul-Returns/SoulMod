package com.soulreturns.config.gui.render

/** Kerning between two codepoints in px at nominal [size]; 0 when either glyph is not this font's own. */
fun interface Kerning {
    fun kernPx(prev: Int, next: Int, size: Float): Float
}

/**
 * The pen across a line drawn as several runs (colors, weights, fonts). Runs start at the exact
 * fractional advance of everything before them, plus the kerning between two runs of the same font,
 * so a split line lays out exactly like the same characters in one run. Callers snap the line's
 * origin once ([SoulText.snapX]) and draw every run at `origin + start`; rounding each run on its own
 * moves it up to half a unit and can swallow a space.
 */
class RunPen(private val size: Float) {
    /** Advance of every run placed so far, from the line's origin. */
    var pen = 0f
        private set
    private var prevFont: Kerning? = null
    private var prevCp = -1

    /** Place [text] of [advance] drawn with [font] (null: no kerning, e.g. the Minecraft font); returns its start. */
    fun place(text: String, advance: Float, font: Kerning?): Float {
        if (text.isEmpty()) return pen
        if (font != null && font === prevFont && prevCp >= 0) pen += font.kernPx(prevCp, text.codePointAt(0), size)
        val start = pen
        pen += advance
        prevFont = font
        prevCp = text.codePointBefore(text.length)
        return start
    }

    /** Advance by [px] of something that is not text (an icon, a fixed gap); no kerning across it. */
    fun gap(px: Float) {
        pen += px
        prevFont = null
        prevCp = -1
    }
}
