package com.soulreturns.features.dungeons.terminals

import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Soul's terminal GUI, shown instead of [vanilla] while the real menu stays open underneath. Draws
 * only the playfield, large and high contrast, with the terminal's name and progress on top.
 *
 * Input: a click on a cell is handed to [TerminalSession.click] (at most one container click, on
 * that slot, now, with the same button); every other click, key and drag does nothing. Esc and the
 * inventory key close the menu exactly as the vanilla screen does. When the session stops being
 * certain, the vanilla screen comes back.
 */
class TerminalScreen(private val vanilla: ContainerScreen, private val session: TerminalSession) : SoulScreenBase(vanilla.title) {
    private companion object {
        const val PAD = 18
        const val HEADER_H = 24
        const val HEADER_GAP = 10
        val PANEL_BG = 0xF21A1A1A.toInt()
        val PANEL_EDGE = 0xFF333333.toInt()
    }

    private class Hit(val slot: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    private val hits = ArrayList<Hit>()
    private var handedBack = false
    private val type get() = session.spec.type

    override fun isPauseScreen(): Boolean = false

    override fun isInGameUi(): Boolean = true

    override fun tick() {
        session.poll()
        if (session.state == TerminalSession.FAILED) handBack()
    }

    private fun handBack() {
        if (handedBack) return
        handedBack = true
        val mc = minecraft ?: return
        mc.execute { if (mc.gui.screen() === this) mc.gui.setScreen(vanilla) }
    }

    override fun onClose() {
        val player = minecraft?.player
        if (player != null && player.containerMenu === session.menu) player.closeContainer()
        if (minecraft?.gui?.screen() === this) super.onClose()
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || minecraft?.options?.keyInventory?.matches(event) == true) onClose()
        return true
    }

    override fun mouseClicked(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val s = SoulUi.factor()
        val mx = click.x() / s
        val my = click.y() / s
        val hit = hits.lastOrNull { mx >= it.x0 && mx < it.x1 && my >= it.y0 && my < it.y1 } ?: return true
        session.click(hit.slot, click.button())
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean = true

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean = true

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean = true

    // ---------------------------------------------------------------- layout

    /** Cell size before the GUI size setting: bigger cells where the board has fewer of them. */
    private fun baseCell(): Int = when (type) {
        TerminalType.StartsWith, TerminalType.Select -> 40
        TerminalType.Panes, TerminalType.Order -> 48
        TerminalType.Rubix -> 58
        TerminalType.Melody -> 44
    }

    private val cols: List<Int> = type.board.map { it % 9 }.distinct().sorted()
    private val rows: List<Int> = type.board.map { it / 9 }.distinct().sorted()

    private class Geo(val cell: Int, val gap: Int, val boardW: Int, val boardH: Int)

    private fun geo(cell: Int): Geo {
        val gap = minOf(8, max(3, cell / 4))
        if (type === TerminalType.Melody) {
            val strip = max(6, cell / 4)
            return Geo(cell, gap, (6.6f * cell).roundToInt() + 8 * gap, 3 * cell + 2 * gap + 2 * (strip + gap))
        }
        return Geo(cell, gap, cols.size * cell + (cols.size - 1) * gap, rows.size * cell + (rows.size - 1) * gap)
    }

    // ---------------------------------------------------------------- render

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        session.poll()
        if (session.state == TerminalSession.FAILED) {
            handBack()
            return
        }
        hits.clear()
        val sc = SoulUi.factor()
        val uiW = (width / sc).toInt()
        val uiH = (height / sc).toInt()
        val mx = (mouseX / sc).toInt()
        val my = (mouseY / sc).toInt()
        g.pose().pushMatrix()
        g.pose().scale(sc, sc)

        var geo = geo((baseCell() * cfg.dungeons.terminals.guiSize.coerceIn(0.5f, 2f)).roundToInt())
        val chromeW = 2 * PAD
        val showTitle = cfg.dungeons.terminals.showTitle
        val chromeH = 2 * PAD + if (showTitle) HEADER_H + HEADER_GAP else 0
        val fit = minOf(1f, (uiW - 16 - chromeW).toFloat() / geo.boardW, (uiH - 16 - chromeH).toFloat() / geo.boardH)
        if (fit < 1f) geo = geo(max(12, (geo.cell * fit).toInt()))
        val w = max(geo.boardW, if (showTitle) headerWidth().coerceAtMost(uiW - 16 - chromeW) else 0) + chromeW
        val h = geo.boardH + chromeH
        val x0 = (uiW - w) / 2
        val y0 = (uiH - h) / 2
        roundRect(g, x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, PANEL_EDGE, SoulRenderPipelines.ROUND_MEDIUM)
        roundRect(g, x0, y0, x0 + w, y0 + h, PANEL_BG, SoulRenderPipelines.ROUND_MEDIUM)

        val sol = if (session.state == TerminalSession.ACTIVE) session.solution else null
        if (showTitle) drawHeader(g, sol, x0 + PAD, y0 + PAD, x0 + w - PAD)
        val bx = x0 + (w - geo.boardW) / 2
        val by = y0 + PAD + if (showTitle) HEADER_H + HEADER_GAP else 0
        val overlays = ArrayList<() -> Unit>()
        if (type === TerminalType.Melody) drawMelody(g, sol, geo, bx, by, mx, my, overlays) else drawGrid(g, sol, geo, bx, by, mx, my, overlays)
        // Numbers, checks and dims go above the item icons.
        g.nextStratum()
        overlays.forEach { it() }
        g.pose().popMatrix()
    }

