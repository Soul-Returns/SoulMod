package com.soulreturns.features.inventory

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.InventoryInfoConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Settings for "Show SkyBlock info in inventory": a live preview of the inventory on the left
 * (real data when the profile is loaded, sample data otherwise) and the entry list on the right —
 * tick to show, arrows to reorder, entries past what fits are flagged. Every change saves at once.
 */
class InventoryInfoScreen(private val parent: Screen? = null) : SoulScreenBase(Component.literal("SkyBlock Info")) {
    companion object {
        private val WARNING = 0xFFFFD744.toInt()

        fun open(parent: Screen? = null) {
            val mc = Minecraft.getInstance()
            mc.execute { mc.setScreenAndShow(InventoryInfoScreen(parent)) }
        }
    }

    private var bodyRect = intArrayOf(0, 0, 0, 0)
    private var scroll = 0f
    private var contentHeight = 0

    init {
        // The preview prefers real data, so ask for it even while the display itself is off.
        SkyblockSelfProfile.refreshIfStale(5 * 60_000L)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        regions.clear()
        val f = SoulUi.factor()
        val uiW = (width / f).toInt()
        val uiH = (height / f).toInt()
        val mx = (mouseX / f).roundToInt()
        val my = (mouseY / f).roundToInt()
        val panelW = (uiW * 0.4f).toInt().coerceAtMost(250)
        val px1 = uiW - 6
        val px0 = px1 - panelW

        g.fill(0, 0, width, height, Theme.DIM)
        drawPreview(g, ((px0 - 6) * f).toInt(), mouseX, mouseY)
        g.pose().pushMatrix()
        g.pose().scale(f, f)
        drawPanel(g, mx, my, px0, 6, px1, uiH - 6)
        drawAlertModal(g, uiW, uiH, mx, my)
        g.pose().popMatrix()
    }

    /** The inventory, centered in the area left of the panel and shrunk if it does not fit. */
    private fun drawPreview(g: GuiGraphicsExtractor, areaW: Int, mouseX: Int, mouseY: Int) {
        val scale = minOf(1f, (areaW - 16f) / InventoryPreview.WIDTH, (height - 16f) / InventoryPreview.HEIGHT).coerceAtLeast(0.25f)
        val fx = ((areaW - InventoryPreview.WIDTH * scale) / 2f).roundToInt()
        val fy = ((height - InventoryPreview.HEIGHT * scale) / 2f).roundToInt()
        g.pose().pushMatrix()
        g.pose().translate(fx.toFloat(), fy.toFloat())
        g.pose().scale(scale, scale)
        InventoryPreview.drawFrame(g, forceInfo = true)
        g.pose().popMatrix()
        if (scale == 1f) InventoryPreview.drawPlayer(g, fx, fy, mouseX, mouseY)
    }

    private fun drawPanel(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        roundRect(g, x0, y0, x1, y1, Theme.PANEL)
        drawTextV(g, "SkyBlock Info", x0 + 10, y0, y0 + 28, Theme.TEXT, fHeading, MsdfFonts.menuSemibold)
        val footerTop = y1 - 34
        bodyRect = intArrayOf(x0 + 4, y0 + 29, x1 - 4, max(y0 + 29, footerTop))
        beginClip(g, bodyRect[0], bodyRect[1], bodyRect[2], bodyRect[3])
        val end = drawBody(g, mx, my, bodyRect[1] + 6 - scroll.roundToInt())
        endClip(g)
        contentHeight = end + scroll.roundToInt() - bodyRect[1] + 6
        scroll = clampScroll(scroll, contentHeight, bodyRect[3] - bodyRect[1])

        g.fill(x0 + 1, footerTop, x1 - 1, footerTop + 1, Theme.SEPARATOR)
        val half = (x1 - x0 - 22) / 2
        drawButton(g, mx, my, x0 + 8, footerTop + 7, x0 + 8 + half, footerTop + 29, "Reset") {
            val defaults = InventoryInfoConfig()
            update {
                copy(
                    progressBars = defaults.progressBars, soulFont = defaults.soulFont, layout = defaults.layout,
                    hoverDetails = defaults.hoverDetails, levelColor = defaults.levelColor, entries = defaults.entries,
                )
            }
        }
        drawButton(g, mx, my, x1 - 8 - half, footerTop + 7, x1 - 8, footerTop + 29, "Done") { onClose() }
    }

