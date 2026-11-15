package com.soulreturns.features.profileviewer.gui

import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.data.hypixel.calc.LevelCalculator
import com.soulreturns.data.hypixel.calc.LevelInfo
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The drawing primitives a [ProfileTab] may use, in Soul-logical coordinates. Implemented by
 * [ProfileViewerScreen] as a thin forward onto [com.soulreturns.ui.screen.SoulScreenBase]'s protected
 * helpers — so a tab file needs no access to the screen's internals, and cannot reach past this to
 * mutate it.
 *
 * Corners are named rather than passed as Blaze3D pipelines, to keep the render backend out of the
 * tab API.
 */
interface ProfilePainter {
    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int = CORNER_MEDIUM)

    fun text(s: String, x: Int, y: Int, color: Int, size: Float, bold: Boolean = false)

    /** Cap-height centered between [top] and [bottom] — what rows of labels should use. */
    fun textV(s: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean = false)

    fun measure(s: String, size: Float, bold: Boolean = false): Int

    fun wrap(s: String, maxW: Int, size: Float): List<String>

    companion object {
        const val CORNER_SQUARE = 0
        const val CORNER_SMALL = 1
        const val CORNER_MEDIUM = 2
        const val CORNER_PILL = 3
    }
}

/**
 * A fixed-width column with a y cursor: the surface a [ProfileTab] appends rows to.
 *
 * A tab never computes an absolute y and never knows the scroll offset, so scrolling, clipping and
 * content height stay the screen's business and cannot drift from one tab to the next. The screen
 * reads [y] when the tab is done to get the content height.
 *
 * ### Two mechanisms worth knowing before writing a tab
 *
 * **Sections measure themselves by running their content twice.** [section] has to draw its card
 * *behind* rows whose total height is only known after they are laid out, and draw order is
 * submission order. So it lays the content out once on a **dry** canvas (every paint suppressed, only
 * the cursor moves), draws the card to the height that produced, then lays it out again for real.
 * Content lambdas must therefore be pure drawing — no counters, no mutation.
 *
 * **Rows outside the viewport are not painted.** Every paint goes through this class, which drops
 * anything fully above [viewTop] or below [viewBottom]. The scissor from
 * [com.soulreturns.ui.screen.SoulScreenBase.beginClip] is still what makes the edges clean; this
 * merely avoids submitting a few thousand glyph quads for a floor table that is scrolled off screen.
 */
