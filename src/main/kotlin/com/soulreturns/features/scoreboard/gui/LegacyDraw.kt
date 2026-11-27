package com.soulreturns.features.scoreboard.gui

import com.soulreturns.config.gui.render.Chroma
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.features.scoreboard.LegacyText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import kotlin.math.roundToInt

/**
 * Scoreboard text (legacy codes, `§#rrggbb`, `§z` chroma) in the setup screen's own lists: the menu font,
 * Hypixel's glyphs no MSDF family has through the Minecraft font in place, clipped to the screen's
 * scroll viewport and cut with an ellipsis at a width. The preview itself draws through the HUD.
 */
internal object LegacyDraw {
    private const val ELLIPSIS = "…"

    private class Piece(val text: String, val color: Int, val bold: Boolean, val msdf: Boolean)

    private fun pieces(text: String, defaultColor: Int, alpha: Int): List<Piece> {
        val out = ArrayList<Piece>()
        val regular = MsdfFonts.menuRegular
        val a = ((defaultColor ushr 24 and 0xFF) * alpha / 255).coerceIn(0, 255)
        for (run in LegacyText.runs(text)) {
            val color = if (run.chroma) Chroma.paint(a) else (a shl 24) or ((run.rgb ?: defaultColor) and 0xFFFFFF).let { Chroma.plain(it) }
            var start = 0
            var covered = true
            var i = 0
            val t = run.text
            while (i < t.length) {
                val cp = t.codePointAt(i)
                val c = !regular.loaded || regular.covers(cp)
                if (i == 0) {
                    covered = c
                } else if (c != covered) {
                    out += Piece(t.substring(start, i), color, run.bold, covered)
                    start = i
                    covered = c
                }
                i += Character.charCount(cp)
            }
            if (start < t.length) out += Piece(t.substring(start), color, run.bold, covered)
        }
        return out
    }

    private fun pieceWidth(p: Piece, text: String, size: Float): Float =
        if (p.msdf) {
            (if (p.bold) MsdfFonts.menuSemibold else MsdfFonts.menuRegular).widthF(text, size)
        } else {
            Minecraft.getInstance().font.width(Component.literal(text).withStyle(Style.EMPTY.withBold(p.bold))) * size / 8f
        }

    /** Advance width of [text] at [size]. */
    fun width(text: String, size: Float): Int = pieces(text, -1, 255).sumOf { pieceWidth(it, it.text, size).toDouble() }.roundToInt()

    /**
     * Draw [text] with its cap band centered on [cy]; colorless runs in [defaultColor], everything faded by
     * [alpha] (0..255). Stops at [maxW] with an ellipsis. Returns the width drawn.
     */
    fun draw(
        g: GuiGraphicsExtractor,
        text: String,
        x: Int,
        cy: Int,
        size: Float,
        defaultColor: Int,
        scissor: ScreenRectangle?,
        alpha: Int = 255,
        maxW: Int = Int.MAX_VALUE,
        shadow: Boolean = true,
    ): Int {
        val top = cy - (size * 7f / 16f).roundToInt()
        val all = pieces(text, defaultColor, alpha)
        val total = all.sumOf { pieceWidth(it, it.text, size).toDouble() }.toFloat()
        val ellipsisW = if (total > maxW) MsdfFonts.menuRegular.widthF(ELLIPSIS, size) else 0f
        val limit = if (total > maxW) maxW - ellipsisW else Float.MAX_VALUE
        var pen = x.toFloat()
        var lastColor = defaultColor
        for (p in all) {
            var t = p.text
            var w = pieceWidth(p, t, size)
            var cut = false
            if (pen - x + w > limit) {
                while (t.isNotEmpty() && pen - x + pieceWidth(p, t, size) > limit) t = t.substring(0, t.offsetByCodePoints(t.length, -1))
                w = pieceWidth(p, t, size)
                cut = true
            }
            drawPiece(g, p, t, pen.roundToInt(), top, size, scissor, shadow)
            pen += w
            lastColor = p.color
            if (cut) {
                SoulText.draw(g, ELLIPSIS, pen.roundToInt(), top, size, lastColor, MsdfFonts.menuRegular, forceCustom = true, shadow = shadow, scissor = scissor)
                pen += ellipsisW
                break
            }
        }
        return (pen - x).roundToInt()
    }

    private fun drawPiece(g: GuiGraphicsExtractor, p: Piece, text: String, x: Int, top: Int, size: Float, scissor: ScreenRectangle?, shadow: Boolean) {
        if (text.isEmpty()) return
        if (p.msdf) {
            SoulText.draw(g, text, x, top, size, p.color, if (p.bold) MsdfFonts.menuSemibold else MsdfFonts.menuRegular, forceCustom = true, shadow = shadow, scissor = scissor)
            return
        }
        val scale = size / 8f
        g.pose().pushMatrix()
        g.pose().translate(x.toFloat(), top.toFloat())
        g.pose().scale(scale, scale)
        SoulText.mcText(g, Minecraft.getInstance().font, text, p.bold, 0, 0, p.color, shadow)
        g.pose().popMatrix()
    }
}
