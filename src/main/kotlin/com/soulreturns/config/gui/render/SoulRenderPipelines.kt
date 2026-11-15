package com.soulreturns.config.gui.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * Custom Blaze3D render pipelines for Soul UI. Backend-agnostic (compiles for OpenGL + Vulkan).
 *
 * All variants share one SDF shader (`core/round_rect`) and differ only by shader defines, so each
 * (radius / corner-mask / shadow) combination is a per-pipeline token — the GUI batch has no
 * per-element uniform slot, so tokens are the in-batch way to vary rounding. Each is based on vanilla
 * [RenderPipelines.GUI_SNIPPET] (standard bind groups + translucent blend + QUADS), overriding the
 * shaders + vertex format (POSITION_TEX_COLOR — the SDF needs a UV channel).
 *
 * Radii / shadow spread are in PHYSICAL pixels (size recovered via `fwidth`). Corner mask bits:
 * 1=top-left, 2=top-right, 4=bottom-right, 8=bottom-left (15 = all).
 */
object SoulRenderPipelines {
    // Legacy radii: radiusSmall = 4, radiusMedium = 6.
    val ROUND_SMALL: RenderPipeline = round("round_small", radius = 4f)
    val ROUND_MEDIUM: RenderPipeline = round("round_medium", radius = 6f)
    val ROUND_LARGE: RenderPipeline = round("round_large", radius = 10f)
    val ROUND_PILL: RenderPipeline = round("round_pill", pill = true)

    /** Top corners only — nav strip on a rounded card (radiusMedium). */
    val ROUND_TOP: RenderPipeline = round("round_top", radius = 6f, corners = 0b0011)

    /** Bottom corners only — footer / sidebar-bottom (radiusMedium). */
    val ROUND_BOTTOM: RenderPipeline = round("round_bottom", radius = 6f, corners = 0b1100)

    /** Bottom-left corner only — sidebar (legacy CornerRounding.BottomLeft). */
    val ROUND_BL: RenderPipeline = round("round_bl", radius = 6f, corners = 0b1000)

    /** Bottom-right corner only — body footer (meets the card's bottom-right corner). */
    val ROUND_BR: RenderPipeline = round("round_br", radius = 6f, corners = 0b0100)

    /**
     * Soft outer glow / shadow. Draw behind the content, expanded by ~[spread] all around; the SDF
     * fades over [spread] px outside the (inset) shape. Small spread + an accent color = a thin glow;
     * larger spread + black + a downward offset = a drop shadow. Radius matches the card (12).
     */
    val ROUND_GLOW: RenderPipeline = round("round_glow", radius = 12f, shadow = true, spread = 2f)

    /**
     * MSDF text pipeline — samples the Inter MSDF atlas (Sampler0) for crisp glyphs at any scale.
     * Based on GUI_TEXTURED_SNIPPET (POSITION_TEX_COLOR + standard UBOs + SAMPLER0 + translucent),
     * swapping in the MSDF shaders.
     */
    val MSDF_TEXT: RenderPipeline =
        RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("soul", "pipeline/msdf_text"))
                .withVertexShader(Identifier.fromNamespaceAndPath("soul", "core/msdf_text"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("soul", "core/msdf_text"))
                .build(),
        )

    /** [MSDF_TEXT] with a soft edge (the glyph's whole distance band), stacked at offsets for glows. */
    val MSDF_SOFT: RenderPipeline =
        RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("soul", "pipeline/msdf_soft"))
                .withVertexShader(Identifier.fromNamespaceAndPath("soul", "core/msdf_text"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("soul", "core/msdf_text"))
                .withShaderDefine("SOFT")
                .build(),
        )

    private fun round(
        name: String,
        radius: Float = 0f,
        pill: Boolean = false,
        corners: Int = 15,
        shadow: Boolean = false,
        spread: Float = 0f,
    ): RenderPipeline {
        var b =
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("soul", "pipeline/$name"))
                .withVertexShader(Identifier.fromNamespaceAndPath("soul", "core/round_rect"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("soul", "core/round_rect"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
        b =
            if (pill) {
                b.withShaderDefine("PILL")
            } else {
                b.withShaderDefine("RADIUS", radius).withShaderDefine("CORNERS", corners)
            }
        if (shadow) b = b.withShaderDefine("SHADOW").withShaderDefine("SPREAD", spread)
        return RenderPipelines.register(b.build())
    }
}
