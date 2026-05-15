package com.soulreturns.ui.runtime

import com.soulreturns.platform.render.nvg.NvgFrame
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.input.SoulInput
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Abstract Minecraft [Screen] subclass that hosts a Soul UI composition.
 *
 * Subclass it, override [Content] with your composable tree, and you get:
 *  - Full-screen NanoVG render every frame, sized to `(width, height)` (GUI-scaled).
 *  - Mouse click / release / scroll routed automatically into [SoulInput] so `Modifier
 *    .clickable(...)` / `Slider` drag / `ScrollableList` wheel all work without per-screen
 *    plumbing.
 *  - Vanilla dimmed background drawn underneath (override [renderDimBackground] to disable).
 *
 * Example:
 * ```
 * class FishingTrackerFullScreen : SoulScreen(Component.literal("Fishing Tracker")) {
 *     @SoulComposable override fun Content() {
 *         Surface(modifier = SoulModifier.Empty.fillMaxSize()) {
 *             Column(gap = 8f) { ... }
 *         }
 *     }
 * }
 * ```
 *
 * The screen renders the entire window through one PIP submission, so mouse coords arrive
 * in screen space (origin = top-left). Position composables explicitly via padding +
 * alignment + `Modifier.fillMaxSize`.
 */
abstract class SoulScreen(title: Component) : Screen(title) {
    /**
     * Compose the screen's content. Invoked from inside [SoulComposer.build] every frame —
     * call any `@SoulComposable` function freely. The lambda must produce exactly one root
     * composable.
     */
    @SoulComposable
    abstract fun Content()

    /**
     * Whether this screen wants Minecraft's vanilla blurred background painted **behind** the
     * Soul UI content. Subclasses override + return true to opt in (default `false`).
     *
     * **Caveats** — 1.21.11's `GuiRenderState` enforces one blur per frame. If Minecraft's
     * `LoadingOverlay` is active (e.g. during the initial resource-pack reload, or during a
     * resource-pack swap), it has already consumed the frame's blur slot and our call would
     * crash. The [render] dispatch below checks `Minecraft.overlay == null` first and skips
     * the blur in that case, also wrapped in try/catch in case another mod or vanilla layer
     * adds its own blur path. When blur is skipped the screen's own [Content]-painted dim
     * (e.g. a `Column.background(0xA0000000)`) carries the visual; combined with blur the
     * result is moderately darker but still readable.
     */
    protected open fun blurBackground(): Boolean = false

    override fun render(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        if (blurBackground() && Minecraft.getInstance().overlay == null) {
            try {
                renderBackground(context, mouseX, mouseY, partialTick)
            } catch (_: IllegalStateException) {
                // Another component already requested blur this frame — silently skip.
                // Visuals fall back to whatever dim layer [Content] paints itself.
            }
        }
        NvgFrame.submit(context, x = 0, y = 0, w = width, h = height) {
            val root =
                SoulComposer.create().build {
                    Content()
                }
            root.draw(
                0f,
                0f,
                SoulConstraints(maxWidth = width.toFloat(), maxHeight = height.toFloat()),
            )
            // Tooltip overlay — painted last so it draws on top of everything in the same
            // NVG frame. Uses the tooltip regions recorded during the main draw above.
            SoulInput.findHoveredTooltip()?.let { paintTooltip(it) }
        }
        super.render(context, mouseX, mouseY, partialTick)
    }

    /**
     * Paint a small tooltip box near the cursor with [text]. Sized to fit the text plus
     * padding; positioned just below and to the right of the cursor, clamped so it never
     * extends past the screen's right or bottom edge.
     */
    private fun paintTooltip(text: String) {
        val font = com.soulreturns.ui.theme.SoulTheme.typography.body.font
        val size = com.soulreturns.ui.theme.SoulTheme.typography.body.size
        val padH = 8f
        val padV = 5f
        val textW = com.soulreturns.platform.render.nvg.NvgRenderer.textWidth(text, size, font)
        val boxW = textW + padH * 2f
        val boxH = size + padV * 2f
        val cursorOffsetX = 12f
        val cursorOffsetY = 16f
        val w = width.toFloat()
        val h = height.toFloat()
        // Anchor below-right of the cursor by default; flip to left/above if it would clip.
        var bx = SoulInput.cursorX + cursorOffsetX
        var by = SoulInput.cursorY + cursorOffsetY
        if (bx + boxW > w) bx = (SoulInput.cursorX - cursorOffsetX - boxW).coerceAtLeast(0f)
        if (by + boxH > h) by = (SoulInput.cursorY - cursorOffsetY - boxH).coerceAtLeast(0f)
        com.soulreturns.platform.render.nvg.NvgRenderer.rect(
            bx,
            by,
            boxW,
            boxH,
            com.soulreturns.ui.theme.SoulTheme.colors.panelInset,
            com.soulreturns.ui.theme.SoulTheme.dimens.radiusSmall,
        )
        com.soulreturns.platform.render.nvg.NvgRenderer.hollowRect(
            bx,
            by,
            boxW,
            boxH,
            thickness = 1f,
            color = 0xFF333333.toInt(),
            radius = com.soulreturns.ui.theme.SoulTheme.dimens.radiusSmall,
        )
        com.soulreturns.platform.render.nvg.NvgRenderer.text(
            text,
            bx + padH,
            by + padV,
            size,
            com.soulreturns.ui.theme.SoulTheme.colors.text,
            font,
        )
    }

    override fun mouseClicked(
        click: MouseButtonEvent,
        doubleClick: Boolean,
    ): Boolean {
        SoulInput.queueClick(click.x().toFloat(), click.y().toFloat())
        return super.mouseClicked(click, doubleClick)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        SoulInput.queueRelease()
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        SoulInput.queueScroll(mouseX.toFloat(), mouseY.toFloat(), verticalAmount.toFloat())
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        SoulInput.queueChar(characterEvent.codepoint())
        return super.charTyped(characterEvent)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        val editKey =
            when (keyEvent.key()) {
                org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> SoulInput.EditKey.Backspace
                org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE -> SoulInput.EditKey.Delete
                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> SoulInput.EditKey.Left
                org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> SoulInput.EditKey.Right
                org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> SoulInput.EditKey.Home
                org.lwjgl.glfw.GLFW.GLFW_KEY_END -> SoulInput.EditKey.End
                org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER ->
                    SoulInput.EditKey.Enter
                org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> {
                    // Esc: clear focus first; if no focused element, fall through to super
                    // (which closes the screen via shouldCloseOnEsc).
                    if (SoulInput.focusedKey != null) {
                        SoulInput.setFocus(null)
                        return true
                    }
                    null
                }
                else -> null
            }
        if (editKey != null) {
            SoulInput.queueEditKey(editKey)
            return true
        }
        return super.keyPressed(keyEvent)
    }

    override fun isPauseScreen(): Boolean = false
}