    private val headerSize get() = fHeading * 1.15f

    /** Width the header needs: the title and the widest progress text. */
    private fun headerWidth(): Int {
        val semi = MsdfFonts.menuSemibold
        return textWidth(session.spec.title, headerSize, semi) + 16 + textWidth("88/88 left", headerSize, semi)
    }

    private fun drawHeader(g: GuiGraphicsExtractor, sol: TerminalTracker.Solution?, l: Int, t: Int, r: Int) {
        val size = headerSize
        val semi = MsdfFonts.menuSemibold
        val progress = if (sol == null) "…" else "${sol.remaining}/${sol.total} left"
        val pw = textWidth(progress, size, semi)
        drawTextV(g, progress, r - pw, t, t + HEADER_H, if (sol != null && sol.remaining == 0) TerminalColors.TARGET_EDGE else Theme.TEXT, size, semi)
        val right = r - pw - 10
        val title = ellipsize(session.spec.title, right - l, size, semi)
        drawTextV(g, title, l, t, t + HEADER_H, Theme.TEXT, size, semi)
    }

    // ---------------------------------------------------------------- cells

    /** Soul px that move the Soul coordinate [v] onto a whole physical pixel under the current pose. */
    private fun pixelShift(v: Int, scale: Float, translate: Float, guiScale: Int): Float {
        val px = (v * scale + translate) * guiScale
        return (px.roundToInt() - px) / (scale * guiScale)
    }

    /**
     * Runs [body] with the origin ([x], [y]) moved onto a whole physical pixel, so a cell's edges and ring are not
     * blurred at fractional UI scales; the overlays [body] adds are shifted the same way.
     */
    private fun snapped(g: GuiGraphicsExtractor, x: Int, y: Int, overlays: MutableList<() -> Unit>, body: (MutableList<() -> Unit>) -> Unit) {
        val pose = g.pose()
        val gs = minecraft?.window?.guiScale ?: 1
        val dx = pixelShift(x, pose.m00(), pose.m20(), gs)
        val dy = pixelShift(y, pose.m11(), pose.m21(), gs)
        val local = ArrayList<() -> Unit>()
        pose.pushMatrix()
        pose.translate(dx, dy)
        body(local)
        pose.popMatrix()
        if (local.isEmpty()) return
        overlays += {
            pose.pushMatrix()
            pose.translate(dx, dy)
            local.forEach { it() }
            pose.popMatrix()
        }
    }

    private fun fillCell(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int, style: SoulRenderPipelines.CellStyle) =
        roundRect(g, x, y, x + w, y + h, color, style.fill)

