// Adapted from Odin (github.com/odtheking/Odin) — BSD 3-Clause License.
// Original backend by Aton; design by Stivais.
package com.soulreturns.platform.render.nvg

import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState

/**
 * Picture-in-Picture render state carrying a NanoVG draw callback.
 *
 * Submitted to `GuiGraphics.guiRenderState.submitPicturesInPictureState(...)` via
 * [NvgFrame.submit]; [NvgPipRenderer.renderToTexture] later invokes [renderContent] inside
 * the PIP framebuffer that Mojang has set up.
 *
 * The screen rectangle `(x, y, x+width, y+height)` defines where the NVG output gets
 * composited on the main scene. Inside [renderContent], NanoVG coordinates run from
 * `(0, 0)` at the top-left of the rectangle to `(width, height)` at the bottom-right.
 */
class NvgPipState(
    private val x: Int,
    private val y: Int,
    private val width: Int,
    private val height: Int,
    private val cachedBounds: ScreenRectangle?,
    private val scissor: ScreenRectangle?,
    val renderContent: () -> Unit,
) : PictureInPictureRenderState {
    override fun x0(): Int = x

    override fun y0(): Int = y

    override fun x1(): Int = x + width

    override fun y1(): Int = y + height

    override fun scale(): Float = 1f

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle = cachedBounds ?: ScreenRectangle(x, y, width, height)

    val contentWidth: Int get() = width

    val contentHeight: Int get() = height
}