    private fun drawBody(g: GuiGraphicsExtractor, mx: Int, my: Int, top: Int): Int {
        val config = cfg.inventoryInfo
        val left = bodyRect[0] + 8
        val right = bodyRect[2] - 8
        var y = top

        if (!InventoryInfo.requirementsMet()) {
            y = hint(g, "Needs the crafting grid, the recipe book and the off-hand slot hidden: this is their space.", left, right, y, WARNING)
            clippedButton(g, mx, my, left, y + 2, right, y + 22, "Hide all three", bodyRect, accent = true) {
                ConfigManager.update { copy(general = general.copy(cleanUpInventory = true, hideCraftingGrid = true, hideRecipeBook = true, hideOffhandSlot = true)) }
            }
            y += 30
        }
        val half = (right - left - 6) / 2
        clippedButton(g, mx, my, left, y, left + half, y + 20, if (config.enabled) "Shown: On" else "Shown: Off", bodyRect, accent = config.enabled) {
            update { copy(enabled = !enabled) }
        }
        clippedButton(g, mx, my, right - half, y, right, y + 20, if (config.progressBars) "Bars: On" else "Bars: Off", bodyRect) {
            update { copy(progressBars = !progressBars) }
        }
        y += 26
        clippedButton(g, mx, my, left, y, left + half, y + 20, if (config.layout == 0) "Layout: Tiles" else "Layout: List", bodyRect) {
            update { copy(layout = if (layout == 0) 1 else 0) }
        }
        clippedButton(g, mx, my, right - half, y, right, y + 20, if (config.soulFont) "Font: Soul" else "Font: Minecraft", bodyRect) {
            update { copy(soulFont = !soulFont) }
        }
        y += 26
        clippedButton(g, mx, my, left, y, left + half, y + 20, if (config.hoverDetails) "Hover details: On" else "Hover details: Off", bodyRect) {
            update { copy(hoverDetails = !hoverDetails) }
        }
        clippedButton(g, mx, my, right - half, y, right, y + 20, if (config.levelColor) "Level color: On" else "Level color: Off", bodyRect) {
            update { copy(levelColor = !levelColor) }
        }
        y += 28
        val source = if (SkyblockSelfProfile.snapshot != null) "Preview shows your profile." else "Preview shows sample data until your profile loads."
        val layoutHint = if (config.layout == 0) "Tiles show the profile as a header and four stats." else "The list shows as many rows as fit."
        y = hint(g, "$source $layoutHint Tick an entry to show it; arrows change the order.", left, right, y, Theme.TEXT_DIM) + 6

        val shown = config.entries.filter { InventoryInfoEntries.byId(it) != null }
        val fits = InventoryInfo.fitting(shown, config.progressBars)
        val rows = shown.mapNotNull(InventoryInfoEntries::byId) + InventoryInfoEntries.ALL.filter { it.id !in shown }
        for (entry in rows) {
            val index = shown.indexOf(entry.id)
            val on = index >= 0
            val hovered = within(bodyRect, mx, my) && my in y..y + 20 && mx in left..right
            roundRect(g, left - 2, y, right + 2, y + 20, if (hovered) Theme.PANEL_HOVER else Theme.SECTION, SoulRenderPipelines.ROUND_SMALL)
            // Checkbox.
            if (on) roundRect(g, left + 3, y + 4, left + 15, y + 16, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
            else panel(g, left + 3, y + 4, left + 15, y + 16, Theme.FIELD_BG, Theme.FIELD_BORDER, SoulPanel.SMALL)
            val overflow = on && entry.id !in fits
            drawTextV(g, entry.title, left + 21, y, y + 20, if (on) Theme.TEXT else Theme.TEXT_DIM)
            if (overflow) {
                val note = "doesn't fit"
                drawTextV(g, note, right - 44 - textWidth(note, fCaption), y, y + 20, WARNING, fCaption)
            }
            clippedRegion(left - 2, y, right - 42, y + 20, bodyRect) { toggle(entry.id) }
            if (on) {
                arrowButton(g, mx, my, right - 40, y + 2, up = true, enabled = index > 0) { move(entry.id, -1) }
                arrowButton(g, mx, my, right - 20, y + 2, up = false, enabled = index < shown.lastIndex) { move(entry.id, 1) }
            }
            y += 23
        }
        return y
    }

    private fun arrowButton(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int, y: Int, up: Boolean, enabled: Boolean, action: () -> Unit) {
        val hovered = enabled && within(bodyRect, mx, my) && mx in x..x + 18 && my in y..y + 16
        roundRect(g, x, y, x + 18, y + 16, if (hovered) Theme.CONTROL_HOVER else Theme.CONTROL, SoulRenderPipelines.ROUND_SMALL)
        val color = if (enabled) Theme.TEXT else Theme.TEXT_FAINT
        val cx = x + 9
        val cy = y + 6
        // ▲/▼ are in no MSDF family; three fill rows make the triangle.
        for (i in 0..2) {
            val row = if (up) cy + i else cy + 2 - i
            g.fill(cx - i, row, cx + i + 1, row + 1, color)
        }
        if (enabled) clippedRegion(x, y, x + 18, y + 16, bodyRect, action)
    }

    private fun hint(g: GuiGraphicsExtractor, text: String, left: Int, right: Int, top: Int, color: Int): Int {
        var y = top
        for (line in wrapText(text, (right - left).coerceAtLeast(1), fCaption)) {
            drawText(g, line, left, y, color, fCaption)
            y += 13
        }
        return y
    }

    private fun toggle(id: String) = update {
        copy(entries = if (id in entries) entries - id else entries + id)
    }

    private fun move(id: String, delta: Int) = update {
        val list = entries.toMutableList()
        val from = list.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in list.indices) return@update this
        list.add(to, list.removeAt(from))
        copy(entries = list)
    }

    private inline fun update(crossinline block: InventoryInfoConfig.() -> InventoryInfoConfig) {
        ConfigManager.update { copy(inventoryInfo = inventoryInfo.block()) }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (alertModalOpen) return true
        val f = SoulUi.factor()
        val mx = (mouseX / f).roundToInt()
        val my = (mouseY / f).roundToInt()
        if (within(bodyRect, mx, my)) scroll = clampScroll(scroll - scrollY.toFloat() * 18, contentHeight, bodyRect[3] - bodyRect[1])
        return true
    }

    override fun onClose() {
        if (alertModalOpen) {
            closeAlert()
            regions.clear()
            return
        }
        if (parent != null) minecraft?.setScreenAndShow(parent) else super.onClose()
    }
}
