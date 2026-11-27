package com.soulreturns.config.gui.render

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import java.util.Optional
import kotlin.math.roundToInt

/**
 * Draws a styled Minecraft [Component] (colors, bold, underline, strikethrough, legacy `§` codes
 * inside the text) with Soul's MSDF fonts, laid out like vanilla text: same top-left origin,
 * baseline and nominal size as [SoulText].
 *
 * **Codepoints no MSDF family has are drawn with the Minecraft font**, in place and on the same
 * baseline: Hypixel's stat icons (`❤ ❈ ✎ ⸕ ☠ …`) and resource-pack glyphs would otherwise draw
 * nothing at all. With the Minecraft font chosen the whole component goes through vanilla.
 *
 * Build [Span]s once per changed component ([spans]), then measure and draw them per frame.
 */
object SoulRichText {
    private const val LEGACY_COLORS = "0123456789abcdef"

    /** Vanilla's RGB for the sixteen legacy color codes, in [LEGACY_COLORS] order. */
    private val LEGACY_RGB =
        intArrayOf(
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
        )

    /** A run of text with one style. [color] is RGB, or null for the caller's default color. */
    class Span(val text: String, val color: Int?, val bold: Boolean, val underline: Boolean, val strikethrough: Boolean)

    /** Flatten [component] into styled runs, applying legacy `§` codes found inside its text. */
    fun spans(component: Component): List<Span> {
        val out = ArrayList<Span>()
        component.visit(
            FormattedText.StyledContentConsumer<Unit> { style, text ->
                legacy(style, text, out)
                Optional.empty()
            },
            Style.EMPTY,
        )
        return out
    }

    private fun legacy(base: Style, text: String, out: MutableList<Span>) {
        var color: Int? = base.color?.value
        var bold = base.isBold
        var underline = base.isUnderlined
        var strike = base.isStrikethrough
        val run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) out += Span(run.toString(), color, bold, underline, strike)
            run.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '§' && i + 1 < text.length) {
                flush()
                val code = text[i + 1].lowercaseChar()
                val legacyColor = LEGACY_COLORS.indexOf(code)
                if (code == 'r') {
                    color = base.color?.value
                    bold = base.isBold
                    underline = base.isUnderlined
                    strike = base.isStrikethrough
                } else if (legacyColor >= 0) {
                    // Like vanilla, a color code also clears the formats before it.
                    color = LEGACY_RGB[legacyColor]
                    bold = false
                    underline = false
                    strike = false
                } else if (code == 'l') {
                    bold = true
                } else if (code == 'n') {
                    underline = true
                } else if (code == 'm') {
                    strike = true
                }
                // Anything else (obfuscated, italic, Hypixel's placeholder codes) is skipped, like vanilla.
                i += 2
                continue
            }
            run.append(c)
            i++
        }
        flush()
    }

    private fun mcScale(size: Float) = size / 8f

    private fun useMinecraft(fontChoice: Int) = fontChoice == MsdfFonts.MINECRAFT

    /** Advance width of [spans] at nominal [size] with family [fontChoice]. */
    fun width(spans: List<Span>, size: Float, fontChoice: Int, boldFont: Boolean = false): Int {
        var w = 0f
        forEachRun(spans, fontChoice) { span, text, msdf ->
            w += runWidth(span, text, msdf, size, fontChoice, boldFont)
        }
        return w.roundToInt()
    }

    /**
     * Draw [spans] with the text's top-left at ([x], [y]). Span colors fall back to [defaultColor]
     * (ARGB); [alpha] (0..255) fades everything, like vanilla's action bar fade.
     */
    fun draw(
        g: GuiGraphicsExtractor,
        spans: List<Span>,
        x: Int,
        y: Int,
        size: Float,
        defaultColor: Int,
        alpha: Int,
        fontChoice: Int,
        shadow: Boolean,
        boldFont: Boolean = false,
    ) {
        val mc = Minecraft.getInstance()
        var pen = x.toFloat()
        forEachRun(spans, fontChoice) { span, text, msdf ->
            val rgb = span.color ?: (defaultColor and 0xFFFFFF)
            val a = ((defaultColor ushr 24 and 0xFF) * alpha / 255).coerceIn(0, 255)
            val color = (a shl 24) or (rgb and 0xFFFFFF)
            val px = pen.roundToInt()
            val w = runWidth(span, text, msdf, size, fontChoice, boldFont)
            if (msdf) {
                val font = if (span.bold || boldFont) MsdfFonts.semiboldFor(fontChoice) else MsdfFonts.regularFor(fontChoice)
                SoulText.draw(g, text, px, y, size, color, font, forceCustom = true, shadow = shadow)
            } else {
                val scale = mcScale(size)
                g.pose().pushMatrix()
                g.pose().translate(px.toFloat(), y.toFloat())
                g.pose().scale(scale, scale)
                SoulText.mcText(g, mc.font, text, span.bold || boldFont, 0, 0, color, shadow)
                g.pose().popMatrix()
            }
            val thickness = mcScale(size).roundToInt().coerceAtLeast(1)
            if (span.underline) Chroma.fill(g, px, y + (size * 9f / 8f).roundToInt() - thickness, px + w.roundToInt(), y + (size * 9f / 8f).roundToInt(), color)
            if (span.strikethrough) Chroma.fill(g, px, y + (size * 4f / 8f).roundToInt(), px + w.roundToInt(), y + (size * 4f / 8f).roundToInt() + thickness, color)
            pen += w
        }
    }

    private fun styled(span: Span, text: String, boldFont: Boolean): Component =
        Component.literal(text).withStyle(Style.EMPTY.withBold(span.bold || boldFont))

    private fun runWidth(span: Span, text: String, msdf: Boolean, size: Float, fontChoice: Int, boldFont: Boolean): Float =
        if (msdf) {
            val font = if (span.bold || boldFont) MsdfFonts.semiboldFor(fontChoice) else MsdfFonts.regularFor(fontChoice)
            font.widthF(text, size)
        } else {
            Minecraft.getInstance().font.width(styled(span, text, boldFont)) * mcScale(size)
        }

    /**
     * Split each span into runs the MSDF family can draw (`msdf = true`) and runs it cannot, which go
     * through the Minecraft font. Under the Minecraft font choice every run is vanilla.
     */
    private inline fun forEachRun(spans: List<Span>, fontChoice: Int, action: (Span, String, Boolean) -> Unit) {
        val vanillaOnly = useMinecraft(fontChoice)
        val regular = MsdfFonts.regularFor(fontChoice)
        for (span in spans) {
            if (vanillaOnly || !regular.loaded) {
                action(span, span.text, false)
                continue
            }
            val text = span.text
            var start = 0
            var startCovered = true
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val covered = regular.covers(cp)
                if (i == 0) {
                    startCovered = covered
                } else if (covered != startCovered) {
                    action(span, text.substring(start, i), startCovered)
                    start = i
                    startCovered = covered
                }
                i += Character.charCount(cp)
            }
            if (start < text.length) action(span, text.substring(start), startCovered)
        }
    }
}
