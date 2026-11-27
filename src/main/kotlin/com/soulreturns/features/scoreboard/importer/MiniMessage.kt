package com.soulreturns.features.scoreboard.importer

import com.soulreturns.features.scoreboard.LegacyText

/**
 * Custom Scoreboard stores title/footer/prefix text as MiniMessage (`<gold><bold>SKYBLOCK</bold></gold>`);
 * this turns it into Soul's legacy codes (`§6§lSKYBLOCK`, `§#rrggbb` for hex, `§z` for rainbow/gradient).
 * Tags it does not know (click, hover, font...) are dropped.
 */
object MiniMessage {
    private val COLORS =
        mapOf(
            "black" to "§0", "dark_blue" to "§1", "dark_green" to "§2", "dark_aqua" to "§3", "dark_red" to "§4",
            "dark_purple" to "§5", "gold" to "§6", "gray" to "§7", "grey" to "§7", "dark_gray" to "§8", "dark_grey" to "§8",
            "blue" to "§9", "green" to "§a", "aqua" to "§b", "red" to "§c", "light_purple" to "§d", "yellow" to "§e", "white" to "§f",
        )
    private val FLAGS =
        mapOf(
            "b" to 'l', "bold" to 'l', "i" to 'o', "italic" to 'o', "em" to 'o', "u" to 'n', "underlined" to 'n',
            "st" to 'm', "strikethrough" to 'm', "obf" to 'k', "obfuscated" to 'k',
        )
    private val TAG = Regex("<(/?)([^<>]*)>")
    private val HEX = Regex("#[0-9a-fA-F]{6}")

    private class Layer(val name: String, val color: String?, val flag: Char?)

    fun toLegacy(text: String): String {
        val out = StringBuilder()
        val stack = ArrayList<Layer>()
        var emittedColor: String? = null
        var emittedFlags = emptySet<Char>()

        fun append(s: String) {
            if (s.isEmpty()) return
            val color = stack.lastOrNull { it.color != null }?.color
            val flags = stack.mapNotNull { it.flag }.toSet()
            if (!flags.containsAll(emittedFlags) || (color == null && emittedColor != null)) {
                out.append("§r")
                emittedColor = null
                emittedFlags = emptySet()
            }
            if (color != null && color != emittedColor) {
                out.append(color)
                emittedColor = color
                emittedFlags = emptySet()
            }
            for (f in flags - emittedFlags) out.append('§').append(f)
            emittedFlags = flags
            out.append(s)
        }

        var pos = 0
        for (m in TAG.findAll(text)) {
            append(text.substring(pos, m.range.first))
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val body = m.groupValues[2].trim()
            val name = body.substringBefore(':').lowercase()
            if (closing) {
                val i = stack.indexOfLast { it.name == name }
                if (i >= 0) stack.removeAt(i)
                continue
            }
            when {
                name == "reset" -> stack.clear()
                name == "newline" || name == "br" -> {}
                name in FLAGS -> stack.add(Layer(name, null, FLAGS[name]))
                name in COLORS -> stack.add(Layer(name, COLORS[name], null))
                HEX.matches(name) -> stack.add(Layer(name, "§$name", null))
                name == "color" || name == "c" -> {
                    val v = body.substringAfter(':', "").trim().lowercase()
                    val code = COLORS[v] ?: v.takeIf { HEX.matches(it) }?.let { "§$it" }
                    if (code != null) stack.add(Layer(name, code, null))
                }
                name == "rainbow" || name == "gradient" || name == "transition" -> stack.add(Layer(name, LegacyText.CHROMA, null))
                else -> {}
            }
        }
        append(text.substring(pos))
        return out.toString()
    }
}
