package com.soulreturns.config.gui.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2fc

/**
 * A single GUI element that draws a laid-out run of MSDF glyph quads (one string) with the
 * [SoulRenderPipelines.MSDF_TEXT] pipeline + the Inter atlas texture. Emits POSITION_TEX_COLOR
 * vertices (position + atlas UV + color) per glyph. A chroma [color] ([Chroma]) draws with the pipeline's chroma
 * variant: each glyph quad shows its own window of the screen-space rainbow.
 */
class MsdfTextRenderState(
    private val pipeline: RenderPipeline,
    private val textureSetup: TextureSetup,
    private val pose: Matrix3x2fc,
    private val quads: List<Quad>,
    private val color: Int,
    private val scissor: ScreenRectangle?,
    private val bnds: ScreenRectangle?,
) : GuiElementRenderState {
    /** One glyph: screen rect (x0,y0)-(x1,y1) + atlas UV rect (u0,v0)-(u1,v1). */
    class Quad(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
    )

    /** The chroma variant, when [color] is chroma and [pipeline] has one. */
    private val chromaPipeline: RenderPipeline? = if (Chroma.isChroma(color)) SoulRenderPipelines.chroma(pipeline) else null

    override fun buildVertices(vertexConsumer: VertexConsumer) {
        if (chromaPipeline != null) {
            val slope = Chroma.slope()
            val phase = Chroma.phase()
            for (q in quads) {
                Chroma.vertex(vertexConsumer, pose, q.x0, q.y0, slope, phase).setUv(q.u0, q.v0).setColor(color)
                Chroma.vertex(vertexConsumer, pose, q.x0, q.y1, slope, phase).setUv(q.u0, q.v1).setColor(color)
                Chroma.vertex(vertexConsumer, pose, q.x1, q.y1, slope, phase).setUv(q.u1, q.v1).setColor(color)
                Chroma.vertex(vertexConsumer, pose, q.x1, q.y0, slope, phase).setUv(q.u1, q.v0).setColor(color)
            }
            return
        }
        val c = if (Chroma.isChroma(color) && quads.isNotEmpty()) Chroma.flatten(color, pose, quads[0].x0, quads[0].y0) else color
        for (q in quads) {
            vertexConsumer.addVertexWith2DPose(pose, q.x0, q.y0).setUv(q.u0, q.v0).setColor(c)
            vertexConsumer.addVertexWith2DPose(pose, q.x0, q.y1).setUv(q.u0, q.v1).setColor(c)
            vertexConsumer.addVertexWith2DPose(pose, q.x1, q.y1).setUv(q.u1, q.v1).setColor(c)
            vertexConsumer.addVertexWith2DPose(pose, q.x1, q.y0).setUv(q.u1, q.v0).setColor(c)
        }
    }

    override fun pipeline(): RenderPipeline = chromaPipeline ?: pipeline

    override fun textureSetup(): TextureSetup = textureSetup

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle? = bnds
}