    /** A filled cell with an inset ring: the fill sits under the ring (concentric), nothing is drawn outside the cell. */
    private fun ringedCell(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, fill: Int, ring: Int, style: SoulRenderPipelines.CellStyle, overlays: MutableList<() -> Unit>) {
        roundRect(g, x + 1, y + 1, x + w - 1, y + h - 1, fill, style.under)
        overlays += { roundRect(g, x, y, x + w, y + h, ring, style.ring) }
    }

    /** Blocked click on [slot]: a faint tint and a thin red ring inside the cell, fading out over 180 ms. */
    private fun flashCue(g: GuiGraphicsExtractor, slot: Int, x0: Int, y0: Int, x1: Int, y1: Int, style: SoulRenderPipelines.CellStyle, overlays: MutableList<() -> Unit>) {
        val f = session.flash(slot)
        if (f <= 0f) return
        overlays += {
            roundRect(g, x0, y0, x1, y1, TerminalColors.flashFill(f), style.fill)
            roundRect(g, x0, y0, x1, y1, TerminalColors.flashEdge(f), style.ring)
        }
    }

    private fun drawGrid(g: GuiGraphicsExtractor, sol: TerminalTracker.Solution?, geo: Geo, bx: Int, by: Int, mx: Int, my: Int, overlays: MutableList<() -> Unit>) {
        val cs = geo.cell
        val style = SoulRenderPipelines.cellStyle(cs)
        for (slot in type.board) {
            val x = bx + cols.indexOf(slot % 9) * (cs + geo.gap)
            val y = by + rows.indexOf(slot / 9) * (cs + geo.gap)
            val cell = sol?.cells?.get(slot)
            hits += Hit(slot, x, y, x + cs, y + cs)
            val hover = sol != null && mx >= x && mx < x + cs && my >= y && my < y + cs
            snapped(g, x, y, overlays) { ov ->
                when {
                    cell == null -> fillCell(g, x, y, cs, cs, TerminalColors.IDLE_FILL, style)
                    type === TerminalType.Rubix -> drawRubixCell(g, cell, x, y, cs, hover, style, ov)
                    type === TerminalType.Order -> drawOrderCell(g, cell, slot, x, y, cs, hover, style, ov)
                    else -> drawCell(g, slot, cell, x, y, cs, hover, style, ov)
                }
                if (cell != null) flashCue(g, slot, x, y, x + cs, y + cs, style, ov)
            }
        }
    }

    private fun drawOrderCell(g: GuiGraphicsExtractor, cell: TerminalTracker.Cell, slot: Int, x: Int, y: Int, cs: Int, hover: Boolean, style: SoulRenderPipelines.CellStyle, overlays: MutableList<() -> Unit>) {
        val state = cell.state
        val rank = if (state == TerminalTracker.UPCOMING) cell.rank else 0
        when {
            state == TerminalTracker.TARGET ->
                ringedCell(g, x, y, cs, cs, TerminalColors.ORDER_NEXT_FILL, if (hover) TerminalColors.TARGET_HOVER else TerminalColors.TARGET_EDGE, style, overlays)
            rank == 1 -> {
                fillCell(g, x, y, cs, cs, TerminalColors.UPCOMING_FILL[0], style)
                overlays += { roundRect(g, x, y, x + cs, y + cs, TerminalColors.UPCOMING_DASH, style.dash) }
            }
            rank == 2 -> fillCell(g, x, y, cs, cs, TerminalColors.UPCOMING_FILL[1], style)
            state == TerminalTracker.DONE -> fillCell(g, x, y, cs, cs, TerminalColors.DONE_FILL, style)
            else -> fillCell(g, x, y, cs, cs, if (hover) TerminalColors.HOVER_FILL else TerminalColors.IDLE_FILL, style)
        }
        if (state == TerminalTracker.DONE) return
        val size = cs * if (state == TerminalTracker.TARGET) 0.62f else 0.5f
        val color = when {
            state == TerminalTracker.TARGET -> Theme.TEXT
            cell.rank in 1..3 -> TerminalColors.UPCOMING_TEXT[cell.rank - 1]
            else -> Theme.TEXT_FAINT
        }
        val n = session.view[slot].count.toString()
        overlays += { SoulText.drawCenteredOn(g, n, x + cs / 2, y + cs / 2, size, color, MsdfFonts.menuSemibold, forceCustom = true) }
    }

