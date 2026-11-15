package com.soulreturns.features.sacks

import com.soulreturns.util.MessageDetector
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import java.util.Locale
import java.util.Optional

/**
 * Pure text parsing for sack menus and the `[Sacks]` chat summary (formats from the 2026-11-07 live
 * captures). Everything is matched on color-stripped text: Hypixel sends styled components, the test
 * server sends literal `§` codes, and some names carry a literal odd-case code (`Common Frog §LBRONZE`).
 */
object SackParsing {
    /** `Stored: 4,494/22.2k`, `Stored: 0/22.2k`; the capacity is compacted and ignored. */
    private val STORED = Regex("""^Stored: ([\d,]+)/\S+$""")

    /** `[Sacks] +270 items, -10 items. (Last 30s.)` or `[Sacks] +32 items. (Last 5s.)`. */
    private val SUMMARY = Regex("""^\[Sacks] .*\(Last (\d+)s\.\)$""")

    /** A hover line, `+158 Lily Pad (Fishing Sack, Fishing Sack)`; the name is everything up to the last parenthesis. */
    private val HOVER_LINE = Regex("""^([+-][\d,]+) (.+) \(([^()]*)\)$""")

    private val WHITESPACE = Regex("""\s+""")

    /** One item of the summary's hover breakdown. */
    data class SummaryLine(val name: String, val delta: Long)

    fun strip(text: String): String = MessageDetector.stripColorCodes(text)

    /** The exact count from a sack item's lore lines, or null when no line is a `Stored:` line. */
    fun storedCount(lore: List<Component>): Long? {
        for (line in lore) {
            val match = STORED.find(strip(line.string).trim()) ?: continue
            return match.groupValues[1].replace(",", "").toLongOrNull()
        }
        return null
    }

    /** Key for name lookups: colors stripped, lower case, single spaces. Menu and chat names meet here. */
    fun normalizeName(name: String): String = strip(name).trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    /** The summary window in seconds when [plain] (color-stripped chat) is a `[Sacks]` summary, else null. */
    fun summaryWindow(plain: String): Int? = SUMMARY.matchEntire(plain.trim())?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Every distinct hover text in [component] as plain text. The breakdown is only in the hover of
     * the `+N items` / `-N items` parts; each part spans several styled segments, hence the dedup.
     */
    fun hoverTexts(component: Component): List<String> {
        val texts = LinkedHashSet<String>()
        component.visit(
            FormattedText.StyledContentConsumer<Unit> { style, _ ->
                val hover = style.hoverEvent
                if (hover is HoverEvent.ShowText) texts += hover.value().string
                Optional.empty()
            },
            Style.EMPTY,
        )
        return texts.toList()
    }

    /** The item lines of the hover texts; headers, blanks and the "can be disabled" footer do not match. */
    fun summaryLines(hovers: List<String>): List<SummaryLine> {
        val out = ArrayList<SummaryLine>()
        for (hover in hovers) {
            for (raw in hover.split('\n')) {
                val match = HOVER_LINE.matchEntire(strip(raw).trim()) ?: continue
                val delta = match.groupValues[1].replace(",", "").toLongOrNull() ?: continue
                if (delta == 0L) continue
                out += SummaryLine(match.groupValues[2].trim(), delta)
            }
        }
        return out
    }
}
