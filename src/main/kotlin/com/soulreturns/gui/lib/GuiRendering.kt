package com.soulreturns.gui.lib

/**
 * Minimal rendering context abstraction used by the GUI library.
 *
 * Hosts provide an implementation that wraps their actual rendering API
 * (e.g. Minecraft's DrawContext/TextRenderer/item rendering).
 */
interface GuiRenderContext {
    val screenWidth: Int
    val screenHeight: Int

    fun drawText(
        text: String,
        x: Int,
        y: Int,
        color: Int,
        shadow: Boolean,
    )

    /** Draw scaled text. Implementations may optimize by delegating to [drawText] when scale == 1f. */
    fun drawScaledText(
        text: String,
        x: Int,
        y: Int,
        color: Int,
        shadow: Boolean,
        scale: Float,
    )

    /** Draw a simple background rectangle (ARGB color). */
    fun fillRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Int
    )

    /** Draw a rounded-corner rectangle (ARGB color). Implementations may fall back to [fillRect]. */
    fun fillRoundedRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Int,
        radius: Float,
    )

    /** Measure the rendered width of [text] in pixels at scale=1. */
    fun textWidth(text: String): Int

    /** Default text line height in pixels at scale=1. */
    val textLineHeight: Int

    /**
     * Constrain subsequent draw calls to the given rectangle. Must be paired with [popScissor];
     * pairs may nest. Implementations may use a hardware scissor box (`glScissor` via
     * `GuiGraphics.enableScissor`) so coordinates are in scaled-GUI space.
     */
    fun pushScissor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )

    fun popScissor()

    /** Draw an item icon at the given position using a host-specific key. */
    fun drawItemIcon(
        iconKey: String,
        x: Int,
        y: Int
    )
}

/**
 * Hit region for interactive elements (e.g. +/- buttons in item trackers).
 */
data class GuiHitRegion(
    val elementId: GuiElementId,
    val kind: Kind,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val payload: String? = null,
) {
    enum class Kind {
        ITEM_TRACKER_INCREMENT,
        ITEM_TRACKER_DECREMENT,
    }

    fun contains(
        px: Int,
        py: Int
    ): Boolean {
        return px >= x && px <= x + width && py >= y && py <= y + height
    }
}

/**
 * Snapshot of hit regions produced during a render pass.
 */
data class GuiInteractionSnapshot(
    val hitRegions: List<GuiHitRegion> = emptyList(),
)

/**
 * Library-level renderer that knows how to draw the supported GuiElement
 * variants and record interaction hit regions.
 */
object GuiRenderer {
    /**
     * Render the given layout on the HUD and return an interaction snapshot
     * describing any clickable regions.
     */
    fun renderHud(
        layout: GuiLayout,
        ctx: GuiRenderContext
    ): GuiInteractionSnapshot {
        val regions = mutableListOf<GuiHitRegion>()

        layout.elements.forEach { element ->
            if (!element.enabled) return@forEach

            when (element) {
                is TextBlockElement -> renderTextBlock(element, ctx)
                is ItemTrackerElement -> renderItemTracker(element, ctx, regions)
                // SoulHudElements have their own NVG-based render path via `SoulHud.dispatchAll`
                // invoked separately from `SoulGuiHudAdapter.renderHud`. The legacy
                // `GuiRenderContext`-based path is a no-op for them.
                is SoulHudElement -> Unit
            }
        }

        return GuiInteractionSnapshot(regions)
    }

    private fun computeBasePosition(
        element: GuiElement,
        ctx: GuiRenderContext
    ): Pair<Int, Int> {
        val baseX = (element.anchorX * ctx.screenWidth).toInt() + element.offsetX
        val baseY = (element.anchorY * ctx.screenHeight).toInt() + element.offsetY
        return baseX to baseY
    }

