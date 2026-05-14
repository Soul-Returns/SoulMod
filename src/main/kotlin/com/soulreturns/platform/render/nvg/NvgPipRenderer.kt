// Adapted from Odin (github.com/odtheking/Odin) — BSD 3-Clause License.
// Original backend by Aton; design by Stivais.
package com.soulreturns.platform.render.nvg

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.soulreturns.util.SoulLogger
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.MultiBufferSource
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL33C
import java.util.OptionalInt

/**
 * Picture-in-Picture renderer that paints a NanoVG region into Minecraft's GUI scene.
 *
 * Direct equivalent of Odin's `NVGPIPRenderer.kt:19-39`, adapted for 1.21.11's RenderDevice
 * pipeline (legacy `GlStateManager._glBindFramebuffer` / `GlConst` removed; replaced by
 * `RenderSystem.getDevice().createCommandEncoder().createRenderPass(...)`, mirroring how
 * [com.soulreturns.render.RoundRectRenderer] does it).
 *
 * **Why PIP at all.** At `Gui.render` TAIL, raw GL writes land in `FBO=0` (the OS window
 * backbuffer), which Minecraft's downstream final composite overwrites. The only render slot
 * Mojang lets you paint into reliably is one of the post-process / PIP passes — which is
 * what `submitPicturesInPictureState` queues for.
 *
 * **GL discipline inside `renderToTexture`.** Mojang opens an FBO for the PIP texture before
 * calling us. We open our own [createRenderPass] block to bind that FBO for writing through
 * the new RenderDevice API. NanoVG then issues raw GL draw calls — those land on whichever
 * FBO `createRenderPass` bound, which is the PIP texture. After our render pass closes,
 * Mojang composites the PIP texture into the main scene at the state's screen coordinates.
 *
 * Registered once per session via `SpecialGuiElementRegistry.register { ... }` in
 * `Soul.onInitializeClient`.
 */
class NvgPipRenderer(vertexConsumers: MultiBufferSource.BufferSource) :
    PictureInPictureRenderer<NvgPipState>(vertexConsumers) {
    private val logger = SoulLogger("Soul/NVG")

    @Volatile
    private var loggedFboState: Boolean = false

    override fun getRenderStateClass(): Class<NvgPipState> = NvgPipState::class.java

    override fun getTextureLabel(): String = "soul:nvg"

    // NanoVG manages its own coordinate system inside the texture (top-left origin, no
    // transform from poseStack), so override the parent's default centering translate. The
    // parent class only exposes getTranslateY; X is left at the implicit center (parent's
    // poseStack-based draws would shift accordingly, but our NVG path ignores poseStack).
    override fun getTranslateY(
        textureH: Int,
        scale: Int,
    ): Float = 0f

    override fun renderToTexture(
        state: NvgPipState,
        matrixStack: PoseStack,
    ) {
        val colorTarget =
            RenderSystem.outputColorTextureOverride
                ?: Minecraft.getInstance().mainRenderTarget.colorTextureView
                ?: run {
                    logger.warn("NvgPipRenderer.renderToTexture: no colorTarget available; skipping")
                    return
                }

        RenderSystem.getDevice().createCommandEncoder()
            .createRenderPass({ "Soul NanoVG" }, colorTarget, OptionalInt.empty())
            .use { _ ->
                // Unbind the sampler at texture unit 0. Mojang's render pipeline leaves a
                // sampler object bound there which overrides per-texture parameters set via
                // `glTexParameteri`. NanoVG configures its font atlas + image samplers via
                // texture parameters (no sampler objects), so anything textured (text,
                // images) renders as black/transparent unless we clear the sampler binding.
                // Solid shapes work without this because they don't sample any texture.
                GL33C.glBindSampler(0, 0)

                // Mojang allocates the PIP texture at higher resolution than the requested
                // content rect — typically 2× on standard GUI scale — so the active viewport
                // (set by the render pass) is e.g. 400×176 for a 200×88 state. NanoVG must
                // know that ratio as its DPR so it rasterizes glyphs and stroke widths at
                // the physical resolution; otherwise it draws at 1× and the projection
                // stretches everything (blurry text). Compute the ratio from the actual GL
                // viewport vs the state's content size.
                val viewport = IntArray(4).also { GL11.glGetIntegerv(GL11.GL_VIEWPORT, it) }
                val physicalW = viewport[2].toFloat()
                val physicalH = viewport[3].toFloat()
                val logicalW = state.contentWidth.toFloat()
                val logicalH = state.contentHeight.toFloat()
                val dpr = if (logicalW > 0f) physicalW / logicalW else 1f

                logFboStateOnce(state.contentWidth, state.contentHeight, viewport, dpr)

                NvgRenderer.beginFrame(logicalW, logicalH, dpr)
                try {
                    state.renderContent()
                } finally {
                    NvgRenderer.endFrame()
                }
            }
    }

    private fun logFboStateOnce(
        requestedW: Int,
        requestedH: Int,
        viewport: IntArray,
        dpr: Float,
    ) {
        if (loggedFboState) return
        loggedFboState = true
        val drawFbo = GL11.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)
        val vp = "(${viewport[0]},${viewport[1]},${viewport[2]},${viewport[3]})"
        logger.info(
            "PIP renderToTexture: drawFBO=$drawFbo viewport=$vp requestedSize=($requestedW, $requestedH) dpr=$dpr",
        )
    }
}
