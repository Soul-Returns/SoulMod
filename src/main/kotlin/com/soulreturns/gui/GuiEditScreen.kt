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
     * Open context menu state. Non-null while the user has right-clicked a SoulHudElement
     * to bring up the anchor-preset picker. Cleared on selection, outside-click, or escape.
     */
    private data class ContextMenu(
        val elementId: String,
        val originX: Int,
        val originY: Int,
        val itemHeight: Int,
        val width: Int,
    )

    private var contextMenu: ContextMenu? = null

    /**
     * One row in the right-click context menu. Either snaps the element to a corner preset
     * ([anchorPreset] non-null) or runs an arbitrary [action] ([action] non-null). Mutually
     * exclusive — exactly one of the two should be set.
     */
    private data class ContextMenuItem(
        val label: String,
        val anchorPreset: AnchorPreset? = null,
        val action: ((elementId: String) -> Unit)? = null,
    )

    private data class AnchorPreset(
        val anchorX: Double,
        val anchorY: Double,
        val horizontalAnchor: com.soulreturns.gui.lib.HudHorizontalAnchor,
        val verticalAnchor: com.soulreturns.gui.lib.HudVerticalAnchor,
    )

    /**
     * Build the right-click context menu for [elementId]. Built fresh on every read so the
     * toggle labels ("HUD Background: ON/OFF" / "Use Minecraft Font: ON/OFF") reflect the
     * element's current effective state (global gate AND per-HUD override).
     */
    private fun buildContextMenuItems(elementId: String): List<ContextMenuItem> {
        fun preset(
            label: String,
            ax: Double,
            ay: Double,
            h: com.soulreturns.gui.lib.HudHorizontalAnchor,
            v: com.soulreturns.gui.lib.HudVerticalAnchor,
        ) = ContextMenuItem(label, AnchorPreset(ax, ay, h, v), null)

        val hStart = com.soulreturns.gui.lib.HudHorizontalAnchor.Start
        val hCenter = com.soulreturns.gui.lib.HudHorizontalAnchor.Center
        val hEnd = com.soulreturns.gui.lib.HudHorizontalAnchor.End
        val vTop = com.soulreturns.gui.lib.HudVerticalAnchor.Top
        val vCenter = com.soulreturns.gui.lib.HudVerticalAnchor.Center
        val vBottom = com.soulreturns.gui.lib.HudVerticalAnchor.Bottom

        val bgEffective = com.soulreturns.ui.runtime.SoulHud.shouldDrawBackground(elementId)
        val mcFontEffective = com.soulreturns.ui.runtime.SoulHud.shouldUseMinecraftFont(elementId)
        val shadowEffective = com.soulreturns.ui.runtime.SoulHud.shouldDrawTextShadow(elementId)
        val boldEffective = com.soulreturns.ui.runtime.SoulHud.shouldUseBoldFont(elementId)

        return listOf(
            preset("Top Left", 0.0, 0.0, hStart, vTop),
            preset("Top Right", 1.0, 0.0, hEnd, vTop),
            preset("Bottom Left", 0.0, 1.0, hStart, vBottom),
            preset("Bottom Right", 1.0, 1.0, hEnd, vBottom),
            preset("Center", 0.5, 0.5, hCenter, vCenter),
            ContextMenuItem(
                label = "HUD Background: ${if (bgEffective) "ON" else "OFF"}",
                action = { id -> toggleHudBackground(id) },
            ),
            ContextMenuItem(
                label = "Use Minecraft Font: ${if (mcFontEffective) "ON" else "OFF"}",
                action = { id -> toggleUseMinecraftFont(id) },
            ),
            ContextMenuItem(
                label = "Text Shadow: ${if (shadowEffective) "ON" else "OFF"}",
                action = { id -> toggleUseTextShadow(id) },
            ),
            ContextMenuItem(
                label = "Bold Font: ${if (boldEffective) "ON" else "OFF"}",
                action = { id -> toggleUseBoldFont(id) },
            ),
            ContextMenuItem(label = "Settings", action = ::openSettingsFor),
        )
    }

    /**
     * Flip the per-HUD `showBackground` override. Reads the current per-HUD value
     * (defaulting `null` → `true`), inverts it, and writes back the explicit `Boolean`.
     * The global `cfg.general.ui.hudBackground` still has to be on for any value here
     * to actually show a backdrop — when global is off, this just records the preference
     * for when global flips back on.
     */
    private fun toggleHudBackground(elementId: String) {
        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == elementId } as?
                com.soulreturns.gui.lib.SoulHudElement ?: return
        val current = element.showBackground ?: true
        GuiLayoutManager.updateSoulHudShowBackground(elementId, !current)
    }

    /** Per-HUD analog of [toggleHudBackground] for the Minecraft font override. */
    private fun toggleUseMinecraftFont(elementId: String) {
        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == elementId } as?
                com.soulreturns.gui.lib.SoulHudElement ?: return
        val current = element.useMinecraftFont ?: true
        GuiLayoutManager.updateSoulHudUseMinecraftFont(elementId, !current)
    }

    /** Per-HUD analog of [toggleHudBackground] for the text-shadow override. */
    private fun toggleUseTextShadow(elementId: String) {
        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == elementId } as?
                com.soulreturns.gui.lib.SoulHudElement ?: return
        val current = element.useTextShadow ?: true
        GuiLayoutManager.updateSoulHudUseTextShadow(elementId, !current)
    }

    /** Per-HUD analog of [toggleHudBackground] for the bold-font override. */
    private fun toggleUseBoldFont(elementId: String) {
        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == elementId } as?
                com.soulreturns.gui.lib.SoulHudElement ?: return
        val current = element.useBoldFont ?: true
        GuiLayoutManager.updateSoulHudUseBoldFont(elementId, !current)
    }

    /**
     * Open the Soul config screen jumped to the deep-link target the HUD registered. If
     * the HUD didn't supply a target (or its category isn't in the live config), the
     * screen falls back to its first visible category — so the user still lands somewhere
     * useful instead of an empty body.
     */
    private fun openSettingsFor(elementId: String) {
        val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(elementId)
        val screen =
            com.soulreturns.config.gui.SoulConfigScreen(
                initialCategory = entry?.settingsCategory,
                initialSubcategory = entry?.settingsSubcategory,
            )
        Minecraft.getInstance().setScreen(screen)
    }

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
            // SoulHudElements compute their on-screen origin via `SoulHud.resolveBaseX/Y` so
            // anchor alignment (Center/End for top-center/right HUDs) is honored. Other
            // element types still use the raw anchor + offset math — they don't have
            // alignment metadata yet.
            val baseX: Int
            val baseY: Int
            if (element is com.soulreturns.gui.lib.SoulHudElement) {
                val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(element.id)
                if (entry != null) {
                    baseX = com.soulreturns.ui.runtime.SoulHud.resolveBaseX(element, entry, width)
                    baseY = com.soulreturns.ui.runtime.SoulHud.resolveBaseY(element, entry, height)
                } else {
                    baseX = (element.anchorX * width).toInt() + element.offsetX
                    baseY = (element.anchorY * height).toInt() + element.offsetY
                }
            } else {
                baseX = (element.anchorX * width).toInt() + element.offsetX
                baseY = (element.anchorY * height).toInt() + element.offsetY
            }

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
                is com.soulreturns.gui.lib.SoulHudElement -> {
                    // Selection-box geometry tracks the **actual rendered size** of the
                    // composed root from the previous frame — the framework records that
                    // post-draw on `SoulHudRegistry`. Falls back to the registration `(width,
                    // height)` max bounds if the HUD hasn't rendered yet this session (e.g.
                    // first edit before the HUD's gate flips visible).
                    val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(element.id)
                    val measured = com.soulreturns.ui.runtime.SoulHudRegistry.lastMeasured(element.id)
                    val effectiveScale = com.soulreturns.ui.runtime.SoulHud.effectiveScaleFor(element.scale)
                    val intrinsicW = measured?.width ?: (entry?.width?.toFloat() ?: 200f)
                    val intrinsicH = measured?.height ?: (entry?.height?.toFloat() ?: 100f)
                    val approxWidth = (intrinsicW * effectiveScale).toInt()
                    val approxHeight = (intrinsicH * effectiveScale).toInt()
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

        // Anchor corner indicators — small dots showing each SoulHud's pivot pixel so the
        // user can see at a glance which edge the element is pinned to. Drawn after HUD
        // content so the dot reads on top.
        drawAnchorIndicators(context, layout)

        // Draw a simple "Reset" button in the top-right corner to restore the
        // layout to its default positions and scales.
        drawResetButton(context, mouseX, mouseY)

        // Small label showing which element is selected.
        editState.selectedElementId?.let { selectedId ->
            guiCtx.drawText("Selected: $selectedId", 4, 4, 0xFFFFFF00.toInt(), shadow = true)
        }

        // Context menu rendered last so it overlays everything else (HUDs, indicators).
        drawContextMenu(context, mouseX, mouseY)

        super.render(context, mouseX, mouseY, delta)
    }

    /**
     * Draw a 4×4 dot at each enabled SoulHudElement's anchor pixel, plus a 1-px ring to
     * make it pop against bright HUD backgrounds. The anchor pixel is the point where the
     * element's `horizontalAnchor` / `verticalAnchor` edge meets `(anchorX × screenW,
     * anchorY × screenH)` — for `Start/Top` it's the top-left corner, for `Center/Center`
     * the visual center, etc. Helps the user understand why a HUD doesn't move the way
     * they expect when they drag.
     */
    private fun drawAnchorIndicators(
        context: GuiGraphics,
        layout: com.soulreturns.gui.lib.GuiLayout,
    ) {
        for (element in layout.elements) {
            if (!element.enabled) continue
            if (element !is com.soulreturns.gui.lib.SoulHudElement) continue
            val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(element.id) ?: continue
            val baseX = com.soulreturns.ui.runtime.SoulHud.resolveBaseX(element, entry, width)
            val baseY = com.soulreturns.ui.runtime.SoulHud.resolveBaseY(element, entry, height)
            val measured = com.soulreturns.ui.runtime.SoulHudRegistry.lastMeasured(element.id)
            val effectiveScale = com.soulreturns.ui.runtime.SoulHud.effectiveScaleFor(element.scale)
            val w = ((measured?.width ?: entry.width.toFloat()) * effectiveScale).toInt()
            val h = ((measured?.height ?: entry.height.toFloat()) * effectiveScale).toInt()
            val pivotX =
                baseX +
                    when (element.horizontalAnchor) {
                        com.soulreturns.gui.lib.HudHorizontalAnchor.Start -> 0
                        com.soulreturns.gui.lib.HudHorizontalAnchor.Center -> w / 2
                        com.soulreturns.gui.lib.HudHorizontalAnchor.End -> w
                    }
            val pivotY =
                baseY +
                    when (element.verticalAnchor) {
                        com.soulreturns.gui.lib.HudVerticalAnchor.Top -> 0
                        com.soulreturns.gui.lib.HudVerticalAnchor.Center -> h / 2
                        com.soulreturns.gui.lib.HudVerticalAnchor.Bottom -> h
                    }
            // White-bordered yellow dot (4×4 inner, 6×6 outer).
            context.fill(pivotX - 3, pivotY - 3, pivotX + 3, pivotY + 3, 0xFF000000.toInt())
            context.fill(pivotX - 2, pivotY - 2, pivotX + 2, pivotY + 2, 0xFFFFFF00.toInt())
        }
    }

    private fun drawContextMenu(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        val menu = contextMenu ?: return
        val items = buildContextMenuItems(menu.elementId)
        val client = Minecraft.getInstance()
        val textRenderer = client.font
        val padding = 4
        val ih = menu.itemHeight
        val left = menu.originX
        val top = menu.originY
        val right = menu.originX + menu.width
        val bottom = menu.originY + ih * items.size
        val bg = 0xE0202020.toInt()
        val border = 0xFF555555.toInt()
        val hover = 0xFF353535.toInt()
        context.fill(left, top, right, bottom, bg)
        // 1-px border on each side.
        context.fill(left, top, right, top + 1, border)
        context.fill(left, bottom - 1, right, bottom, border)
        context.fill(left, top, left + 1, bottom, border)
        context.fill(right - 1, top, right, bottom, border)

        for ((idx, item) in items.withIndex()) {
            val itemY = top + idx * ih
            val hovered = mouseX in left..(right - 1) && mouseY in itemY..(itemY + ih - 1)
            if (hovered) {
                context.fill(left + 1, itemY, right - 1, itemY + ih, hover)
            }
            // Divider above the first non-preset item — visually separates the corner
            // presets from the toggles + Settings entry. (Triggered when the previous
            // item was a preset and this one isn't.)
            val prev = items.getOrNull(idx - 1)
            if (item.anchorPreset == null && prev?.anchorPreset != null) {
                context.fill(left + 4, itemY, right - 4, itemY + 1, border)
            }
            context.drawString(
                textRenderer,
                item.label,
                left + padding,
                itemY + (ih - 8) / 2,
                0xFFEEEEEE.toInt(),
                true,
            )
        }
    }

    override fun mouseClicked(
        click: net.minecraft.client.input.MouseButtonEvent,
        doubled: Boolean
    ): Boolean {
        val client = Minecraft.getInstance()
        val mouseXInt = click.x.toInt()
        val mouseYInt = click.y.toInt()
        val isRightClick = click.button() == 1

        // Context menu is modal: while open, all clicks land here first.
        contextMenu?.let { menu ->
            val items = buildContextMenuItems(menu.elementId)
            val ih = menu.itemHeight
            val withinX = mouseXInt >= menu.originX && mouseXInt < menu.originX + menu.width
            val withinY = mouseYInt >= menu.originY && mouseYInt < menu.originY + ih * items.size
            if (withinX && withinY) {
                val idx = (mouseYInt - menu.originY) / ih
                val item = items.getOrNull(idx)
                if (item != null) {
                    item.anchorPreset?.let { preset ->
                        GuiLayoutManager.updateSoulHudAnchor(
                            id = menu.elementId,
                            anchorX = preset.anchorX,
                            anchorY = preset.anchorY,
                            horizontalAnchor = preset.horizontalAnchor,
                            verticalAnchor = preset.verticalAnchor,
                        )
                    }
                    item.action?.invoke(menu.elementId)
                }
            }
            contextMenu = null
            return true
        }

        // First, check if the reset button was clicked; if so, clear the layout
        // back to defaults and skip element selection/dragging.
        if (isOverResetButton(mouseXInt, mouseYInt)) {
            resetLayoutToDefaults()
            return true
        }

        // Right-click on a SoulHudElement opens the anchor-preset menu instead of dragging.
        // Other element types don't have alignment metadata yet, so right-click on them
        // falls through to the normal selection path.
        if (isRightClick) {
            val hitId = findHitElement(mouseXInt, mouseYInt)
            val hit = hitId?.let { id -> GuiLayoutManager.getElements().firstOrNull { it.id == id } }
            if (hit is com.soulreturns.gui.lib.SoulHudElement) {
                editState = EditState(selectedElementId = hit.id)
                // Clamp the menu to stay on-screen. Item count comes from a freshly-built
                // list because toggle items can change label width per-element.
                val menuWidth = 160
                val itemHeight = 14
                val totalHeight = itemHeight * buildContextMenuItems(hit.id).size
                val ox = mouseXInt.coerceAtMost(width - menuWidth - 2).coerceAtLeast(2)
                val oy = mouseYInt.coerceAtMost(height - totalHeight - 2).coerceAtLeast(2)
                contextMenu =
                    ContextMenu(
                        elementId = hit.id,
                        originX = ox,
                        originY = oy,
                        itemHeight = itemHeight,
                        width = menuWidth,
                    )
                return true
            }
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
        // Right-button drags + drags while the context menu is up shouldn't move elements.
        if (click.button() != 0) return super.mouseDragged(click, offsetX, offsetY)
        if (contextMenu != null) return super.mouseDragged(click, offsetX, offsetY)

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
