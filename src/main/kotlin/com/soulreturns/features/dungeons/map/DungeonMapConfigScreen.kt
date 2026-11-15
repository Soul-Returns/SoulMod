package com.soulreturns.features.dungeons.map

import com.soulreturns.config.gui.ConfigScreenBase
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.model.ConfigCategory
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.ui.SoulUi
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dedicated dungeon-map settings screen: a config container split **40% live preview / 60%
 * settings**, opened from the "Map settings…" row in the main config or via `/soul map`.
 *
 * The left 40% renders a synthetic [DungeonMapPreview] scene (transparent background — no sub-panel)
 * that updates live as options on the right are edited, so color/opacity/marker changes are visible
 * immediately. The right 60% is a category-tabbed, scrollable list of the map options (Map, Markers,
 * Colors, Sync); widgets + input come from [ConfigScreenBase].
 */
class DungeonMapConfigScreen(
    private val parent: Screen? = null,
) : ConfigScreenBase(Component.literal("Dungeon Map Settings")) {
    private companion object {
        const val TITLE_H = 34
        const val TABS_H = 28
        const val FOOTER_H = 40
        const val ROW_H = 32
        const val SEC_GAP = 12
        const val SCROLL_STEP = 18
        const val PREVIEW_PAD = 14
    }

    private var activeCat = 0
    private var bodyScroll = 0f
    private var bodyContentH = 0
    private var bodyRect = intArrayOf(0, 0, 0, 0)

    private var scene: DungeonMapHud.MapScene? = null

    override fun init() {
        if (scene == null) scene = DungeonMapPreview.build(minecraft?.player?.gameProfile?.name)
    }

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        regions.clear()

        val s = SoulUi.factor()
        val uiW = (width / s).toInt()
        val uiH = (height / s).toInt()
        val mx = (mouseX / s).toInt()
        val my = (mouseY / s).toInt()

        g.pose().pushMatrix()
        g.pose().scale(s, s)

        // Same margins as the main config: 90% × 88% centered.
        val cardW = (uiW * 0.9f).toInt().coerceAtMost(1100).coerceAtMost(uiW - 8)
        val cardH = (uiH * 0.88f).toInt().coerceAtMost(760).coerceAtMost(uiH - 8)
        val cardX0 = (uiW - cardW) / 2
        val cardY0 = (uiH - cardH) / 2
        val cardX1 = cardX0 + cardW
        val cardY1 = cardY0 + cardH
        val splitX = cardX0 + (cardW * 0.4f).roundToInt()

        g.fill(0, 0, uiW, uiH, Theme.DIM)

        // Right 60% is the settings panel; the left 40% is left transparent for the preview.
        roundRect(g, splitX, cardY0, cardX1, cardY1, Theme.PANEL, SoulRenderPipelines.ROUND_MEDIUM)
        g.fill(splitX, cardY0, splitX + 1, cardY1, Theme.SEPARATOR)

        drawPreview(g, cardX0, cardY0, splitX, cardY1)

        val cats = DungeonMapConfigModel.categories
        if (activeCat >= cats.size) activeCat = 0

        // Title bar.
        drawTextV(g, "Dungeon Map", splitX + 16, cardY0, cardY0 + TITLE_H, Theme.TEXT, fHeading, MsdfFonts.menuSemibold)
        g.fill(splitX + 1, cardY0 + TITLE_H, cardX1, cardY0 + TITLE_H + 1, Theme.SEPARATOR)

        drawTabs(g, mx, my, splitX, cardY0 + TITLE_H, cardX1, cats)

        val bodyY0 = cardY0 + TITLE_H + TABS_H
        val footerY0 = cardY1 - FOOTER_H
        drawBody(g, mx, my, splitX, bodyY0, cardX1, footerY0, cats.getOrNull(activeCat))

        // Footer.
        g.fill(splitX + 1, footerY0, cardX1, footerY0 + 1, Theme.SEPARATOR)
        val btnY0 = footerY0 + 5
        val btnY1 = cardY1 - 5
        val doneX0 = cardX1 - 8 - 56
        drawButton(g, mx, my, doneX0, btnY0, cardX1 - 8, btnY1, "Done") {
            commit()
            applyPending()
            onClose()
        }
        if (hasPending()) {
            val applyX1 = doneX0 - 8
            drawButton(g, mx, my, applyX1 - 56, btnY0, applyX1, btnY1, "Apply", accent = true) {
                commit()
                applyPending()
            }
        }

        // Overlays (drawn last so they sit on top of everything).
        drawColorPicker(g, uiW, uiH, mx, my)
        drawDropdown(g, uiW, uiH, mx, my)

        g.pose().popMatrix()
    }

    private fun drawPreview(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        val sc = scene ?: return
        val availW = (x1 - x0) - 2 * PREVIEW_PAD
        val availH = (y1 - y0) - 2 * PREVIEW_PAD
        if (availW <= 0 || availH <= 0) return
        val scale = min(availW.toFloat() / DungeonMapHud.SIZE, availH.toFloat() / DungeonMapHud.SIZE)
        val drawn = DungeonMapHud.SIZE * scale
        val px = x0 + ((x1 - x0) - drawn) / 2f
        val py = y0 + ((y1 - y0) - drawn) / 2f
        // Clip to the preview column so labels/markers near the edge don't bleed into the panel.
        g.enableScissor(x0, y0, x1, y1)
        DungeonMapHud.renderPreview(g, sc, px, py, scale)
        g.disableScissor()
    }

    private fun drawTabs(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        cats: List<ConfigCategory>,
    ) {
        var tx = x0 + 12
        val tabY0 = y0 + 4
        val tabY1 = y0 + TABS_H - 4
        for ((i, c) in cats.withIndex()) {
            val tw = textWidth(c.title, fBody) + 20
            val active = i == activeCat
            val hovered = mx in tx..(tx + tw) && my in tabY0..tabY1
            if (active) {
                roundRect(g, tx, tabY0, tx + tw, tabY1, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
            } else if (hovered) {
                roundRect(g, tx, tabY0, tx + tw, tabY1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
            }
            val col = if (active) 0xFFFFFFFF.toInt() else Theme.TEXT_DIM
            drawTextV(g, c.title, tx + 10, tabY0, tabY1, col, fBody, MsdfFonts.menuSemibold)
            val idx = i
            region(tx, tabY0, tx + tw, tabY1) {
                activeCat = idx
                bodyScroll = 0f
            }
            tx += tw + 6
        }
    }

    private fun drawBody(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        cat: ConfigCategory?,
    ) {
        bodyRect = intArrayOf(x0, y0, x1, y1)
        beginClip(g, x0, y0, x1, y1)
        val secX0 = x0 + 16
        val secX1 = x1 - 18
        var y = y0 + 12 - bodyScroll.roundToInt()
        val sections = cat?.subcategories?.firstOrNull()?.sections.orEmpty()
        for (sec in sections) {
            val opts = sec.options.filter { it.visible() }
            if (opts.isEmpty()) continue
            val n = opts.size
            val labelBlockH = if (sec.subtitle != null) 26 else 18
            val cardTop = y + labelBlockH
            val rowsH = opts.sumOf { optionRowHeight(it, secX0 + 8, secX1 - 8, ROW_H) } + (n - 1).coerceAtLeast(0) * 2
            val cardH = rowsH + 16
            val secBottom = cardTop + cardH
            if (secBottom > y0 && y < y1) {
                drawText(g, sec.title, secX0 + 4, y + 2, Theme.TEXT_DIM, fBody)
                sec.subtitle?.let { drawText(g, it, secX0 + 4, y + 14, Theme.TEXT_FAINT, fCaption) }
                roundRect(g, secX0, cardTop, secX1, secBottom, Theme.SECTION, SoulRenderPipelines.ROUND_MEDIUM)
                var rowY = cardTop + 8
                for (opt in opts) {
                    val h = optionRowHeight(opt, secX0 + 8, secX1 - 8, ROW_H)
                    drawOptionRow(g, mx, my, secX0 + 8, rowY, secX1 - 8, rowY + h, opt, y0, y1)
                    rowY += h + 2
                }
            }
            y += labelBlockH + cardH + SEC_GAP
        }
        endClip(g)

        bodyContentH = (y + bodyScroll.roundToInt()) - (y0 + 12) + 16
        bodyScroll = clampScroll(bodyScroll, bodyContentH, y1 - y0)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val s = SoulUi.factor()
        val mx = (mouseX / s).roundToInt()
        val my = (mouseY / s).roundToInt()
        if (within(bodyRect, mx, my)) {
            bodyScroll = clampScroll(bodyScroll - scrollY.toFloat() * SCROLL_STEP, bodyContentH, bodyRect[3] - bodyRect[1])
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun onClose() {
        commit()
        val target = parent
        if (target != null) minecraft?.setScreenAndShow(target) else super.onClose()
    }
}
