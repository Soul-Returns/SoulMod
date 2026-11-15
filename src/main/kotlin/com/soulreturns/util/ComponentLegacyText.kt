package com.soulreturns.util

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import java.util.Optional

/**
 * Convert a Minecraft [Component] tree into a legacy `§`-prefixed string that preserves
 * color + style formatting (analogous to what the chat log uses internally).
 *
 * Mojang `Component.getString()` strips all styling — useful for plain text comparisons,
 * but throws away the colour cues that distinguish e.g. an EPIC pet from a LEGENDARY one
 * in Hypixel SkyBlock loot stand names (`§5Ender Dragon` vs `§6Ender Dragon`). This
 * helper walks the tree via `Component.visit` and emits the corresponding `§<char>`
 * prefix for each segment's named colour + style flags.
 *
 * **Limitations:**
 * - Non-named colors (`#RRGGBB` hex via [net.minecraft.network.chat.TextColor]) have no
 *   legacy code; they're silently dropped. Hypixel SkyBlock only uses the 16 named
 *   vanilla colors, so this is fine in practice.
 * - We emit a fresh code prefix for every visited segment without de-duplicating against
 *   the previously-emitted style — the resulting string can have redundant codes
 *   (`§5§5Text`) but is still valid for any consumer that parses `§<char>` sequences.
 */

// 26.2's ChatFormatting no longer exposes getByName()/getChar()/isColor(), so map the 16
// named colours (TextColor.serialize() output) to their legacy codes directly.
private val LEGACY_COLOR_CODES: Map<String, Char> =
    mapOf(
        "black" to '0',
        "dark_blue" to '1',
        "dark_green" to '2',
        "dark_aqua" to '3',
        "dark_red" to '4',
        "dark_purple" to '5',
        "gold" to '6',
        "gray" to '7',
        "dark_gray" to '8',
        "blue" to '9',
        "green" to 'a',
        "aqua" to 'b',
        "red" to 'c',
        "light_purple" to 'd',
        "yellow" to 'e',
        "white" to 'f',
    )

fun Component.toLegacyText(): String {
    val sb = StringBuilder()
    this.visit(
        { style: Style, content: String ->
            style.color?.let { color ->
                LEGACY_COLOR_CODES[color.serialize()]?.let { sb.append('§').append(it) }
            }
            if (style.isBold) sb.append("§l")
            if (style.isItalic) sb.append("§o")
            if (style.isUnderlined) sb.append("§n")
            if (style.isStrikethrough) sb.append("§m")
            if (style.isObfuscated) sb.append("§k")
            sb.append(content)
            Optional.empty<Unit>()
        },
        Style.EMPTY,
    )
    return sb.toString()
}