    private fun renderTextBlock(
        element: TextBlockElement,
        ctx: GuiRenderContext
    ) {
        val (baseX, baseY) = computeBasePosition(element, ctx)
        var y = baseY
        val scale = element.scale.coerceAtLeast(0.25f)
        val lineStep = (10f * scale).toInt().coerceAtLeast(4)

        element.title?.let { title ->
            ctx.drawScaledText(title, baseX, y, element.color, element.textShadow, scale)
            y += lineStep
        }

        element.lines.forEach { line ->
            ctx.drawScaledText(line, baseX, y, element.color, element.textShadow, scale)
            y += lineStep
        }
    }

    private fun renderItemTracker(
        element: ItemTrackerElement,
        ctx: GuiRenderContext,
        regions: MutableList<GuiHitRegion>,
    ) {
        val (baseX, baseY) = computeBasePosition(element, ctx)
        var y = baseY
        val scale = element.scale.coerceAtLeast(0.25f)
        val lineStep = (12f * scale).toInt().coerceAtLeast(4)

        element.title?.let { title ->
            ctx.drawScaledText(title, baseX, y, element.textColor, element.textShadow, scale)
            y += lineStep
        }

        val iconSize = 16
        val buttonWidth = 8
        val buttonHeight = 8
        val textOffsetX = iconSize + 4
        val buttonOffsetX = textOffsetX + 60

        element.entries.forEach { entry ->
            // Icon
            ctx.drawItemIcon(entry.iconKey, baseX, y)

            // Text: "name current/target"
            val text =
                buildString {
                    append(entry.displayName)
                    append(" ")
                    append(entry.currentCount)
                    if (entry.targetCount > 0) {
                        append("/")
                        append(entry.targetCount)
                    }
                }
            ctx.drawScaledText(text, baseX + textOffsetX, y + 4, element.textColor, element.textShadow, scale)

            // +/- buttons; actual click handling is done via hit regions
            val plusX = baseX + buttonOffsetX
            val minusX = plusX + buttonWidth + 2

            ctx.fillRect(plusX, y, buttonWidth, buttonHeight, 0xAA00FF00.toInt())
            ctx.drawText("+", plusX + 1, y, 0xFF000000.toInt(), shadow = false)

            ctx.fillRect(minusX, y, buttonWidth, buttonHeight, 0xAAFF0000.toInt())
            ctx.drawText("-", minusX + 1, y, 0xFF000000.toInt(), shadow = false)

            regions +=
                GuiHitRegion(
                    elementId = element.id,
                    kind = GuiHitRegion.Kind.ITEM_TRACKER_INCREMENT,
                    x = plusX,
                    y = y,
                    width = buttonWidth,
                    height = buttonHeight,
                    payload = entry.entryId,
                )

            regions +=
                GuiHitRegion(
                    elementId = element.id,
                    kind = GuiHitRegion.Kind.ITEM_TRACKER_DECREMENT,
                    x = minusX,
                    y = y,
                    width = buttonWidth,
                    height = buttonHeight,
                    payload = entry.entryId,
                )

            y += iconSize + element.rowSpacing
        }
    }
}

/**
 * Library-level interaction handler which consumes click events and applies
 * the appropriate layout mutations via GuiLayoutManager.
 */
object GuiInteractionHandler {
    /**
     * Handle a mouse click at the given screen-space coordinates.
     *
     * @param x Screen-space X coordinate.
     * @param y Screen-space Y coordinate.
     * @param snapshot The interaction snapshot from the most recent render.
     * @return true if the click was consumed by the GUI library.
     */
    fun handleClick(
        x: Int,
        y: Int,
        snapshot: GuiInteractionSnapshot
    ): Boolean {
        val region = snapshot.hitRegions.firstOrNull { it.contains(x, y) } ?: return false

        return when (region.kind) {
            GuiHitRegion.Kind.ITEM_TRACKER_INCREMENT -> {
                val entryId = region.payload ?: return false
                GuiLayoutManager.updateTrackerCounts(region.elementId, entryId, delta = 1)
                true
            }
            GuiHitRegion.Kind.ITEM_TRACKER_DECREMENT -> {
                val entryId = region.payload ?: return false
                GuiLayoutManager.updateTrackerCounts(region.elementId, entryId, delta = -1)
                true
            }
        }
    }
}
