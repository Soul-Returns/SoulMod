package com.soulreturns.config.gui.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2fc

/**
 * A custom GUI element that draws a quad with the SDF [SoulRenderPipelines.ROUND_RECT] pipeline.
 * Modeled on vanilla `ColoredRectangleRenderState`, but emits POSITION_TEX_COLOR vertices with
 * UV = quad-local [0,1] so the fragment shader can compute the rounded-rect SDF.
 *
 * Submit via `guiGraphicsExtractor.guiRenderState.addGuiElement(this)`.
 */
class RoundRectRenderState(
    private val pipeline: RenderPipeline,
    private val textureSetup: TextureSetup,
    private val pose: Matrix3x2fc,
    private val x0: Int,
    private val y0: Int,
    private val x1: Int,
    private val y1: Int,
    private val color: Int,
    private val scissor: ScreenRectangle?,
) : GuiElementRenderState {
    private val bounds: ScreenRectangle? =
        run {
            val b = ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose)
            scissor?.intersection(b) ?: b
        }

    override fun buildVertices(vertexConsumer: VertexConsumer) {
        // UV carries SIGNED composable-unit coordinates relative to the quad center, so the SDF works
        // in composable units — the corner radius (a shader define) then scales with the UI like the
        // legacy NanoVG renderer, instead of being a fixed physical-pixel size. Each corner's
        // abs(UV) equals the composable half-size, which the vertex shader forwards as a flat varying.
        val hw = (x1 - x0) * 0.5f
        val hh = (y1 - y0) * 0.5f
        vertexConsumer.addVertexWith2DPose(pose, x0.toFloat(), y0.toFloat()).setUv(-hw, -hh).setColor(color)
        vertexConsumer.addVertexWith2DPose(pose, x0.toFloat(), y1.toFloat()).setUv(-hw, hh).setColor(color)
        vertexConsumer.addVertexWith2DPose(pose, x1.toFloat(), y1.toFloat()).setUv(hw, hh).setColor(color)
        vertexConsumer.addVertexWith2DPose(pose, x1.toFloat(), y0.toFloat()).setUv(hw, -hh).setColor(color)
    }

    override fun pipeline(): RenderPipeline = pipeline

    override fun textureSetup(): TextureSetup = textureSetup

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle? = bounds
}
