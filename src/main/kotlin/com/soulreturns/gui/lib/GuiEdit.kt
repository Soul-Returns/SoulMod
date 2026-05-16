package com.soulreturns.gui.lib

/**
 * Represents the current state of an edit session (e.g. "/soul gui").
 */
data class EditState(
    val selectedElementId: GuiElementId? = null,
    val isDragging: Boolean = false,
    val dragStartScreenX: Int = 0,
    val dragStartScreenY: Int = 0,
    val originalAnchorX: Double = 0.0,
    val originalAnchorY: Double = 0.0,
    val originalOffsetX: Int = 0,
    val originalOffsetY: Int = 0,
)

/**
 * Library-side helper that applies edit operations to the layout.
 */
object GuiEditSession {
    /**
     * Hit test elements based on their current layout and return the id of the
     * top-most element under the given coordinates, or null if none.
     *
     * For now this uses a simple bounding box based on text/row estimates.
     * Hosts can refine this later if needed.
     */
    fun hitTestElement(
        layout: GuiLayout,
        ctx: GuiRenderContext,
        x: Int,
        y: Int
    ): GuiElementId? {
        // Simple heuristic: treat each element as a rectangle around its
        // computed base position. This is mainly for selecting an element to
        // move/scale; it does not need pixel-perfect precision.
        return layout.elements.lastOrNull { element ->
            if (!element.enabled) return@lastOrNull false
            val (baseX, baseY) = computeBasePosition(element, ctx)
            val width: Int
            val height: Int
            when (element) {
                is TextBlockElement -> {
                    width = 200
                    height = 20 + element.lines.size * 10
                }
                is ItemTrackerElement -> {
                    width = 200
                    height = 20 + element.entries.size * 18
                }
                is SoulHudElement -> {
                    val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(element.id)
                    val measured = com.soulreturns.ui.runtime.SoulHudRegistry.lastMeasured(element.id)
                    val effectiveScale = com.soulreturns.ui.runtime.SoulHud.effectiveScaleFor(element.scale)
                    val intrinsicW = measured?.width ?: (entry?.width?.toFloat() ?: 200f)
                    val intrinsicH = measured?.height ?: (entry?.height?.toFloat() ?: 64f)
                    width = (intrinsicW * effectiveScale).toInt()
                    height = (intrinsicH * effectiveScale).toInt()
                }
            }
            x >= baseX && x <= baseX + width && y >= baseY && y <= baseY + height
        }?.id
    }

    /**
     * Begin dragging the given element.
     */
    fun beginDrag(
        elementId: GuiElementId,
        ctx: GuiRenderContext,
        mouseX: Int,
        mouseY: Int
    ): EditState {
        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == elementId }
                ?: return EditState()

        return EditState(
            selectedElementId = elementId,
            isDragging = true,
            dragStartScreenX = mouseX,
            dragStartScreenY = mouseY,
            originalAnchorX = element.anchorX,
            originalAnchorY = element.anchorY,
            originalOffsetX = element.offsetX,
            originalOffsetY = element.offsetY,
        )
    }

    /**
     * Update drag: move the element by the mouse delta in **pixels**, applied to
     * `offsetX/Y`. `anchorX/Y` stays at whatever the user chose via the right-click
     * anchor-grid preset (edge or center) — the anchor is the conceptual "where this HUD
     * is glued to the screen edge", and dragging just changes its distance from that
     * edge. The previous behaviour translated drag into `anchorX` changes, which broke
     * for HUDs that had been pinned to an edge (anchor at 0 or 1 with offset filling in
     * the visible position): the drag would clamp the anchor at the edge fraction and
     * the offset stayed fixed, so the HUD couldn't actually move past its current spot.
     */
    fun updateDrag(
        state: EditState,
        ctx: GuiRenderContext,
        mouseX: Int,
        mouseY: Int
    ): EditState {
        val elementId = state.selectedElementId ?: return state
        if (!state.isDragging) return state

        val dx = mouseX - state.dragStartScreenX
        val dy = mouseY - state.dragStartScreenY

        GuiLayoutManager.updateElementPosition(
            id = elementId,
            anchorX = state.originalAnchorX,
            anchorY = state.originalAnchorY,
            offsetX = state.originalOffsetX + dx,
            offsetY = state.originalOffsetY + dy,
        )

        return state
    }

    /**
     * End dragging; returns an updated state with dragging cleared.
     */
    fun endDrag(state: EditState): EditState {
        return state.copy(isDragging = false)
    }

    /**
     * Adjust the scale of the selected element based on scroll wheel input.
     */
    fun adjustScale(
        state: EditState,
        scrollDelta: Double
    ) {
        val elementId = state.selectedElementId ?: return
        val current = GuiLayoutManager.getElements().firstOrNull { it.id == elementId } ?: return
        val factor = 1.0f + (scrollDelta * 0.1f).toFloat()
        val newScale = (current.scale * factor)
        GuiLayoutManager.updateElementScale(elementId, newScale)
    }

    private fun computeBasePosition(
        element: GuiElement,
        ctx: GuiRenderContext
    ): Pair<Int, Int> {
        // SoulHudElements have alignment metadata (Start / Center / End on each axis) that
        // shifts the on-screen origin away from the raw anchor — delegate to SoulHud so the
        // hit-test rectangle lines up with what the user actually sees.
        if (element is SoulHudElement) {
            val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(element.id)
            if (entry != null) {
                val baseX = com.soulreturns.ui.runtime.SoulHud.resolveBaseX(element, entry, ctx.screenWidth)
                val baseY = com.soulreturns.ui.runtime.SoulHud.resolveBaseY(element, entry, ctx.screenHeight)
                return baseX to baseY
            }
        }
        val baseX = (element.anchorX * ctx.screenWidth).toInt() + element.offsetX
        val baseY = (element.anchorY * ctx.screenHeight).toInt() + element.offsetY
        return baseX to baseY
    }
}
