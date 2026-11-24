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

    /** Rounded outlines (the SDF's edge band, inside the quad): stroke width / corner radius in the name. */
    val STROKE_1_R4: RenderPipeline = round("stroke_1_r4", radius = 4f, stroke = 1f)
    val STROKE_2_R4: RenderPipeline = round("stroke_2_r4", radius = 4f, stroke = 2f)
    val STROKE_2_R7: RenderPipeline = round("stroke_2_r7", radius = 7f, stroke = 2f)
    val STROKE_2_R10: RenderPipeline = round("stroke_2_r10", radius = 10f, stroke = 2f)
    val STROKE_3_R8: RenderPipeline = round("stroke_3_r8", radius = 8f, stroke = 3f)
    val STROKE_1_PILL: RenderPipeline = round("stroke_1_pill", pill = true, stroke = 1f)

    /** [STROKE_2_R7] dashed (7 px period). */
    val DASH_2_R7: RenderPipeline = round("dash_2_r7", radius = 7f, stroke = 2f, dash = 7f)

    /**
     * The pipelines of one terminal-cell shape: [fill] the cell, [under] a fill 1 px inside it with the ring's inner
     * radius + 1 (it sits under [ring], concentric), [ring] a 2 px outline inside the cell's edge, [dash] the same ring
     * dashed (period about 7 px, stretched to fit, one dash centered on every corner arc). All use `AACENTER` edges.
     */
    class CellStyle internal constructor(radius: Float, name: String) {
        val fill: RenderPipeline = round("cell_fill_$name", radius = radius, center = true)
        val under: RenderPipeline = round("cell_under_$name", radius = radius - 1f, center = true)
        val ring: RenderPipeline = round("cell_ring_$name", radius = radius, stroke = 2f, center = true)
        val dash: RenderPipeline = round("cell_dash_$name", radius = radius, stroke = 2f, dashp = 7f, center = true)
    }

    private val CELL_R10 = CellStyle(10f, "r10")
    private val CELL_R14 = CellStyle(14f, "r14")
    private val CELL_R17 = CellStyle(17f, "r17")

    /** The cell shape for a cell of [size] Soul px: the corner radius is about 0.29 of the size (14 on 48), from fixed variants. */
    fun cellStyle(size: Int): CellStyle = if (size < 44) CELL_R10 else if (size < 54) CELL_R14 else CELL_R17

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
        stroke: Float = 0f,
        dash: Float = 0f,
        dashp: Float = 0f,
        center: Boolean = false,
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
        if (stroke > 0f) b = b.withShaderDefine("STROKE", stroke)
        if (dash > 0f) b = b.withShaderDefine("DASH", dash)
        if (center) b = b.withShaderDefine("AACENTER")
        if (dashp > 0f) b = b.withShaderDefine("DASHP", dashp)
        return RenderPipelines.register(b.build())
    }
}
