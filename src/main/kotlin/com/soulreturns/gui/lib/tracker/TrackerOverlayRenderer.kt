package com.soulreturns.gui.lib.tracker

import com.soulreturns.gui.lib.GuiHitRegion
import com.soulreturns.gui.lib.GuiRenderContext
import com.soulreturns.gui.lib.TrackerOverlayElement

/**
 * Paints a [TrackerOverlay] panel — translucent rounded background, header with title + tab
 * buttons, scrollable list of [TrackerRow]s, footer with sort / limit / reset buttons.
 *
 * All drawing goes through [GuiRenderContext] so the renderer stays Minecraft-free; only
 * pixel arithmetic + abstract draw calls live here.
 *
 * Records [GuiHitRegion]s for every interactive element so [TrackerInputHandler] can route
 * clicks. A separate `TRACKER_OVERLAY_SCROLL_REGION` hit-region covers the list area for the
 * scroll-wheel input path.
 */
object TrackerOverlayRenderer {
    // Colors — sourced from the existing Theme palette but blended for translucency.
    private const val PANEL_COLOR = 0xD81A1A1A.toInt() // ~85% alpha dark grey
    private const val SEPARATOR_COLOR = 0xFF2A2A2A.toInt()
    private const val BUTTON_BG = 0xFF242424.toInt()
    private const val BUTTON_ACTIVE_BG = 0xFF3B82F6.toInt()
    private const val TEXT = 0xFFEEEEEE.toInt()
    private const val TEXT_DIM = 0xFF888888.toInt()
    private const val TEXT_ACCENT = 0xFFFFFFFF.toInt()
    private const val PANEL_RADIUS = 6f
    private const val BUTTON_RADIUS = 4f

    // Layout constants (all in scaled-GUI pixels at element scale = 1).
    private const val PADDING = 6
    private const val ROW_HEIGHT = 11
    private const val TAB_HEIGHT = 14
    private const val TAB_PAD_X = 6
    private const val TAB_GAP = 3
    private const val BUTTON_HEIGHT = 12
    private const val BUTTON_PAD_X = 5
    private const val FOOTER_GAP = 4
    private const val SECTION_GAP = 4
    private const val PANEL_WIDTH = 200

    /**
     * Render the overlay panel for [element]. Returns nothing — recorded hit regions are
     * appended to [regions].
     */
    fun render(
        element: TrackerOverlayElement,
        overlay: TrackerOverlay,
        settings: TrackerSettings,
        ctx: GuiRenderContext,
        regions: MutableList<GuiHitRegion>,
    ) {
        val baseX = (element.anchorX * ctx.screenWidth).toInt() + element.offsetX
        val baseY = (element.anchorY * ctx.screenHeight).toInt() + element.offsetY
        val scale = element.scale.coerceAtLeast(0.5f).coerceAtMost(2.5f)

        // We render in unscaled pixel space but with the underlying GuiGraphics matrix pre-scaled.
        // The renderer itself is naive; scaling is a wrapper. We approximate scale by inflating
        // pixel constants directly to keep hit-regions correct in screen-space.
        val widthPx = (PANEL_WIDTH * scale).toInt()
        val rowH = (ROW_HEIGHT * scale).toInt().coerceAtLeast(8)
        val tabH = (TAB_HEIGHT * scale).toInt().coerceAtLeast(10)
        val btnH = (BUTTON_HEIGHT * scale).toInt().coerceAtLeast(9)
        val padX = (PADDING * scale).toInt().coerceAtLeast(4)
        val padY = (PADDING * scale).toInt().coerceAtLeast(4)
        val sectionGap = (SECTION_GAP * scale).toInt().coerceAtLeast(3)
        val textScale = scale

        // Compute layout bottom-up so we know the total height before drawing the background.
        val maxListRows = settings.limit.let { if (it < 0) Int.MAX_VALUE else it }
        val activeTab = overlay.tabByName(settings.activeTab)
        val sortKey = overlay.sortByKey(settings.sortKey).key
        val allRows =
            activeTab.rowsProvider()
                .sortedByDescending { it.sortValues[sortKey] ?: 0L }
        val visibleRows = if (allRows.size > maxListRows) allRows.take(maxListRows) else allRows
        val totalListRows = visibleRows.size

        val viewportRows = minOf(totalListRows, 12).coerceAtLeast(1)
        val listHeight = viewportRows * rowH

        val headerHeight = tabH + sectionGap
        val footerHeight = btnH + sectionGap
        val totalHeight = padY + headerHeight + listHeight + sectionGap + footerHeight + padY

        // Background panel.
        ctx.fillRoundedRect(baseX, baseY, widthPx, totalHeight, PANEL_COLOR, PANEL_RADIUS * scale)

        var cursorY = baseY + padY

        // ───── Header: title (left) + tab buttons (right, right-aligned).
        drawHeader(
            element = element,
            overlay = overlay,
            settings = settings,
            ctx = ctx,
            regions = regions,
            baseX = baseX,
            cursorY = cursorY,
            widthPx = widthPx,
            tabH = tabH,
            padX = padX,
            textScale = textScale,
        )
        cursorY += headerHeight

        // ───── Scrollable list region.
        val listX = baseX + padX
        val listY = cursorY
        val listW = widthPx - padX * 2

        // Whole list region records a scroll hit-target.
        regions +=
            GuiHitRegion(
                elementId = element.id,
                kind = GuiHitRegion.Kind.TRACKER_OVERLAY_SCROLL_REGION,
                x = listX,
                y = listY,
                width = listW,
                height = listHeight,
            )

        val maxScroll = (totalListRows - viewportRows).coerceAtLeast(0)
        val clampedScroll = settings.scrollOffset.coerceIn(0, maxScroll)
        if (clampedScroll != settings.scrollOffset) settings.scrollOffset = clampedScroll

        ctx.pushScissor(listX, listY, listW, listHeight)
        try {
            for (rowIndex in 0 until viewportRows) {
                val row = visibleRows.getOrNull(rowIndex + clampedScroll) ?: break
                drawRow(
                    row = row,
                    ctx = ctx,
                    x = listX,
                    y = listY + rowIndex * rowH,
                    width = listW,
                    rowH = rowH,
                    textScale = textScale,
                )
            }
            if (totalListRows == 0) {
                ctx.drawScaledText("(no data yet)", listX, listY + 2, TEXT_DIM, true, textScale)
            }
        } finally {
            ctx.popScissor()
        }
        cursorY += listHeight + sectionGap

        // ───── Separator.
        ctx.fillRect(baseX + padX, cursorY - sectionGap / 2, widthPx - padX * 2, 1, SEPARATOR_COLOR)

        // ───── Footer: sort, limit, [reset].
        drawFooter(
            element = element,
            overlay = overlay,
            settings = settings,
            ctx = ctx,
            regions = regions,
            baseX = baseX,
            cursorY = cursorY,
            widthPx = widthPx,
            btnH = btnH,
            padX = padX,
            textScale = textScale,
        )
    }

