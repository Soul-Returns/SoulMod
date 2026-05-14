package com.soulreturns.gui.lib

/**
 * Convenience API for feature modules to work with the GUI layout library
 * without needing to manipulate GuiLayout directly.
 *
 * **Element id is identity.** The upsert helpers below treat the [GuiElementId] as the unique
 * key, regardless of element subtype. If the saved layout contains an element with the same
 * id but a different runtime type — e.g. a stale [TextBlockElement] left over from a previous
 * mod version that's been replaced by a [TrackerOverlayElement] — it is dropped, not preserved.
 * Without that rule, a type swap would leave a ghost element of the old type rendering forever.
 */
object GuiLayoutApi {
    /**
     * Create or update a simple on-screen text block.
     *
     * Behavior:
     * - If a [TextBlockElement] with the given [id] exists, its position and scale are
     *   preserved; only its enabled flag, title, lines, and color are updated.
     * - If an element of a **different** type with the same id exists, it is dropped and
     *   replaced by a fresh [TextBlockElement] at the default position.
     * - Otherwise a new [TextBlockElement] is created at the default position.
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
            if (element.id == id) {
                // Same id → either the matching-type element to preserve, or a stale element
                // of a different type to drop. Either way, do not keep it in [others].
                if (element is TextBlockElement) existing = element
                continue
            }
            others += element
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

    /**
     * Create or update the layout shell for a tracker overlay (see
     * [com.soulreturns.gui.lib.tracker.TrackerOverlay]).
     *
     * Only positioning fields are persisted in `gui_layout.json` — the overlay descriptor
     * itself lives in [com.soulreturns.gui.lib.tracker.TrackerOverlayRegistry] and UI state
     * (active tab / sort / limit) lives in `tracker_settings.json`. Re-calling this with the
     * same id preserves the user's positioning; only the [enabled] flag is updated. Stale
     * elements of a different type sharing this id are dropped — see the class-level note on
     * element id as identity.
     */
    @JvmStatic
    fun updateTrackerOverlay(
        id: GuiElementId,
        enabled: Boolean = true,
        defaultAnchorX: Double = 0.02,
        defaultAnchorY: Double = 0.5,
        defaultOffsetX: Int = 0,
        defaultOffsetY: Int = 0,
        defaultScale: Float = 1.0f,
    ) {
        val current = GuiLayoutManager.getLayout()
        var existing: TrackerOverlayElement? = null
        val others = mutableListOf<GuiElement>()
        for (element in current.elements) {
            if (element.id == id) {
                if (element is TrackerOverlayElement) existing = element
                continue
            }
            others += element
        }
        val updated =
            existing?.copy(enabled = enabled)
                ?: TrackerOverlayElement(
                    id = id,
                    enabled = enabled,
                    anchorX = defaultAnchorX,
                    anchorY = defaultAnchorY,
                    offsetX = defaultOffsetX,
                    offsetY = defaultOffsetY,
                    scale = defaultScale,
                )
        others += updated
        GuiLayoutManager.setLayout(GuiLayout(others))
    }
}
