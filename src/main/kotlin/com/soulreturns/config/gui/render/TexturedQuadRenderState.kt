package com.soulreturns.config.gui.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2fc

/**
 * A single POSITION_TEX_COLOR quad sampling an arbitrary texture (via [textureSetup]) — used for
 * things the SDF/MSDF states don't cover, e.g. player-head markers on the dungeon map. Rotation is
 * baked into [pose] by the caller (`g.pose().rotate(...)` before submit), so heads can face the
 * player's heading. `color` tints (use 0xFFFFFFFF for untinted); a chroma tint ([Chroma]) uses the pipeline's chroma
 * variant (vanilla `GUI_TEXTURED` has one), else the hue at the quad's center.
 */
class TexturedQuadRenderState(
    private val pipeline: RenderPipeline,
    private val textureSetup: TextureSetup,
    private val pose: Matrix3x2fc,
    private val x0: Float,
    private val y0: Float,
    private val x1: Float,
    private val y1: Float,
    private val u0: Float,
    private val v0: Float,
    private val u1: Float,
    private val v1: Float,
    private val color: Int,
    private val bnds: ScreenRectangle?,
) : GuiElementRenderState {
    private val chromaPipeline: RenderPipeline? = if (Chroma.isChroma(color)) SoulRenderPipelines.chroma(pipeline) else null

    override fun buildVertices(vertexConsumer: VertexConsumer) {
        if (chromaPipeline != null) {
            val slope = Chroma.slope()
            val phase = Chroma.phase()
            Chroma.vertex(vertexConsumer, pose, x0, y0, slope, phase).setUv(u0, v0).setColor(color)
            Chroma.vertex(vertexConsumer, pose, x0, y1, slope, phase).setUv(u0, v1).setColor(color)
            Chroma.vertex(vertexConsumer, pose, x1, y1, slope, phase).setUv(u1, v1).setColor(color)
            Chroma.vertex(vertexConsumer, pose, x1, y0, slope, phase).setUv(u1, v0).setColor(color)
            return
        }
        val c = Chroma.flatten(color, pose, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f)
        vertexConsumer.addVertexWith2DPose(pose, x0, y0).setUv(u0, v0).setColor(c)
        vertexConsumer.addVertexWith2DPose(pose, x0, y1).setUv(u0, v1).setColor(c)
        vertexConsumer.addVertexWith2DPose(pose, x1, y1).setUv(u1, v1).setColor(c)
        vertexConsumer.addVertexWith2DPose(pose, x1, y0).setUv(u1, v0).setColor(c)
    }

    override fun pipeline(): RenderPipeline = chromaPipeline ?: pipeline

    override fun textureSetup(): TextureSetup = textureSetup

    override fun scissorArea(): ScreenRectangle? = null

    override fun bounds(): ScreenRectangle? = bnds
}
