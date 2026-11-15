package com.soulreturns.features.pets

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.soulreturns.config.gui.render.RoundRectRenderState
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f

/**
 * The level-up glow of `mocks/pet-hud/mock.css` (`ph-glow`): `box-shadow: 0 0 0 1.5px rc, 0 0 10px 1px rc`
 * around the rounded card, outside it only. One SDF quad (`core/pet_glow.fsh`, vertex shader shared with
 * `core/round_rect`) the card's size plus [SPREAD] on every side; the color's alpha carries the animation.
 */
object PetGlow {
    /** Quad growth past the card: covers the 10 px blur plus its 1 px spread. */
    const val SPREAD = 12

    /** ROUND_SMALL's radius, the card's corners. */
    private const val RADIUS = 4f

    private val PIPELINE: RenderPipeline by lazy {
        RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("soul", "pipeline/pet_glow"))
                .withVertexShader(Identifier.fromNamespaceAndPath("soul", "core/round_rect"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("soul", "core/pet_glow"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withShaderDefine("RADIUS", RADIUS)
                .withShaderDefine("SPREAD", SPREAD.toFloat())
                .withShaderDefine("RING", 1.5f)
                .withShaderDefine("BLUR", 10f)
                .withShaderDefine("BLUR_SPREAD", 1f)
                .build(),
        )
    }

    /** Glow around a card of [w] × [h] at the origin, in [argb] (alpha = intensity). */
    fun draw(g: GuiGraphicsExtractor, w: Int, h: Int, argb: Int) {
        if ((argb ushr 24) == 0) return
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(PIPELINE, TextureSetup.noTexture(), Matrix3x2f(g.pose()), -SPREAD, -SPREAD, w + SPREAD, h + SPREAD, argb, null),
        )
    }
}
