package com.soulreturns.gui

import com.soulreturns.gui.lib.EditState
import com.soulreturns.gui.lib.GuiEditSession
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.gui.lib.GuiRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Edit GUI screen opened via "/soul gui". Allows moving and scaling GUI
 * elements defined by the GUI library.
 */
class GuiEditScreen : Screen(Component.literal("Edit GUI")) {
    private var editState: EditState = EditState()

    private data class ElementBounds(
        val id: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private data class ButtonBounds(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private var elementBounds: List<ElementBounds> = emptyList()
    private var resetButtonBounds: ButtonBounds? = null

    /**
     * Lightweight context used for drag updates, where we only
     * need access to the screen dimensions and not actual rendering.
     */
    private class EditHitTestContext(private val client: Minecraft) : com.soulreturns.gui.lib.GuiRenderContext {
        override val screenWidth: Int
            get() = client.window.guiScaledWidth

        override val screenHeight: Int
            get() = client.window.guiScaledHeight

        override fun drawText(
            text: String,
            x: Int,
            y: Int,
            color: Int,
            shadow: Boolean
        ) {
            // no-op for hit testing
        }

        override fun drawScaledText(
            text: String,
            x: Int,
            y: Int,
            color: Int,
            shadow: Boolean,
            scale: Float,
        ) {
            // no-op for hit testing
        }

        override fun fillRect(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            color: Int
        ) {
            // no-op for hit testing
        }

        override fun fillRoundedRect(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            color: Int,
            radius: Float,
        ) {
            // no-op for hit testing
        }

        override fun textWidth(text: String): Int = client.font.width(text)

        override val textLineHeight: Int
            get() = client.font.lineHeight

        override fun pushScissor(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            // no-op for hit testing
        }

        override fun popScissor() {
            // no-op for hit testing
        }

        override fun drawItemIcon(
            iconKey: String,
            x: Int,
            y: Int
        ) {
            // no-op for hit testing
        }
    }

    override fun render(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        delta: Float
    ) {
        // Draw the in-game background behind our editor UI so the world remains
        // visible with Minecraft's standard slight blur, but without the
        // previous semi-transparent blue overlay.
        renderTransparentBackground(context)

        // Re-render Soul UI HUDs on top of the blur. The `Gui.render` TAIL hook already
        // painted them, but Minecraft 1.21's screen-open blur pass runs over the main
        // framebuffer including any previously-composited NanoVG PIPs — so they end up
        // blurred. Re-dispatching here puts them back on top, crisp, so the user can see
        // what they're positioning.
        com.soulreturns.ui.runtime.SoulHud.dispatchAll(context)

        val client = Minecraft.getInstance()
        val layout = GuiLayoutManager.getLayout()
        val guiCtx = MinecraftGuiRenderContext(context, client)

        // Precompute rough bounds for each element so we can draw edit boxes
        // and support hit testing.
        val textRenderer = client.font
        val bounds = mutableListOf<ElementBounds>()
        for (element in layout.elements) {
            if (!element.enabled) continue
            val baseX = (element.anchorX * width).toInt() + element.offsetX
            val baseY = (element.anchorY * height).toInt() + element.offsetY

            when (element) {
                is com.soulreturns.gui.lib.TextBlockElement -> {
                    val lines =
                        buildList {
                            element.title?.let { add(it) }
                            addAll(element.lines)
                        }
                    if (lines.isEmpty()) continue
                    val scale = element.scale.coerceAtLeast(0.25f)
                    val lineHeight = ((textRenderer.lineHeight + 2) * scale).toInt().coerceAtLeast(4)
                    var maxWidth = 0
                    for (line in lines) {
                        val w = (textRenderer.width(line) * scale).toInt()
                        if (w > maxWidth) maxWidth = w
                    }
                    val totalHeight = lines.size * lineHeight
                    bounds +=
                        ElementBounds(
                            id = element.id,
                            x = baseX - 4,
                            y = baseY - 4,
                            width = maxWidth + 8,
                            height = totalHeight + 8,
                        )
                }
                is com.soulreturns.gui.lib.ItemTrackerElement -> {
                    val rows = element.entries.size + if (element.title != null) 1 else 0
                    if (rows == 0) continue
                    val scale = element.scale.coerceAtLeast(0.25f)
                    val lineHeight = ((textRenderer.lineHeight + 4) * scale).toInt().coerceAtLeast(4)
                    val approxWidth = (160 * scale).toInt()
                    val totalHeight = rows * lineHeight
                    bounds +=
                        ElementBounds(
                            id = element.id,
                            x = baseX - 4,
                            y = baseY - 4,
                            width = approxWidth,
                            height = totalHeight + 8,
                        )
                }
                is com.soulreturns.gui.lib.TrackerOverlayElement -> {
                    // Approximate the panel's bounding box so /soul gui can drag it. The
                    // TrackerOverlayRenderer uses scale-relative sizing similar to these constants.
                    val scale = element.scale.coerceAtLeast(0.25f)
                    val approxWidth = (200 * scale).toInt()
                    val approxHeight = (160 * scale).toInt()
                    bounds +=
                        ElementBounds(
                            id = element.id,
                            x = baseX - 4,
                            y = baseY - 4,
                            width = approxWidth + 8,
                            height = approxHeight + 8,
                        )
                }
                is com.soulreturns.gui.lib.SoulHudElement -> {
                    // Soul UI framework HUDs declare their max bounds at registration time —
                    // ask the registry. Effective on-screen size includes the per-element
                    // scale + global scale + optional GUI-scale compensation, so compute the
                    // same effective scale the render path uses.
                    val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(element.id)
                    val effectiveScale = com.soulreturns.ui.runtime.SoulHud.effectiveScaleFor(element.scale)
                    val approxWidth = ((entry?.width ?: 200) * effectiveScale).toInt()
                    val approxHeight = ((entry?.height ?: 100) * effectiveScale).toInt()
                    bounds +=
                        ElementBounds(
                            id = element.id,
                            x = baseX - 4,
                            y = baseY - 4,
                            width = approxWidth + 8,
                            height = approxHeight + 8,
                        )
                }
            }
        }
        elementBounds = bounds

        // Highlight hovered & selected elements.
        val hoveredId = findHitElement(mouseX, mouseY)
        for (b in elementBounds) {
            val color =
                when {
                    b.id == editState.selectedElementId -> 0x40FFFFFF.toInt() // selected
                    b.id == hoveredId -> 0x2000FFFF.toInt() // hovered
                    else -> 0x20000000.toInt()
                }
            context.fill(b.x, b.y, b.x + b.width, b.y + b.height, color)
        }

        // Render HUD content on top of the edit boxes.
        GuiRenderer.renderHud(layout, guiCtx)

        // Draw a simple "Reset" button in the top-right corner to restore the
        // layout to its default positions and scales.
        drawResetButton(context, mouseX, mouseY)

        // Small label showing which element is selected.
        editState.selectedElementId?.let { selectedId ->
            guiCtx.drawText("Selected: $selectedId", 4, 4, 0xFFFFFF00.toInt(), shadow = true)
        }

        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseClicked(
        click: net.minecraft.client.input.MouseButtonEvent,
        doubled: Boolean
    ): Boolean {
        val client = Minecraft.getInstance()
        val mouseXInt = click.x.toInt()
        val mouseYInt = click.y.toInt()

        // First, check if the reset button was clicked; if so, clear the layout
        // back to defaults and skip element selection/dragging.
        if (isOverResetButton(mouseXInt, mouseYInt)) {
            resetLayoutToDefaults()
            return true
        }

        // Treat any mouse button as a selection trigger in edit mode. This
        // avoids having to depend on the exact Click.button() enum string.
        val hitId = findHitElement(mouseXInt, mouseYInt)
        if (hitId != null) {
            val guiCtx = EditHitTestContext(client)
            editState = GuiEditSession.beginDrag(hitId, guiCtx, mouseXInt, mouseYInt)
        } else {
            editState = EditState()
        }
        return true
    }

    override fun mouseDragged(
        click: net.minecraft.client.input.MouseButtonEvent,
        offsetX: Double,
        offsetY: Double
    ): Boolean {
        val client = Minecraft.getInstance()
        val guiCtx = EditHitTestContext(client)
        val mouseXInt = click.x.toInt()
        val mouseYInt = click.y.toInt()

        // If a drag starts without a prior click being registered here, we
        // still want to begin dragging the element under the cursor.
        if (!editState.isDragging) {
            val hitId = findHitElement(mouseXInt, mouseYInt)
            if (hitId != null) {
                editState = GuiEditSession.beginDrag(hitId, guiCtx, mouseXInt, mouseYInt)
            }
        }

        if (editState.isDragging) {
            editState = GuiEditSession.updateDrag(editState, guiCtx, mouseXInt, mouseYInt)
            return true
        }
        return super.mouseDragged(click, offsetX, offsetY)
    }

    override fun mouseReleased(click: net.minecraft.client.input.MouseButtonEvent): Boolean {
        if (editState.isDragging) {
            editState = GuiEditSession.endDrag(editState)
            return true
        }
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double
    ): Boolean {
        if (verticalAmount == 0.0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)

        val client = Minecraft.getInstance()
        val hitId =
            findHitElement(mouseX.toInt(), mouseY.toInt())
                ?: editState.selectedElementId
                ?: return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)

        // Treat the hovered element as selected for scaling purposes.
        editState = editState.copy(selectedElementId = hitId)
        GuiEditSession.adjustScale(editState, verticalAmount)
        return true
    }

    override fun onClose() {
        // Persist layout changes when leaving edit mode
        GuiLayoutManager.save()
        super.onClose()
    }

    private fun drawResetButton(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int
    ) {
        val textRenderer = Minecraft.getInstance().font
        val padding = 6
        val buttonHeight = 18
        val label = "Reset HUD"
        val labelWidth = textRenderer.width(label)
        val buttonWidth = labelWidth + padding * 2
        val x = width - buttonWidth - padding
        val y = padding

        val hovered = mouseX >= x && mouseX <= x + buttonWidth && mouseY >= y && mouseY <= y + buttonHeight
        val backgroundColor = if (hovered) 0x80000000.toInt() else 0x60000000.toInt()
        val borderColor = if (hovered) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt()

        // Store bounds for click handling
        resetButtonBounds = ButtonBounds(x, y, buttonWidth, buttonHeight)

        // Border
        context.fill(x - 1, y - 1, x + buttonWidth + 1, y + buttonHeight + 1, borderColor)
        // Background
        context.fill(x, y, x + buttonWidth, y + buttonHeight, backgroundColor)

        val textX = x + padding
        val textY = y + (buttonHeight - textRenderer.lineHeight) / 2
        context.drawString(textRenderer, label, textX, textY, 0xFFFFFFFF.toInt(), false)
    }

    private fun isOverResetButton(
        mouseX: Int,
        mouseY: Int
    ): Boolean {
        val b = resetButtonBounds ?: return false
        return mouseX >= b.x && mouseX <= b.x + b.width && mouseY >= b.y && mouseY <= b.y + b.height
    }

    private fun resetLayoutToDefaults() {
        // Clear any current selection/dragging state and reset layout.
        editState = EditState()
        elementBounds = emptyList()
        GuiLayoutManager.resetToDefaults()
    }

    private fun findHitElement(
        mouseX: Int,
        mouseY: Int
    ): String? {
        return elementBounds.lastOrNull { b ->
            mouseX >= b.x && mouseX <= b.x + b.width &&
                mouseY >= b.y && mouseY <= b.y + b.height
        }?.id
    }
}
