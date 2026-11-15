package com.soulreturns.features.inventorybuttons

import com.soulreturns.config.InventoryButton
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.SoulUi
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f

/**
 * The notice and the tray for buttons whose spot does not exist at the current GUI scale. Shared by
 * the live overlay (over the player inventory) and the editor, drawn on the Soul render layer in
 * Soul-logical coordinates (the host pushes [SoulUi.factor]).
 *
 * Closed, it is a small notice ("N inventory buttons hidden at GUI scale K" + Move…). Move… opens
 * the tray at its default spot (top center of the host's band): a top bar with the title, a 6-dot
 * grip (2 rows × 3) that drags the tray for this session only, and a close button; the hidden
 * buttons below in rows of nine, centered; the instruction under them. Pressing a button starts a
 * drag ([dragged]); the host highlights free spots, draws the button under the cursor and, on
 * [release], moves it to the free spot it was dropped on. Nothing is placed automatically.
 */
class HiddenButtonsTray {
    /** A hidden button and its index in the host's button list. */
    class Entry(val index: Int, val button: InventoryButton)

    private companion object {
        const val CELL = 20
        const val CELL_GAP = 4
        const val PER_ROW = 9
        const val PAD = 8
        const val BAR_H = 14
        const val GRID_W = PER_ROW * CELL + (PER_ROW - 1) * CELL_GAP
        const val WIDTH = GRID_W + 2 * PAD
        const val NOTICE_H = 22

        val BG = 0xF2181A1F.toInt()
        val BORDER = 0xFF353A44.toInt()
        val AMBER = 0xFFE0A43A.toInt()
        val AMBER_TEXT = 0xFFFFCC66.toInt()
        val TITLE = 0xFFD8DBE2.toInt()
        val MUTED = 0xFF9AA1AD.toInt()
        val GRIP = 0xFF6C7380.toInt()
        val CELL_BG = 0xFF262A31.toInt()
        val CELL_BORDER = 0xFF4A90FF.toInt()
        val CELL_BORDER_HOVER = 0xFF8AB8FF.toInt()
        val CELL_DIM = 0xFF3A3F49.toInt()
        val SIZE_CAPTION = 10f * MsdfFont.LEGACY_EM
        val SIZE_BODY = 11f * MsdfFont.LEGACY_EM
    }

    var open: Boolean = false
        private set

    /** Index (in the host's button list) of the button being dragged out of the tray, or -1. */
    var dragged: Int = -1
        private set

    /** True while the tray takes the mouse: a button or the tray itself is being dragged. */
    val busy: Boolean get() = dragged >= 0 || gripping

    // Tray top-left; NaN = the default spot. Reset every time it opens.
    private var trayX = Float.NaN
    private var trayY = Float.NaN
    private var gripping = false
    private var grabX = 0f
    private var grabY = 0f

    // Hit rects of the last frame (x0, y0, x1, y1); empty when not drawn.
    private val none = IntArray(0)
    private var noticeRect = none
    private var moveRect = none
    private var trayRect = none
    private var gripRect = none
    private var closeRect = none
    private val cellRects = ArrayList<IntArray>()
    private val cellIndices = ArrayList<Int>()
    private val cellRectNone = IntArray(4)

    /** Closed, at the default spot, nothing held. */
    fun reset() {
        open = false
        dragged = -1
        gripping = false
        trayX = Float.NaN
        trayY = Float.NaN
        clearRects()
    }

    /** Open at the default spot (the editor opens it right away when buttons are hidden). */
    fun show() {
        open = true
        trayX = Float.NaN
        trayY = Float.NaN
    }

    /**
     * Draw the notice or the tray for [hidden] at GUI scale [guiScale], centered in the band
     * [x0]..[x1] from [top], kept above [bottom]. [mx]/[my] are the cursor in Soul-logical px.
     * [selected] (a host index) gets an accent ring. [tooltip] takes the hovered cell's tooltip
     * (entry + cell rect) for a host with its own tooltips; null shows the vanilla item tooltip.
     */
    fun draw(
        g: GuiGraphicsExtractor,
        hidden: List<Entry>,
        guiScale: Int,
        x0: Int,
        x1: Int,
        top: Int,
        bottom: Int,
        mx: Int,
        my: Int,
        selected: Int = -1,
        tooltip: ((Entry, IntArray) -> Unit)? = null,
    ) {
        clearRects()
        if (hidden.isEmpty()) {
            open = false
            dragged = -1
            gripping = false
            return
        }
        if (dragged >= 0 && hidden.none { it.index == dragged }) dragged = -1
        if (open) drawTray(g, hidden, x0, x1, top, bottom, mx, my, selected, tooltip) else drawNotice(g, hidden.size, guiScale, x0, x1, top, mx, my)
    }

