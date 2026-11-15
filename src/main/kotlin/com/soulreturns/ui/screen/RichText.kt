package com.soulreturns.ui.screen

/**
 * Inline markup for short Soul texts (release notes, update notices): `**bold**` and `` `code` ``;
 * an unclosed marker stays literal. Drawn by [SoulScreenBase.drawRich]. Pure, so it is testable.
 */
object RichText {
    const val PLAIN = 0
    const val BOLD = 1
    const val CODE = 2

    /** A run of text in one style ([PLAIN], [BOLD], [CODE]). */
    data class Span(val text: String, val style: Int)

    fun parse(text: String): List<Span> {
        val out = ArrayList<Span>()
        val plain = StringBuilder()
        var i = 0
        fun flush() {
            if (plain.isNotEmpty()) out += Span(plain.toString(), PLAIN)
            plain.clear()
        }
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    out += Span(text.substring(i + 2, end), BOLD)
                    i = end + 2
                    continue
                }
            } else if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    out += Span(text.substring(i + 1, end), CODE)
                    i = end + 1
                    continue
                }
            }
            plain.append(text[i])
            i++
        }
        flush()
        return out
    }
}