    private fun drawCell(g: GuiGraphicsExtractor, slot: Int, cell: TerminalTracker.Cell, x: Int, y: Int, cs: Int, hover: Boolean, style: SoulRenderPipelines.CellStyle, overlays: MutableList<() -> Unit>) {
        val showIcon = type === TerminalType.StartsWith || type === TerminalType.Select
        val state = cell.state
        when (state) {
            TerminalTracker.TARGET ->
                ringedCell(g, x, y, cs, cs, TerminalColors.TARGET_FILL, if (hover) TerminalColors.TARGET_HOVER else TerminalColors.TARGET_EDGE, style, overlays)
            TerminalTracker.DONE -> fillCell(g, x, y, cs, cs, TerminalColors.DONE_FILL, style)
            else -> fillCell(g, x, y, cs, cs, if (hover) TerminalColors.HOVER_FILL else TerminalColors.IDLE_FILL, style)
        }
        if (!showIcon) return
        val icon = (cs * 0.62f).roundToInt()
        g.pose().pushMatrix()
        g.pose().translate((x + (cs - icon) / 2).toFloat(), (y + (cs - icon) / 2).toFloat())
        g.pose().scale(icon / 16f, icon / 16f)
        g.fakeItem(session.view[slot], 0, 0)
        g.pose().popMatrix()
        if (state == TerminalTracker.IDLE) overlays += { fillCell(g, x, y, cs, cs, TerminalColors.IDLE_DIM, style) }
        if (state == TerminalTracker.DONE) {
            val c = max(10, cs / 3)
            overlays += {
                fillCell(g, x, y, cs, cs, TerminalColors.DONE_DIM, style)
                drawIcon(g, "check", x + (cs - c) / 2, y + (cs - c) / 2, c, TerminalColors.CHECK)
            }
        }
    }

    /**
     * Like Odin's: a cell shows only what to do, never its pane's color. A signed count (minus = right clicks)
     * on the action's color, a small "left"/"right" under it; done cells stay empty.
     */
    private fun drawRubixCell(g: GuiGraphicsExtractor, cell: TerminalTracker.Cell, x: Int, y: Int, cs: Int, hover: Boolean, style: SoulRenderPipelines.CellStyle, overlays: MutableList<() -> Unit>) {
        if (cell.clicks == 0) {
            fillCell(g, x, y, cs, cs, TerminalColors.DONE_FILL, style)
            return
        }
        fillCell(g, x, y, cs, cs, TerminalColors.rubixAction(cell.clicks), style)
        if (hover) overlays += { roundRect(g, x, y, x + cs, y + cs, TerminalColors.TARGET_HOVER, style.ring) }
        val labels = cfg.dungeons.terminals.rubixLabels
        overlays += {
            SoulText.drawCenteredOn(g, cell.clicks.toString(), x + cs / 2, y + cs / 2 - if (labels) cs / 12 else 0, cs * 0.5f, Theme.TEXT, MsdfFonts.menuSemibold, forceCustom = true)
            if (labels) {
                val hint = if (cell.clicks < 0) "right" else "left"
                SoulText.drawCenteredOn(g, hint, x + cs / 2, y + cs - cs / 5, fCaption, 0xCCFFFFFF.toInt(), MsdfFonts.menuSemibold, forceCustom = true, shadow = false)
            }
        }
    }