    private fun drawHeader(
        element: TrackerOverlayElement,
        overlay: TrackerOverlay,
        settings: TrackerSettings,
        ctx: GuiRenderContext,
        regions: MutableList<GuiHitRegion>,
        baseX: Int,
        cursorY: Int,
        widthPx: Int,
        tabH: Int,
        padX: Int,
        textScale: Float,
    ) {
        // Title left-aligned, vertically centered against tab height.
        val titleY = cursorY + (tabH - (ctx.textLineHeight * textScale).toInt()) / 2
        ctx.drawScaledText(overlay.title, baseX + padX, titleY, TEXT, true, textScale)

        // Tabs right-aligned.
        val tabPadX = (TAB_PAD_X * textScale).toInt().coerceAtLeast(3)
        val tabGap = (TAB_GAP * textScale).toInt().coerceAtLeast(2)
        // Measure tabs right-to-left to compute right-edge anchor.
        val tabWidths =
            overlay.tabs.map { tab ->
                val labelWidth = (ctx.textWidth(tab.name) * textScale).toInt()
                labelWidth + tabPadX * 2
            }
        val totalTabWidth = tabWidths.sum() + tabGap * (overlay.tabs.size - 1).coerceAtLeast(0)
        var tabX = baseX + widthPx - padX - totalTabWidth
        for ((i, tab) in overlay.tabs.withIndex()) {
            val tw = tabWidths[i]
            val active = tab.name == settings.activeTab
            val bg = if (active) BUTTON_ACTIVE_BG else BUTTON_BG
            ctx.fillRoundedRect(tabX, cursorY, tw, tabH, bg, BUTTON_RADIUS * textScale)
            val labelWidth = (ctx.textWidth(tab.name) * textScale).toInt()
            val labelX = tabX + (tw - labelWidth) / 2
            val labelY = cursorY + (tabH - (ctx.textLineHeight * textScale).toInt()) / 2
            val labelColor = if (active) TEXT_ACCENT else TEXT
            ctx.drawScaledText(tab.name, labelX, labelY, labelColor, true, textScale)
            regions +=
                GuiHitRegion(
                    elementId = element.id,
                    kind = GuiHitRegion.Kind.TRACKER_OVERLAY_TAB,
                    x = tabX,
                    y = cursorY,
                    width = tw,
                    height = tabH,
                    payload = tab.name,
                )
            tabX += tw + tabGap
        }
    }

