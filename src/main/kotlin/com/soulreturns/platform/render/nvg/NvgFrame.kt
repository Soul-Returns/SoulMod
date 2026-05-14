// Adapted from Odin (github.com/odtheking/Odin) — BSD 3-Clause License.
// Original backend by Aton; design by Stivais.
package com.soulreturns.platform.render.nvg

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/**
 * Entry point for issuing NanoVG draws from inside Minecraft's render cycle.
 *
 * NanoVG is a raw OpenGL library; Minecraft 1.21.11's `RenderDevice` enforces command-encoder
 * semantics and no longer exposes legacy `bindWrite`-style FBO binding for the main render
 * target. The only way to make NVG output reach the visible swapchain is to **queue a
 * Picture-in-Picture state**: Mojang allocates an offscreen color attachment, invokes our
 * [NvgPipRenderer.renderToTexture] with the right FBO bound, then composites the texture
 * into the main GUI scene at the state's screen rectangle.
 *
 * Call sites:
 *  - From a HUD render hook with a `GuiGraphics`: [submit] with explicit screen-rect bounds,
 *    or [submitFullScreen] to cover the whole logical viewport.
 *  - The block draws in **NVG-local pixel coords** (top-left origin at `(0, 0)`, extending to
 *    the rect's `(width, height)`). To paint at a screen coord, do the math: NVG-local =
 *    screen - rect-origin.
 */
object NvgFrame {
    /**
     * Submit a NanoVG draw bounded to the screen rectangle `(x, y, x+w, y+h)`. Mojang allocates
     * an offscreen PIP texture matching that size; [block] draws into it via the [NvgRenderer]
     * primitives. Coordinates inside [block] are relative to the rectangle's top-left.
     */
    fun submit(
        context: GuiGraphics,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        block: () -> Unit,
    ) {
        if (w <= 0 || h <= 0) return
        val scissor = context.scissorStack.peek()
        val state = NvgPipState(x, y, w, h, null, scissor, block)
        context.guiRenderState.submitPicturesInPictureState(state)
    }

    /**
     * Convenience [submit] covering the full logical (GUI-scaled) viewport. NVG-local coords
     * inside [block] match `GuiGraphics`'s coord system (top-left origin), so layout math
     * carried over from other Soul HUDs translates directly.
     */
    fun submitFullScreen(
        context: GuiGraphics,
        block: () -> Unit,
    ) {
        val window = Minecraft.getInstance().window
        submit(context, 0, 0, window.guiScaledWidth, window.guiScaledHeight, block)
    }
}