    /** A press at ([mx], [my]) Soul px; true when the notice or the tray took it. */
    fun click(mx: Int, my: Int): Boolean {
        if (hit(moveRect, mx, my)) {
            show()
            return true
        }
        if (hit(noticeRect, mx, my)) return true
        if (!hit(trayRect, mx, my)) return false
        if (hit(closeRect, mx, my)) {
            open = false
            return true
        }
        if (hit(gripRect, mx, my)) {
            gripping = true
            grabX = mx - trayRect[0].toFloat()
            grabY = my - trayRect[1].toFloat()
            return true
        }
        for (i in cellRects.indices) {
            if (hit(cellRects[i], mx, my)) {
                dragged = cellIndices[i]
                return true
            }
        }
        return true
    }

    /** End any drag; returns the index of the button that was being dragged out, or -1. */
    fun release(): Int {
        gripping = false
        val index = dragged
        dragged = -1
        return index
    }

    private fun drawNotice(g: GuiGraphicsExtractor, count: Int, guiScale: Int, x0: Int, x1: Int, top: Int, mx: Int, my: Int) {
        val lead = "$count inventory button${if (count == 1) "" else "s"}"
        val rest = " hidden at GUI scale $guiScale"
        val semibold = MsdfFonts.menuSemibold
        val leadW = SoulText.width(lead, SIZE_BODY, semibold, forceCustom = true)
        val restW = SoulText.width(rest, SIZE_BODY, MsdfFonts.menuRegular, forceCustom = true)
        val moveLabel = "Move…"
        val moveW = SoulText.width(moveLabel, SIZE_BODY, MsdfFonts.menuRegular, forceCustom = true) + 14
        val w = 10 + leadW + restW + 8 + moveW + 4
        val left = (x0 + x1) / 2 - w / 2
        val y0 = top + 4
        val y1 = y0 + NOTICE_H
        rect(g, left, y0, left + w, y1, AMBER, SoulRenderPipelines.ROUND_MEDIUM)
        rect(g, left + 1, y0 + 1, left + w - 1, y1 - 1, BG, SoulRenderPipelines.ROUND_MEDIUM)
        val cy = (y0 + y1) / 2
        SoulText.drawVCentered(g, lead, left + 10, cy, SIZE_BODY, AMBER_TEXT, semibold, forceCustom = true)
        SoulText.drawVCentered(g, rest, left + 10 + leadW, cy, SIZE_BODY, Theme.TEXT, MsdfFonts.menuRegular, forceCustom = true)
        val bx = left + w - 4 - moveW
        val button = intArrayOf(bx, y0 + 3, bx + moveW, y1 - 3)
        rect(g, button[0], button[1], button[2], button[3], if (hit(button, mx, my)) Theme.ACCENT else Theme.ACCENT_DIM, SoulRenderPipelines.ROUND_SMALL)
        SoulText.drawCenteredOn(g, moveLabel, (button[0] + button[2]) / 2, cy, SIZE_BODY, Theme.TEXT, MsdfFonts.menuRegular, forceCustom = true)
        noticeRect = intArrayOf(left, y0, left + w, y1)
        moveRect = button
    }