    private fun drawRow(
        row: TrackerRow,
        ctx: GuiRenderContext,
        x: Int,
        y: Int,
        width: Int,
        rowH: Int,
        textScale: Float,
    ) {
        val textY = y + (rowH - (ctx.textLineHeight * textScale).toInt()) / 2
        ctx.drawScaledText(row.label, x, textY, TEXT, true, textScale)

        // Right-aligned: primary value, optional secondary.
        val primaryStr = "%,d".format(row.primaryValue)
        val secondaryStr =
            row.secondaryValue?.let { v ->
                if (row.secondaryLabel.isEmpty()) "%,d".format(v) else "%,d ${row.secondaryLabel}".format(v)
            }

        val secondaryWidth =
            if (secondaryStr != null) (ctx.textWidth(secondaryStr) * textScale).toInt() else 0
        val gap = if (secondaryStr != null) (6 * textScale).toInt() else 0
        val primaryWidth = (ctx.textWidth(primaryStr) * textScale).toInt()

        var rightX = x + width
        if (secondaryStr != null) {
            rightX -= secondaryWidth
            ctx.drawScaledText(secondaryStr, rightX, textY, TEXT_DIM, true, textScale)
            rightX -= gap
        }
        rightX -= primaryWidth
        ctx.drawScaledText(primaryStr, rightX, textY, TEXT, true, textScale)
    }

    private fun drawFooter(
        element: TrackerOverlayElement,
        overlay: TrackerOverlay,
        settings: TrackerSettings,
        ctx: GuiRenderContext,
        regions: MutableList<GuiHitRegion>,
        baseX: Int,
        cursorY: Int,
        widthPx: Int,
        btnH: Int,
        padX: Int,
        textScale: Float,
    ) {
        val btnPadX = (BUTTON_PAD_X * textScale).toInt().coerceAtLeast(3)
        val btnGap = (FOOTER_GAP * textScale).toInt().coerceAtLeast(2)

        val sortLabel = "Sort: ${overlay.sortByKey(settings.sortKey).label}"
        val limitLabel = "Show: ${formatLimit(settings.limit)}"
        val resetLabel = "Reset Session"

        val sortW = (ctx.textWidth(sortLabel) * textScale).toInt() + btnPadX * 2
        val limitW = (ctx.textWidth(limitLabel) * textScale).toInt() + btnPadX * 2
        val resetW =
            if (overlay.showResetButton) (ctx.textWidth(resetLabel) * textScale).toInt() + btnPadX * 2 else 0

        var x = baseX + padX
        // Sort button
        renderFooterButton(
            ctx = ctx,
            regions = regions,
            element = element,
            kind = GuiHitRegion.Kind.TRACKER_OVERLAY_CYCLE_SORT,
            x = x,
            y = cursorY,
            width = sortW,
            height = btnH,
            label = sortLabel,
            textScale = textScale,
        )
        x += sortW + btnGap
        renderFooterButton(
            ctx = ctx,
            regions = regions,
            element = element,
            kind = GuiHitRegion.Kind.TRACKER_OVERLAY_CYCLE_LIMIT,
            x = x,
            y = cursorY,
            width = limitW,
            height = btnH,
            label = limitLabel,
            textScale = textScale,
        )
        if (overlay.showResetButton) {
            // Reset button right-aligned.
            val resetX = baseX + widthPx - padX - resetW
            renderFooterButton(
                ctx = ctx,
                regions = regions,
                element = element,
                kind = GuiHitRegion.Kind.TRACKER_OVERLAY_RESET,
                x = resetX,
                y = cursorY,
                width = resetW,
                height = btnH,
                label = resetLabel,
                textScale = textScale,
            )
        }
    }

    private fun renderFooterButton(
        ctx: GuiRenderContext,
        regions: MutableList<GuiHitRegion>,
        element: TrackerOverlayElement,
        kind: GuiHitRegion.Kind,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        label: String,
        textScale: Float,
    ) {
        ctx.fillRoundedRect(x, y, width, height, BUTTON_BG, BUTTON_RADIUS * textScale)
        val labelWidth = (ctx.textWidth(label) * textScale).toInt()
        val labelX = x + (width - labelWidth) / 2
        val labelY = y + (height - (ctx.textLineHeight * textScale).toInt()) / 2
        ctx.drawScaledText(label, labelX, labelY, TEXT, true, textScale)
        regions +=
            GuiHitRegion(
                elementId = element.id,
                kind = kind,
                x = x,
                y = y,
                width = width,
                height = height,
            )
    }

    private fun formatLimit(limit: Int): String = if (limit < 0) "All" else "Top $limit"
}