class ProfileCanvas internal constructor(
    private val p: ProfilePainter,
    /** Left edge of this column. */
    val x0: Int,
    /** Right edge of this column. */
    val x1: Int,
    startY: Int,
    private val viewTop: Int,
    private val viewBottom: Int,
    private val dry: Boolean,
) {
    companion object {
        // The legacy 13/11/10 em sizes in SoulText's vanilla-relative scale (see SoulScreenBase).
        const val F_HEADING = 13f * MsdfFont.LEGACY_EM
        const val F_BODY = 11f * MsdfFont.LEGACY_EM
        const val F_CAPTION = 10f * MsdfFont.LEGACY_EM

        /** One option-style row, matching the config menu's 32 px so the two screens feel related. */
        const val ROW_H = 18
        const val SECTION_PAD = 10
        const val SECTION_GAP = 12
        const val CAPTION_LINE_H = 11

        /** Column widths for the floor tables: Floor, Runs, Best, Fastest, S, S+. */
        val FLOOR_COLUMNS = intArrayOf(34, 46, 46, 62, 62, 62)
    }

    /** Bottom of everything appended so far. The screen reads this as the content height. */
    var y: Int = startY
        private set

    val width: Int get() = x1 - x0

    // ------------------------------------------------------------------ primitives

    private fun visible(top: Int, bottom: Int) = !dry && bottom >= viewTop && top <= viewBottom

    private fun rect(rx0: Int, ry0: Int, rx1: Int, ry1: Int, color: Int, corner: Int) {
        if (visible(ry0, ry1)) p.rect(rx0, ry0, rx1, ry1, color, corner)
    }

    private fun textV(s: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean = false) {
        if (visible(top, bottom)) p.textV(s, x, top, bottom, color, size, bold)
    }

    private fun textAt(s: String, x: Int, top: Int, color: Int, size: Float, bold: Boolean = false) {
        if (visible(top, top + size.roundToInt() + 4)) p.text(s, x, top, color, size, bold)
    }

    fun measure(s: String, size: Float = F_BODY, bold: Boolean = false): Int = p.measure(s, size, bold)

    /** Advance the cursor without drawing. */
    fun gap(h: Int) {
        y += h
    }

    // ------------------------------------------------------------------ containers

    /**
     * A titled card, laid out exactly like the config menu's section cards: the title (and optional
     * word-wrapped subtitle) sit above a rounded panel holding [content].
     *
     * [content] runs twice — see the class KDoc. Keep it pure.
     */
    fun section(title: String, subtitle: String? = null, content: ProfileCanvas.() -> Unit) {
        val subLines = subtitle?.let { p.wrap(it, width - 8, F_CAPTION) } ?: emptyList()
        val headH = if (subLines.isEmpty()) 18 else 14 + subLines.size * CAPTION_LINE_H + 1
        textAt(title, x0 + 4, y + 2, Theme.TEXT_DIM, F_BODY)
        subLines.forEachIndexed { i, line ->
            textAt(line, x0 + 4, y + 14 + i * CAPTION_LINE_H, Theme.TEXT_FAINT, F_CAPTION)
        }
        val cardTop = y + headH

        // Measure: lay the body out on a dry canvas to learn how tall the card has to be.
        val probe = ProfileCanvas(p, x0 + SECTION_PAD, x1 - SECTION_PAD, cardTop + SECTION_PAD, viewTop, viewBottom, dry = true)
        probe.content()
        val cardBottom = probe.y + SECTION_PAD

        rect(x0, cardTop, x1, cardBottom, Theme.SECTION, ProfilePainter.CORNER_MEDIUM)
        val body = ProfileCanvas(p, x0 + SECTION_PAD, x1 - SECTION_PAD, cardTop + SECTION_PAD, viewTop, viewBottom, dry)
        body.content()

        y = cardBottom + SECTION_GAP
    }

    /** A hairline between rows inside a section. */
    fun divider() {
        rect(x0, y + 2, x1, y + 3, Theme.SEPARATOR, ProfilePainter.CORNER_SQUARE)
        y += 5
    }

    // ------------------------------------------------------------------ rows

    /** `Label ······ value`, the workhorse row. */
    fun statRow(label: String, value: String, valueColor: Int = Theme.TEXT, bold: Boolean = false) {
        val top = y
        val bottom = y + ROW_H
        textV(label, x0, top, bottom, Theme.TEXT_DIM, F_BODY)
        textV(value, x1 - measure(value, F_BODY, bold), top, bottom, valueColor, F_BODY, bold)
        y = bottom
    }

    /** A whole row of dim explanatory text, word-wrapped to the column. */
    fun note(text: String, color: Int = Theme.TEXT_FAINT) {
        for (line in p.wrap(text, width, F_CAPTION)) {
            textV(line, x0, y, y + CAPTION_LINE_H + 4, color, F_CAPTION)
            y += CAPTION_LINE_H + 2
        }
        y += 2
    }

    /**
     * `Label   Lv 34   [progress]   12.3M` — the row every levelled thing uses, so Catacombs, a
     * dungeon class, a skill and a slayer all read identically.
     *
     * @param highlight draws the level in the accent color (the currently selected dungeon class).
     * @param trailing overrides the right-hand column, which is otherwise total XP. Use it where a
     *   total is not the number a player thinks in: SkyBlock Level is exactly 100 XP per level, so
     *   `6/100` says something and `60.4k` says nothing.
     */
    fun levelRow(
        label: String,
        level: LevelInfo,
        experience: Double,
        highlight: Boolean = false,
        trailing: String? = null,
    ) {
        val top = y
        val bottom = y + ROW_H
        val labelW = (width * 0.34f).roundToInt().coerceIn(70, 150)
        val xpText = trailing ?: LevelCalculator.formatXp(experience)
        val xpW = measure(xpText, F_BODY).coerceAtLeast(measure("000.0M", F_BODY))
        val levelText = if (level.maxed) "Lv ${level.level} MAX" else "Lv ${level.level}"
        val levelW = measure("Lv 00 MAX", F_BODY)

        textV(label, x0, top, bottom, if (highlight) Theme.TEXT else Theme.TEXT_DIM, F_BODY, bold = highlight)
        textV(
            levelText,
            x0 + labelW,
            top,
            bottom,
            if (level.maxed) Theme.ACCENT else if (highlight) Theme.ACCENT else Theme.TEXT,
            F_BODY,
        )

        val barX0 = x0 + labelW + levelW + 8
        val barX1 = x1 - xpW - 8
        if (barX1 - barX0 >= 24) progressBar(barX0, barX1, (top + bottom) / 2, level.progress.toFloat())
        textV(xpText, x1 - measure(xpText, F_BODY), top, bottom, Theme.TEXT_FAINT, F_BODY)
        y = bottom
    }

    /** A 4 px pill track with an accent fill. [fraction] is clamped. */
    fun progressBar(bx0: Int, bx1: Int, cy: Int, fraction: Float, color: Int = Theme.ACCENT) {
        rect(bx0, cy - 2, bx1, cy + 2, Theme.CONTROL, ProfilePainter.CORNER_PILL)
        val f = fraction.coerceIn(0f, 1f)
        val fill = bx0 + ((bx1 - bx0) * f).roundToInt()
        if (fill > bx0 + 1) rect(bx0, cy - 2, fill, cy + 2, color, ProfilePainter.CORNER_PILL)
    }

    /**
     * A fixed-column table. [widths] are per column; the last column takes the remainder if the row
     * is wider than the sum. A header row is dim, body rows are normal.
     */
    fun tableRow(cells: List<String>, widths: IntArray, header: Boolean = false) {
        val top = y
        val bottom = y + if (header) ROW_H - 2 else ROW_H - 3
        var cx = x0
        for ((i, cell) in cells.withIndex()) {
            val w = widths.getOrElse(i) { 50 }
            textV(cell, cx, top, bottom, if (header) Theme.TEXT_DIM else Theme.TEXT, F_CAPTION, bold = header)
            cx += w
        }
        if (header) rect(x0, bottom, x1, bottom + 1, Theme.SEPARATOR, ProfilePainter.CORNER_SQUARE)
        y = bottom + if (header) 3 else 0
    }
}

/** `24.31` — an average level, always two decimals, so a column of them lines up. */
fun formatAverage(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
