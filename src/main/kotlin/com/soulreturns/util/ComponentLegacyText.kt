package com.soulreturns.util

import net.minecraft.ChatFormatting
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
 * helper walks the tree via `Component.visit`, looks up the named-colour [ChatFormatting]
 * for each segment's [Style.getColor], and emits the corresponding `§<char>` prefix.
 *
 * **Limitations:**
 * - Non-named colors (`#RRGGBB` hex via [net.minecraft.network.chat.TextColor]) have no
 *   legacy code; they're silently dropped. Hypixel SkyBlock only uses the 16 named
 *   vanilla colors, so this is fine in practice.
 * - We emit a fresh code prefix for every visited segment without de-duplicating against
 *   the previously-emitted style — the resulting string can have redundant codes
 *   (`§5§5Text`) but is still valid for any consumer that parses `§<char>` sequences.
 */
fun Component.toLegacyText(): String {
    val sb = StringBuilder()
    this.visit(
        { style: Style, content: String ->
            style.color?.let { color ->
                val name = color.serialize()
                val cf = ChatFormatting.getByName(name)
                if (cf != null && cf.isColor) sb.append('§').append(cf.char)
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