    private fun drawTray(
        g: GuiGraphicsExtractor,
        hidden: List<Entry>,
        x0: Int,
        x1: Int,
        top: Int,
        bottom: Int,
        mx: Int,
        my: Int,
        selected: Int,
        tooltip: ((Entry, IntArray) -> Unit)?,
    ) {
        val rows = (hidden.size + PER_ROW - 1) / PER_ROW
        val gridH = rows * CELL + (rows - 1) * CELL_GAP
        val h = PAD + BAR_H + 6 + gridH + 6 + 10 + PAD
        if (trayX.isNaN()) {
            trayX = ((x0 + x1) / 2 - WIDTH / 2).toFloat()
            trayY = (top + 4).toFloat()
        }
        if (gripping) {
            trayX = mx - grabX
            trayY = my - grabY
        }
        trayX = trayX.coerceIn(x0.toFloat(), maxOf(x0, x1 - WIDTH).toFloat())
        trayY = trayY.coerceIn(top.toFloat(), maxOf(top, bottom - h).toFloat())
        val left = trayX.toInt()
        val y0 = trayY.toInt()
        rect(g, left, y0, left + WIDTH, y0 + h, BORDER, SoulRenderPipelines.ROUND_MEDIUM)
        rect(g, left + 1, y0 + 1, left + WIDTH - 1, y0 + h - 1, BG, SoulRenderPipelines.ROUND_MEDIUM)
        trayRect = intArrayOf(left, y0, left + WIDTH, y0 + h)

        // Top bar: title, grip, close.
        val barTop = y0 + PAD
        val barCy = barTop + BAR_H / 2
        SoulText.drawVCentered(g, "Hidden buttons", left + PAD, barCy, SIZE_CAPTION, TITLE, MsdfFonts.menuSemibold, forceCustom = true)
        val gcx = left + WIDTH / 2
        gripRect = intArrayOf(gcx - 9, barCy - 6, gcx + 9, barCy + 6)
        val gripColor = if (gripping || hit(gripRect, mx, my)) TITLE else GRIP
        for (row in 0 until 2) {
            for (col in 0 until 3) {
                val dx = gcx - 4 + col * 4
                val dy = barCy - 2 + row * 4
                rect(g, dx - 1, dy - 1, dx + 1, dy + 1, gripColor, SoulRenderPipelines.ROUND_PILL)
            }
        }
        val cx1 = left + WIDTH - PAD + 2
        closeRect = intArrayOf(cx1 - 14, barCy - 7, cx1, barCy + 7)
        val closeHovered = hit(closeRect, mx, my)
        if (closeHovered) rect(g, closeRect[0], closeRect[1], closeRect[2], closeRect[3], Theme.CONTROL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        SoulText.drawCenteredOn(g, "×", (closeRect[0] + closeRect[2]) / 2, barCy, SIZE_BODY, if (closeHovered) Theme.TEXT else MUTED, MsdfFonts.menuRegular, forceCustom = true, tight = true)

        // The buttons, nine per row, rows centered.
        val gridTop = barTop + BAR_H + 6
        var hovered: Entry? = null
        var hoveredRect = cellRectNone
        for ((i, entry) in hidden.withIndex()) {
            val row = i / PER_ROW
            val inRow = minOf(PER_ROW, hidden.size - row * PER_ROW)
            val rowW = inRow * CELL + (inRow - 1) * CELL_GAP
            val cx = left + PAD + (GRID_W - rowW) / 2 + (i % PER_ROW) * (CELL + CELL_GAP)
            val cy = gridTop + row * (CELL + CELL_GAP)
            val cell = intArrayOf(cx, cy, cx + CELL, cy + CELL)
            val isDragged = entry.index == dragged
            val over = !busy && hit(cell, mx, my)
            if (over) {
                hovered = entry
                hoveredRect = cell
            }
            if (entry.index == selected && !isDragged) rect(g, cx - 1, cy - 1, cx + CELL + 1, cy + CELL + 1, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
            val edge = if (isDragged) CELL_DIM else if (entry.index == selected) Theme.ACCENT else if (over) CELL_BORDER_HOVER else CELL_BORDER
            rect(g, cx, cy, cx + CELL, cy + CELL, edge, SoulRenderPipelines.ROUND_SMALL)
            rect(g, cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, CELL_BG, SoulRenderPipelines.ROUND_SMALL)
            if (!isDragged) g.fakeItem(InventoryButtonIcons.stack(entry.button), cx + 2, cy + 2)
            cellRects.add(cell)
            cellIndices.add(entry.index)
        }
        SoulText.drawCenteredOn(g, "Drag each onto a green spot", left + WIDTH / 2, gridTop + gridH + 6 + 5, SIZE_CAPTION, MUTED, MsdfFonts.menuRegular, forceCustom = true)

        if (hovered != null) {
            if (tooltip != null) {
                tooltip(hovered, hoveredRect)
            } else {
                val f = SoulUi.factor()
                val lines = InventoryButtonPainter.tooltip(hovered.button) +
                    Component.literal("§8Was: ${InventoryButtonSpots.describe(hovered.button.spot)}")
                g.setComponentTooltipForNextFrame(Minecraft.getInstance().font, lines, (mx * f).toInt(), (my * f).toInt())
            }
        }
    }

    private fun clearRects() {
        noticeRect = none
        moveRect = none
        trayRect = none
        gripRect = none
        closeRect = none
        cellRects.clear()
        cellIndices.clear()
    }

    private fun hit(r: IntArray, x: Int, y: Int): Boolean = r.size == 4 && x >= r[0] && x < r[2] && y >= r[1] && y < r[3]

    private fun rect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, pipeline: RenderPipeline) {
        g.guiRenderState.addGuiElement(RoundRectRenderState(pipeline, TextureSetup.noTexture(), Matrix3x2f(g.pose()), x0, y0, x1, y1, color, null))
    }
}
