package com.soulreturns.features.updater

import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.HudElement
import com.soulreturns.ui.screen.SoulScreenBase
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f

/**
 * The updater's toast (spec `mocks/updater/`, `.uc-toast`): one card in the top-right corner for 8 s,
 * sliding in from the right, blue (update), green (updated) or red (withdrawn, failed). Hidden while a
 * Soul screen is open: the menu shows the same state itself. Not movable; it is a one-off notice.
 */
object UpdateToastHud : HudElement("update_toast") {
    const val UP = 0
    const val OK = 1
    const val BAD = 2

    private const val SHOW_MS = 8_000L
    private const val SLIDE_MS = 250L
    private const val W = 214
    private const val MARGIN = 8
    private const val PAD_L = 7
    private const val PAD_R = 9
    private const val PAD_Y = 7
    private const val ICON = 16
    private const val LINE = 13

    private val F_TITLE = 10.5f * MsdfFont.LEGACY_EM
    private val F_TEXT = 10f * MsdfFont.LEGACY_EM
    private val GREEN = 0xFF4ADE80.toInt()
    private val RED = 0xFFE05252.toInt()

    private class Toast(val kind: Int, val title: String, val text: String, val at: Long)

    @Volatile
    private var toast: Toast? = null

    private val icons = HashMap<String, Identifier>()

    fun show(kind: Int, title: String, text: String) {
        toast = Toast(kind, title, text, System.currentTimeMillis())
    }

    override fun isEnabled(): Boolean {
        val t = toast ?: return false
        if (System.currentTimeMillis() - t.at > SHOW_MS + SLIDE_MS) {
            toast = null
            return false
        }
        return Minecraft.getInstance().gui.screen() !is SoulScreenBase
    }

    override fun extract(g: GuiGraphicsExtractor) {
        val t = toast ?: return
        val mc = Minecraft.getInstance()
        val uiW = (mc.window.guiScaledWidth / SoulUi.factor()).toInt()
        val age = System.currentTimeMillis() - t.at
        // Ease-out slide in, slide back out at the end.
        val inFrac = (age.toFloat() / SLIDE_MS).coerceIn(0f, 1f)
        val outFrac = ((age - SHOW_MS).toFloat() / SLIDE_MS).coerceIn(0f, 1f)
        val shown = (1f - (1f - inFrac) * (1f - inFrac)) * (1f - outFrac)
        val x0 = uiW - MARGIN - W + ((1f - shown) * (W + MARGIN + 26)).toInt()
        val y0 = MARGIN

        val textX = x0 + PAD_L + ICON + 7
        val maxW = x0 + W - PAD_R - textX
        val lines = wrap(t.text, maxW)
        val h = PAD_Y * 2 + LINE + lines.size * LINE
        val stripe = if (t.kind == OK) GREEN else if (t.kind == BAD) RED else Theme.ACCENT

        rect(g, x0, y0, x0 + W, y0 + h, 0x90000000.toInt())
        SoulPanel.slice(g, x0, y0, x0 + W, y0 + h, SoulPanel.SMALL, x0, y0, x0 + 2, y0 + h, stripe)
        val icon = if (t.kind == OK) "ok_circle" else if (t.kind == BAD) "warn" else "flame"
        val iconColor = if (t.kind == OK) GREEN else if (t.kind == BAD) 0xFFFF6B6B.toInt() else -1
        val id = icons.getOrPut(icon) { Identifier.fromNamespaceAndPath("soul", "textures/gui/icons/$icon.png") }
        g.blit(RenderPipelines.GUI_TEXTURED, id, x0 + PAD_L, y0 + PAD_Y + 1, 0f, 0f, ICON, ICON, 64, 64, 64, 64, iconColor)

        SoulText.drawVCentered(g, t.title, textX, y0 + PAD_Y + LINE / 2, F_TITLE, 0xFFFFFFFF.toInt(), MsdfFonts.menuSemibold, forceCustom = true)
        var y = y0 + PAD_Y + LINE
        for (line in lines) {
            SoulText.drawVCentered(g, line, textX, y + LINE / 2, F_TEXT, 0xFFC8C8C8.toInt(), MsdfFonts.menuRegular, forceCustom = true)
            y += LINE
        }
    }

    private fun wrap(text: String, maxW: Int): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && SoulText.width(candidate, F_TEXT, MsdfFonts.menuRegular, forceCustom = true) > maxW) {
                out += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    private fun rect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(SoulRenderPipelines.ROUND_SMALL, TextureSetup.noTexture(), Matrix3x2f(g.pose()), x0, y0, x1, y1, color, null),
        )
    }
}
