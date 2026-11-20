package com.soulreturns.features.profileviewer.gui.kit

import kotlin.math.max

/**
 * A line of text in colored runs, parsed from Minecraft `§` codes (`§0`..`§f` colors, `§l` bold,
 * `§r` reset; other format codes are dropped). Item names, lore-like values and every Soul text that
 * mixes colors (`32 §8(5.0%)`) go through this, because SoulText itself draws one color per call.
 *
 * Immutable and cheap: a section builds these every frame.
 */
class Rich private constructor(val runs: List<Run>) {
    /**
     * [color] null = the drawing node's default color. [size] set = a run in its own size (not bold unless
     * [bold]), on the line's baseline: a unit after a big number (`+38 Strength`).
     */
    class Run(val text: String, val color: Int?, val bold: Boolean, val minW: Int = 0, val size: Float? = null)

    val isEmpty: Boolean get() = runs.all { it.text.isEmpty() }

    /** The text without codes. */
    val plain: String get() = runs.joinToString("") { it.text }

    private fun Run.width(m: KitMeasure, lineSize: Float, lineBold: Boolean): Int =
        m.width(text, size ?: lineSize, if (size != null) bold else lineBold || bold)

    fun width(m: KitMeasure, size: Float, bold: Boolean = false): Int =
        runs.sumOf { max(it.width(m, size, bold), it.minW) }

    /** Draws on one line, vertically centered between [top] and [bottom]; returns the end x. */
    fun draw(c: KitCanvas, x: Int, top: Int, bottom: Int, size: Float, color: Int, bold: Boolean = false): Int {
        var cx = x
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val rs = r.size
            if (rs == null) {
                c.text(r.text, cx, top, bottom, r.color ?: color, size, bold || r.bold)
            } else {
                val dy = ((size - rs) * BASELINE).toInt()
                c.text(r.text, cx, top + dy, bottom + dy, r.color ?: color, rs, r.bold)
            }
            cx += max(r.width(c, size, bold), r.minW)
        }
        return cx
    }

    /** This line cut to [maxW] with a trailing `…`, or itself when it fits. */
    fun ellipsize(m: KitMeasure, size: Float, maxW: Int, bold: Boolean = false): Rich {
        if (width(m, size, bold) <= maxW) return this
        val ell = m.width(ELLIPSIS, size, bold)
        val out = ArrayList<Run>()
        var used = 0
        for (r in runs) {
            val w = r.width(m, size, bold)
            if (used + w + ell <= maxW) {
                out += r
                used += w
                continue
            }
            var end = r.text.length
            while (end > 0 && used + Run(r.text.substring(0, end), r.color, r.bold, 0, r.size).width(m, size, bold) + ell > maxW) end--
            out += Run(r.text.substring(0, end).trimEnd() + ELLIPSIS, r.color, r.bold, 0, r.size)
            return Rich(out)
        }
        return Rich(out)
    }

    /** Greedy word wrap to lines no wider than [maxW]; a word wider than a line stays whole. */
    fun wrap(m: KitMeasure, size: Float, maxW: Int, bold: Boolean = false): List<Rich> {
        val words = ArrayList<Run>()
        for (r in runs) {
            var start = 0
            val t = r.text
            for (i in t.indices) {
                if (t[i] == ' ') {
                    words += Run(t.substring(start, i + 1), r.color, r.bold, 0, r.size)
                    start = i + 1
                }
            }
            if (start < t.length) words += Run(t.substring(start), r.color, r.bold, 0, r.size)
        }
        val lines = ArrayList<Rich>()
        var line = ArrayList<Run>()
        var w = 0
        for (word in words) {
            val ww = Run(word.text.trimEnd(), word.color, word.bold, 0, word.size).width(m, size, bold)
            if (line.isNotEmpty() && w + ww > maxW && line.last().text.endsWith(' ')) {
                lines += Rich(trimTail(line))
                line = ArrayList()
                w = 0
            }
            line += word
            w += word.width(m, size, bold)
        }
        if (line.isNotEmpty()) lines += Rich(trimTail(line))
        return lines.ifEmpty { listOf(this) }
    }

    private fun trimTail(line: List<Run>): List<Run> {
        val last = line.last()
        return line.dropLast(1) + Run(last.text.trimEnd(), last.color, last.bold, 0, last.size)
    }

    override fun toString(): String = plain

    companion object {
        const val ELLIPSIS = "…"
        val EMPTY = Rich(emptyList())

        /** How far below the center a glyph's baseline sits, per px of font size (lines up mixed sizes). */
        private const val BASELINE = 0.35f

        /** Minecraft's 16 chat colors, `§0`..`§f`. */
        val MC_COLORS: IntArray = intArrayOf(
            0xFF000000.toInt(), 0xFF0000AA.toInt(), 0xFF00AA00.toInt(), 0xFF00AAAA.toInt(),
            0xFFAA0000.toInt(), 0xFFAA00AA.toInt(), 0xFFFFAA00.toInt(), 0xFFAAAAAA.toInt(),
            0xFF555555.toInt(), 0xFF5555FF.toInt(), 0xFF55FF55.toInt(), 0xFF55FFFF.toInt(),
            0xFFFF5555.toInt(), 0xFFFF55FF.toInt(), 0xFFFFFF55.toInt(), 0xFFFFFFFF.toInt(),
        )

        /** The color of `§<code>`, or null for a format code or an unknown one. */
        fun mcColor(code: Char): Int? {
            val i = "0123456789abcdef".indexOf(code.lowercaseChar())
            return if (i < 0) null else MC_COLORS[i]
        }

        /** Parses `§` codes. Plain text becomes one run in the default color. */
        fun of(text: String): Rich {
            if (text.indexOf('§') < 0) return Rich(listOf(Run(text, null, false)))
            val out = ArrayList<Run>()
            var color: Int? = null
            var bold = false
            val buf = StringBuilder()
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '§' && i + 1 < text.length) {
                    if (buf.isNotEmpty()) {
                        out += Run(buf.toString(), color, bold)
                        buf.setLength(0)
                    }
                    val code = text[i + 1].lowercaseChar()
                    val c = mcColor(code)
                    if (c != null) {
                        color = c
                        bold = false
                    } else if (code == 'l') {
                        bold = true
                    } else if (code == 'r') {
                        color = null
                        bold = false
                    }
                    i += 2
                    continue
                }
                buf.append(ch)
                i++
            }
            if (buf.isNotEmpty()) out += Run(buf.toString(), color, bold)
            return Rich(out)
        }

        /** One run in an explicit color (a Soul color that has no `§` code). */
        fun colored(text: String, color: Int, bold: Boolean = false, minW: Int = 0): Rich = Rich(listOf(Run(text, color, bold, minW)))

        /** One run in its own [size] on the line's baseline. */
        fun sized(text: String, color: Int, size: Float, bold: Boolean = false): Rich = Rich(listOf(Run(text, color, bold, 0, size)))

        /** Runs side by side. */
        fun join(vararg parts: Rich): Rich = Rich(parts.flatMap { it.runs })
    }
}

/** `"text".rich` = [Rich.of]. */
val String.rich: Rich get() = Rich.of(this)