    private fun drawMelody(g: GuiGraphicsExtractor, sol: TerminalTracker.Solution?, geo: Geo, bx: Int, by: Int, mx: Int, my: Int, overlays: MutableList<() -> Unit>) {
        val cs = geo.cell
        val gap = geo.gap
        val style = SoulRenderPipelines.cellStyle(cs)
        val strip = max(6, cs / 4)
        val stripStyle = SoulRenderPipelines.cellStyle(0)
        val m = sol?.melody
        val laneTop = by + strip + gap
        val buttonX = bx + 5 * (cs + gap) + gap * 3
        val target = m?.target
        if (target != null) {
            val cx = bx + (target - 1) * (cs + gap)
            snapped(g, cx, by, overlays) {
                fillCell(g, cx, by, cs, geo.boardH, TerminalColors.TARGET_COLUMN_TINT, style)
                fillCell(g, cx, by, cs, strip, TerminalColors.TARGET_COLUMN, stripStyle)
                fillCell(g, cx, by + geo.boardH - strip, cs, strip, TerminalColors.TARGET_COLUMN, stripStyle)
            }
        }
        for (l in 0 until 3) {
            val lane = m?.lanes?.getOrNull(l)
            val y = laneTop + l * (cs + gap)
            val buttonCell = lane?.let { sol?.cells?.get(it.button) }
            // Our lock is on its way: the lane keeps its pane (the server's, wherever it is) and the button says so.
            val locked = sol?.melodyLocked == l
            val done = !locked && buttonCell?.state == TerminalTracker.DONE
            val active = lane != null && m?.active == l
            val live = active || locked
            for (c in 1..5) {
                val x = bx + (c - 1) * (cs + gap)
                val slot = (l + 1) * 9 + c
                hits += Hit(slot, x, y, x + cs, y + cs)
                // The green pane is drawn wherever the server has it, in every lane state.
                val moving = lane?.moving == c
                val fill = when {
                    moving -> TerminalColors.LANE_MOVING
                    live -> TerminalColors.LANE_ACTIVE
                    done -> TerminalColors.DONE_FILL
                    else -> TerminalColors.LANE_IDLE
                }
                snapped(g, x, y, overlays) { ov ->
                    fillCell(g, x, y, cs, cs, fill, style)
                    if (live && c == target) {
                        val ring = if (moving) TerminalColors.TARGET_HOVER else TerminalColors.TARGET_COLUMN
                        ov += { roundRect(g, x, y, x + cs, y + cs, ring, style.ring) }
                    }
                    flashCue(g, slot, x, y, x + cs, y + cs, style, ov)
                }
            }
            val slot = (l + 1) * 9 + 7
            val bw = (cs * 1.6f).roundToInt()
            hits += Hit(slot, buttonX, y, buttonX + bw, y + cs)
            val hover = mx >= buttonX && mx < buttonX + bw && my >= y && my < y + cs
            val now = !locked && buttonCell?.state == TerminalTracker.TARGET
            snapped(g, buttonX, y, overlays) { ov ->
                val cx = buttonX + bw / 2
                val cy = y + cs / 2
                if (locked) {
                    ringedCell(g, buttonX, y, bw, cs, TerminalColors.LANE_IDLE, TerminalColors.NOW, style, ov)
                    ov += { SoulText.drawCenteredOn(g, "locked", cx, cy, cs * 0.3f, Theme.TEXT, MsdfFonts.menuSemibold, forceCustom = true) }
                } else if (now) {
                    ringedCell(g, buttonX, y, bw, cs, TerminalColors.TARGET_FILL, if (hover) TerminalColors.TARGET_HOVER else TerminalColors.NOW, style, ov)
                    ov += { SoulText.drawCenteredOn(g, "NOW", cx, cy, cs * 0.42f, Theme.TEXT, MsdfFonts.menuSemibold, forceCustom = true) }
                } else if (done) {
                    fillCell(g, buttonX, y, bw, cs, TerminalColors.DONE_FILL, style)
                    val c = max(10, cs / 3)
                    ov += { drawIcon(g, "check", buttonX + (bw - c) / 2, y + (cs - c) / 2, c, TerminalColors.CHECK) }
                } else if (active) {
                    ringedCell(g, buttonX, y, bw, cs, TerminalColors.LANE_IDLE, 0xFF3A3A3A.toInt(), style, ov)
                    ov += { SoulText.drawCenteredOn(g, "wait", cx, cy, cs * 0.3f, Theme.TEXT_DIM, MsdfFonts.menuSemibold, forceCustom = true) }
                } else {
                    fillCell(g, buttonX, y, bw, cs, TerminalColors.LANE_IDLE, style)
                }
                flashCue(g, slot, buttonX, y, buttonX + bw, y + cs, style, ov)
            }
        }
    }
}
