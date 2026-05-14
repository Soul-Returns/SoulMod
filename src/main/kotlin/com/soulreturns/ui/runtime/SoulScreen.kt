package com.soulreturns.ui.runtime

import com.soulreturns.platform.render.nvg.NvgFrame
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.input.SoulInput
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
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
     * Whether to draw the standard Minecraft dimmed background behind our content. Subclasses
     * can override to `false` to paint over a transparent screen (e.g. a full-bleed
     * hand-drawn background).
     */
    protected open fun renderDimBackground(): Boolean = true

    override fun render(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        if (renderDimBackground()) renderBackground(context, mouseX, mouseY, partialTick)
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
        }
        super.render(context, mouseX, mouseY, partialTick)
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

    override fun isPauseScreen(): Boolean = false
}
