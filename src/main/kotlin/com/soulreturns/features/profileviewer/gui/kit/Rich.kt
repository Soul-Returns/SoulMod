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
    /** [color] null = the drawing node's default color. */
    class Run(val text: String, val color: Int?, val bold: Boolean, val minW: Int = 0)

    val isEmpty: Boolean get() = runs.all { it.text.isEmpty() }

    /** The text without codes. */
    val plain: String get() = runs.joinToString("") { it.text }

    fun width(m: KitMeasure, size: Float, bold: Boolean = false): Int =
        runs.sumOf { max(m.width(it.text, size, bold || it.bold), it.minW) }

    /** Draws on one line, vertically centered between [top] and [bottom]; returns the end x. */
    fun draw(c: KitCanvas, x: Int, top: Int, bottom: Int, size: Float, color: Int, bold: Boolean = false): Int {
        var cx = x
        for (r in runs) {
            if (r.text.isEmpty()) continue
            c.text(r.text, cx, top, bottom, r.color ?: color, size, bold || r.bold)
            cx += max(c.width(r.text, size, bold || r.bold), r.minW)
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
            val w = m.width(r.text, size, bold || r.bold)
            if (used + w + ell <= maxW) {
                out += r
                used += w
                continue
            }
            var end = r.text.length
            while (end > 0 && used + m.width(r.text.substring(0, end), size, bold || r.bold) + ell > maxW) end--
            out += Run(r.text.substring(0, end).trimEnd() + ELLIPSIS, r.color, r.bold)
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
                    words += Run(t.substring(start, i + 1), r.color, r.bold)
                    start = i + 1
                }
            }
            if (start < t.length) words += Run(t.substring(start), r.color, r.bold)
        }
        val lines = ArrayList<Rich>()
        var line = ArrayList<Run>()
        var w = 0
        for (word in words) {
            val ww = m.width(word.text.trimEnd(), size, bold || word.bold)
            if (line.isNotEmpty() && w + ww > maxW && line.last().text.endsWith(' ')) {
                lines += Rich(trimTail(line))
                line = ArrayList()
                w = 0
            }
            line += word
            w += m.width(word.text, size, bold || word.bold)
        }
        if (line.isNotEmpty()) lines += Rich(trimTail(line))
        return lines.ifEmpty { listOf(this) }
    }

    private fun trimTail(line: List<Run>): List<Run> {
        val last = line.last()
        return line.dropLast(1) + Run(last.text.trimEnd(), last.color, last.bold)
    }

    override fun toString(): String = plain

    companion object {
        const val ELLIPSIS = "…"
        val EMPTY = Rich(emptyList())

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

        /** Runs side by side. */
        fun join(vararg parts: Rich): Rich = Rich(parts.flatMap { it.runs })
    }
}

/** `"text".rich` = [Rich.of]. */
val String.rich: Rich get() = Rich.of(this)
