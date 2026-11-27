package com.soulreturns.config.gui.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2fc
import kotlin.math.ceil

/**
 * A custom GUI element that draws a quad with the SDF [SoulRenderPipelines.ROUND_RECT] pipeline.
 * Modeled on vanilla `ColoredRectangleRenderState`, but emits POSITION_TEX_COLOR vertices with
 * UV = quad-local [0,1] so the fragment shader can compute the rounded-rect SDF.
 *
 * Submit via `guiGraphicsExtractor.guiRenderState.addGuiElement(this)`. A chroma [color] ([Chroma]) draws with the
 * pipeline's chroma variant, or flattened at the quad's center when the pipeline has none.
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
    /** The chroma variant, when [color] is chroma and [pipeline] has one. */
    private val chromaPipeline: RenderPipeline? = if (Chroma.isChroma(color)) SoulRenderPipelines.chroma(pipeline) else null

    private val bounds: ScreenRectangle? =
        run {
            val b = ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose)
            scissor?.intersection(b) ?: b
        }

    override fun buildVertices(vertexConsumer: VertexConsumer) {
        // The quad's edges go to whole physical pixels, where the rasterizer put a plain fill's edges too (pixel
        // centers inside the quad), so straight sides stay crisp and the shader's centered edge fade (half a pixel
        // either side of the true edge) lands exactly on them; corners are then cut from the same box.
        var ax0 = x0.toFloat()
        var ay0 = y0.toFloat()
        var ax1 = x1.toFloat()
        var ay1 = y1.toFloat()
        if (pose.m01() == 0f && pose.m10() == 0f && pose.m00() > 0f && pose.m11() > 0f) {
            val gs = Minecraft.getInstance().window.guiScale.toFloat()
            val sx = pose.m00() * gs
            val sy = pose.m11() * gs
            val tx = pose.m20() * gs
            val ty = pose.m21() * gs
            val px0 = snap(x0 * sx + tx)
            val py0 = snap(y0 * sy + ty)
            // A hairline never collapses to nothing.
            val px1 = maxOf(snap(x1 * sx + tx), if (x1 > x0) px0 + 1f else px0)
            val py1 = maxOf(snap(y1 * sy + ty), if (y1 > y0) py0 + 1f else py0)
            ax0 = (px0 - tx) / sx
            ay0 = (py0 - ty) / sy
            ax1 = (px1 - tx) / sx
            ay1 = (py1 - ty) / sy
        }
        // UV carries SIGNED composable-unit coordinates relative to the quad center, so the SDF works
        // in composable units — the corner radius (a shader define) then scales with the UI like the
        // legacy NanoVG renderer, instead of being a fixed physical-pixel size. Each corner's
        // abs(UV) equals the composable half-size, which the vertex shader forwards as a flat varying.
        val hw = (ax1 - ax0) * 0.5f
        val hh = (ay1 - ay0) * 0.5f
        if (chromaPipeline != null) {
            val slope = Chroma.slope()
            val phase = Chroma.phase()
            Chroma.vertex(vertexConsumer, pose, ax0, ay0, slope, phase).setUv(-hw, -hh).setColor(color)
            Chroma.vertex(vertexConsumer, pose, ax0, ay1, slope, phase).setUv(-hw, hh).setColor(color)
            Chroma.vertex(vertexConsumer, pose, ax1, ay1, slope, phase).setUv(hw, hh).setColor(color)
            Chroma.vertex(vertexConsumer, pose, ax1, ay0, slope, phase).setUv(hw, -hh).setColor(color)
            return
        }
        val c = Chroma.flatten(color, pose, (ax0 + ax1) * 0.5f, (ay0 + ay1) * 0.5f)
        vertexConsumer.addVertexWith2DPose(pose, ax0, ay0).setUv(-hw, -hh).setColor(c)
        vertexConsumer.addVertexWith2DPose(pose, ax0, ay1).setUv(-hw, hh).setColor(c)
        vertexConsumer.addVertexWith2DPose(pose, ax1, ay1).setUv(hw, hh).setColor(c)
        vertexConsumer.addVertexWith2DPose(pose, ax1, ay0).setUv(hw, -hh).setColor(c)
    }

    /** The pixel boundary the rasterizer's pixel-center rule rounds a physical edge [v] to (ties go left/up). */
    private fun snap(v: Float): Float = ceil(v - 0.5f)

    override fun pipeline(): RenderPipeline = chromaPipeline ?: pipeline

    override fun textureSetup(): TextureSetup = textureSetup

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle? = bounds
}
