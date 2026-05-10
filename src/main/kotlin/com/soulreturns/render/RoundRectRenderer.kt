// Adapted from Odin (github.com/odtheking/Odin) — BSD 3-Clause License, Copyright (c) odtheking
package com.soulreturns.render

import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.DynamicUniformStorage
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState
import com.mojang.blaze3d.vertex.Tesselator
import net.minecraft.client.renderer.MultiBufferSource
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import org.joml.Matrix3x2f
import org.joml.Matrix4f
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f
import java.util.OptionalInt
import kotlin.math.roundToInt

class RoundRectRenderer(vertexConsumers: MultiBufferSource.BufferSource)
    : PictureInPictureRenderer<RoundRectRenderer.State>(vertexConsumers) {

    override fun getRenderStateClass(): Class<State> = State::class.java

    override fun getTextureLabel(): String = "soul:round_rect"

    // Center the 2D quad in the PIP texture (instead of the default entity-style bottom anchor).
    override fun getTranslateY(textureH: Int, scale: Int): Float = textureH / 2f

    override fun renderToTexture(state: State, matrixStack: PoseStack) {
        val w = (state.x1() - state.x0()).toFloat()
        val h = (state.y1() - state.y0()).toFloat()

        // Build a quad centered at the origin in GUI-unit model space.
        val builder = Tesselator.getInstance()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        builder.addVertex(-w / 2f, -h / 2f, 0f).setColor(state.topLeftColor)
        builder.addVertex(-w / 2f,  h / 2f, 0f).setColor(state.bottomLeftColor)
        builder.addVertex( w / 2f,  h / 2f, 0f).setColor(state.bottomRightColor)
        builder.addVertex( w / 2f, -h / 2f, 0f).setColor(state.topRightColor)
        val mesh = builder.buildOrThrow()

        val modelView = matrixStack.last().pose()
        val dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
            modelView, Vector4f(1f, 1f, 1f, 1f), Vector3f(), Matrix4f()
        )

        val uniformBuffer = uniformStorage.writeUniform(DynamicUniformStorage.DynamicUniform { buffer ->
            Std140Builder.intoBuffer(buffer)
                .putVec4(0f, 0f, w, h)   // center at origin, full size
                .putVec4(state.topLeftRadius, state.topRightRadius, state.bottomRightRadius, state.bottomLeftRadius)
                .putVec4(state.outlineRed, state.outlineGreen, state.outlineBlue, state.outlineAlpha)
                .putVec4(state.outlineWidth, 0f, 0f, 0f)
        })

        mesh.use {
            val vertexBuf = SoulRenderPipelines.ROUND_RECT.getVertexFormat()
                .uploadImmediateVertexBuffer(mesh.vertexBuffer())
            val params = mesh.drawState()
            val indexStorage = RenderSystem.getSequentialBuffer(params.mode())
            val indexBuf = indexStorage.getBuffer(params.indexCount())

            val colorTarget = RenderSystem.outputColorTextureOverride
                ?: Minecraft.getInstance().mainRenderTarget.colorTextureView
                ?: return@use

            RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass({ "Soul Rounded Rectangle" }, colorTarget, OptionalInt.empty())
                .use { pass ->
                    pass.setPipeline(SoulRenderPipelines.ROUND_RECT)
                    RenderSystem.bindDefaultUniforms(pass)
                    pass.setUniform("DynamicTransforms", dynamicTransforms)
                    pass.setUniform("u", uniformBuffer)
                    pass.setVertexBuffer(0, vertexBuf)
                    pass.setIndexBuffer(indexBuf, indexStorage.type())
                    pass.drawIndexed(0, 0, params.indexCount(), 1)
                }
        }
    }

    class State(
        private val x: Int,
        private val y: Int,
        private val width: Int,
        private val height: Int,
        val topLeftColor: Int,
        val topRightColor: Int,
        val bottomRightColor: Int,
        val bottomLeftColor: Int,
        val topLeftRadius: Float,
        val topRightRadius: Float,
        val bottomRightRadius: Float,
        val bottomLeftRadius: Float,
        val outlineColor: Int,
        val outlineWidth: Float,
        private val scissor: ScreenRectangle?,
        private val cachedBounds: ScreenRectangle?
    ) : PictureInPictureRenderState {

        val outlineRed   = (outlineColor shr 16 and 0xFF) / 255f
        val outlineGreen = (outlineColor shr 8  and 0xFF) / 255f
        val outlineBlue  = (outlineColor        and 0xFF) / 255f
        val outlineAlpha = (outlineColor shr 24 and 0xFF) / 255f

        override fun x0(): Int = x
        override fun y0(): Int = y
        override fun x1(): Int = x + width
        override fun y1(): Int = y + height
        override fun scale(): Float = 1f
        override fun scissorArea(): ScreenRectangle? = scissor
        override fun bounds(): ScreenRectangle = cachedBounds ?: ScreenRectangle(x, y, width, height)
    }

    companion object {
        private val uniformStorage = DynamicUniformStorage<DynamicUniformStorage.DynamicUniform>(
            "Soul Rounded Rectangle UBO",
            Std140SizeCalculator().putVec4().putVec4().putVec4().putVec4().get(),
            16
        )

        @JvmStatic
        fun clear() = uniformStorage.endFrame()

        fun submit(
            context: GuiGraphics,
            x0: Int, y0: Int, x1: Int, y1: Int,
            topLeftColor: Int, topRightColor: Int, bottomRightColor: Int, bottomLeftColor: Int,
            topLeftRadius: Float, topRightRadius: Float, bottomRightRadius: Float, bottomLeftRadius: Float,
            outlineColor: Int, outlineWidth: Float
        ) {
            val scissor: ScreenRectangle? = context.scissorStack.peek()
            val pose = Matrix3x2f(context.pose())

            val p0 = pose.transformPosition(Vector2f(x0.toFloat(), y0.toFloat()))
            val p1 = pose.transformPosition(Vector2f(x1.toFloat(), y1.toFloat()))

            val screenLeft = minOf(p0.x, p1.x).roundToInt()
            val screenTop  = minOf(p0.y, p1.y).roundToInt()
            val screenW    = maxOf(p0.x, p1.x).roundToInt() - screenLeft
            val screenH    = maxOf(p0.y, p1.y).roundToInt() - screenTop

            val poseScale  = pose.transformDirection(Vector2f(1f, 0f)).length()

            val bounds = PictureInPictureRenderState.getBounds(
                screenLeft, screenTop, screenLeft + screenW, screenTop + screenH, scissor
            )

            context.guiRenderState.submitPicturesInPictureState(
                State(
                    screenLeft, screenTop, screenW, screenH,
                    topLeftColor, topRightColor, bottomRightColor, bottomLeftColor,
                    topLeftRadius * poseScale, topRightRadius * poseScale,
                    bottomRightRadius * poseScale, bottomLeftRadius * poseScale,
                    outlineColor, outlineWidth * poseScale,
                    scissor, bounds
                )
            )
        }
    }
}
