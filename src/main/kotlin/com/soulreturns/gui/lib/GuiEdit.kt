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
            val width = 200
            val height =
                when (element) {
                    is TextBlockElement -> 20 + element.lines.size * 10
                    is ItemTrackerElement -> 20 + element.entries.size * 18
                    is TrackerOverlayElement -> (160 * element.scale.coerceAtLeast(0.25f)).toInt()
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
     * Update drag: compute new anchor/offset based on mouse delta and apply to
     * the selected element via GuiLayoutManager.
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

        // Convert delta in pixels to deltas in normalized anchor space.
        val deltaAnchorX = dx.toDouble() / ctx.screenWidth.toDouble()
        val deltaAnchorY = dy.toDouble() / ctx.screenHeight.toDouble()

        val newAnchorX = (state.originalAnchorX + deltaAnchorX).coerceIn(0.0, 1.0)
        val newAnchorY = (state.originalAnchorY + deltaAnchorY).coerceIn(0.0, 1.0)

        GuiLayoutManager.updateElementPosition(
            id = elementId,
            anchorX = newAnchorX,
            anchorY = newAnchorY,
            offsetX = state.originalOffsetX,
            offsetY = state.originalOffsetY,
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
        val baseX = (element.anchorX * ctx.screenWidth).toInt() + element.offsetX
        val baseY = (element.anchorY * ctx.screenHeight).toInt() + element.offsetY
        return baseX to baseY
    }
}
