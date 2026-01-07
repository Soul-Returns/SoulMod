package com.soulreturns.gui

import com.soulreturns.gui.lib.EditState
import com.soulreturns.gui.lib.GuiEditSession
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.gui.lib.GuiRenderer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * Edit GUI screen opened via "/soul gui". Allows moving and scaling GUI
 * elements defined by the GUI library.
 */
class GuiEditScreen : Screen(Text.literal("Edit GUI")) {

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
    private class EditHitTestContext(private val client: MinecraftClient) : com.soulreturns.gui.lib.GuiRenderContext {
        override val screenWidth: Int
            get() = client.window.scaledWidth

        override val screenHeight: Int
            get() = client.window.scaledHeight

        override fun drawText(text: String, x: Int, y: Int, color: Int, shadow: Boolean) {
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

        override fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int) {
            // no-op for hit testing
        }

        override fun drawItemIcon(iconKey: String, x: Int, y: Int) {
            // no-op for hit testing
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Draw the in-game background behind our editor UI so the world remains
        // visible with Minecraft's standard slight blur, but without the
        // previous semi-transparent blue overlay.
        renderInGameBackground(context)

        val client = MinecraftClient.getInstance()
        val layout = GuiLayoutManager.getLayout()
        val guiCtx = MinecraftGuiRenderContext(context, client)

        // Precompute rough bounds for each element so we can draw edit boxes
        // and support hit testing.
        val textRenderer = client.textRenderer
        val bounds = mutableListOf<ElementBounds>()
        for (element in layout.elements) {
            if (!element.enabled) continue
            val baseX = (element.anchorX * width).toInt() + element.offsetX
            val baseY = (element.anchorY * height).toInt() + element.offsetY

            when (element) {
                is com.soulreturns.gui.lib.TextBlockElement -> {
                    val lines = buildList {
                        element.title?.let { add(it) }
                        addAll(element.lines)
                    }
                    if (lines.isEmpty()) continue
                    val scale = element.scale.coerceAtLeast(0.25f)
                    val lineHeight = ((textRenderer.fontHeight + 2) * scale).toInt().coerceAtLeast(4)
                    var maxWidth = 0
                    for (line in lines) {
                        val w = (textRenderer.getWidth(line) * scale).toInt()
                        if (w > maxWidth) maxWidth = w
                    }
                    val totalHeight = lines.size * lineHeight
                    bounds += ElementBounds(
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
                    val lineHeight = ((textRenderer.fontHeight + 4) * scale).toInt().coerceAtLeast(4)
                    val approxWidth = (160 * scale).toInt()
                    val totalHeight = rows * lineHeight
                    bounds += ElementBounds(
                        id = element.id,
                        x = baseX - 4,
                        y = baseY - 4,
                        width = approxWidth,
                        height = totalHeight + 8,
                    )
                }
            }
        }
        elementBounds = bounds

        // Highlight hovered & selected elements.
        val hoveredId = findHitElement(mouseX, mouseY)
        for (b in elementBounds) {
            val color = when {
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

    //? if >=1.21.10 {
    override fun mouseClicked(click: net.minecraft.client.gui.Click, doubled: Boolean): Boolean {
        val client = MinecraftClient.getInstance()
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

    override fun mouseDragged(click: net.minecraft.client.gui.Click, offsetX: Double, offsetY: Double): Boolean {
        val client = MinecraftClient.getInstance()
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

    override fun mouseReleased(click: net.minecraft.client.gui.Click): Boolean {
        if (editState.isDragging) {
            editState = GuiEditSession.endDrag(editState)
            return true
        }
        return super.mouseReleased(click)
    }
    //?} else {
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // Legacy signature stub for older versions; not used on 1.21.10+
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        return super.mouseReleased(mouseX, mouseY, button)
    }*/
    //?}

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (verticalAmount == 0.0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)

        val client = MinecraftClient.getInstance()
        val hitId = findHitElement(mouseX.toInt(), mouseY.toInt())
            ?: editState.selectedElementId
            ?: return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)

        // Treat the hovered element as selected for scaling purposes.
        editState = editState.copy(selectedElementId = hitId)
        GuiEditSession.adjustScale(editState, verticalAmount)
        return true
    }

    override fun close() {
        // Persist layout changes when leaving edit mode
        GuiLayoutManager.save()
        super.close()
    }

    private fun drawResetButton(context: DrawContext, mouseX: Int, mouseY: Int) {
        val textRenderer = MinecraftClient.getInstance().textRenderer
        val padding = 6
        val buttonHeight = 18
        val label = "Reset HUD"
        val labelWidth = textRenderer.getWidth(label)
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
        val textY = y + (buttonHeight - textRenderer.fontHeight) / 2
        context.drawText(textRenderer, label, textX, textY, 0xFFFFFFFF.toInt(), false)
    }

    private fun isOverResetButton(mouseX: Int, mouseY: Int): Boolean {
        val b = resetButtonBounds ?: return false
        return mouseX >= b.x && mouseX <= b.x + b.width && mouseY >= b.y && mouseY <= b.y + b.height
    }

    private fun resetLayoutToDefaults() {
        // Clear any current selection/dragging state and reset layout.
        editState = EditState()
        elementBounds = emptyList()
        GuiLayoutManager.resetToDefaults()
    }

    private fun findHitElement(mouseX: Int, mouseY: Int): String? {
        return elementBounds.lastOrNull { b ->
            mouseX >= b.x && mouseX <= b.x + b.width &&
                mouseY >= b.y && mouseY <= b.y + b.height
        }?.id
    }
}
