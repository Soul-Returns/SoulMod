package com.soulreturns.gui.lib

/**
 * Convenience API for feature modules to work with the GUI layout library
 * without needing to manipulate GuiLayout directly.
 */
object GuiLayoutApi {
    /**
     * Create or update a simple on-screen text block.
     *
     * Behavior:
     * - If no TextBlockElement with the given [id] exists, a new one is
     *   created using the provided default anchor/scale values.
     * - If one exists, its position and scale are preserved; only its
     *   enabled flag, title, lines, and color are updated.
     *
     * This is intended to make simple HUD features (like the Legion counter)
     * easy to implement with a single call per tick.
     */
    @JvmStatic
    fun updateTextBlock(
        id: GuiElementId,
        title: String? = null,
        lines: List<String> = emptyList(),
        color: Int = 0xFFFFFFFF.toInt(),
        enabled: Boolean = true,
        // Default layout values used only when the element is first created
        defaultAnchorX: Double = 0.02,
        defaultAnchorY: Double = 0.02,
        defaultOffsetX: Int = 0,
        defaultOffsetY: Int = 0,
        defaultScale: Float = 1.0f,
        defaultTextShadow: Boolean = true,
    ) {
        val current = GuiLayoutManager.getLayout()

        var existing: TextBlockElement? = null
        val others = mutableListOf<GuiElement>()
        for (element in current.elements) {
            if (element is TextBlockElement && element.id == id) {
                existing = element
            } else {
                // All elements in the layout are non-null by construction; we
                // simply keep everything else as-is.
                others += element
            }
        }

        val updated =
            if (existing != null) {
                existing.copy(
                    enabled = enabled,
                    // Only override title/lines when non-null/non-empty so user
                    // edits to layout (position/scale) are preserved cleanly.
                    title = title ?: existing.title,
                    lines = if (lines.isNotEmpty()) lines else existing.lines,
                    color = color,
                )
            } else {
                TextBlockElement(
                    id = id,
                    enabled = enabled,
                    anchorX = defaultAnchorX,
                    anchorY = defaultAnchorY,
                    offsetX = defaultOffsetX,
                    offsetY = defaultOffsetY,
                    scale = defaultScale,
                    textShadow = defaultTextShadow,
                    title = title,
                    lines = lines,
                    color = color,
                )
            }

        others += updated
        GuiLayoutManager.setLayout(GuiLayout(others))
    }
}
