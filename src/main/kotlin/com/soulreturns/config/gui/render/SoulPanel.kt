package com.soulreturns.config.gui.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fc
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A filled rounded panel with an optional border ring, concentric by construction: one quad, one pipeline
 * ([SoulRenderPipelines.PANEL], shader `core/panel`). The ring lies inside the box's edge; the fill is the same shape
 * inset by the ring's width (same arc centers, radius minus the width), so no fill shows outside or through the ring
 * and no ring color shows under the fill. Radius, corners, ring width and colors are per draw (vertex data), so panels
 * of any radius batch together. Chroma colors ([Chroma]) work for the fill and both ring colors. The edge is the same
 * one-pixel centered fade as `core/round_rect`.
 *
 * The ring can be a two-color gradient: the colors blend along the ring's centerline (by arc length, clockwise from the
 * top center: color 1 there, color 2 halfway round) and the whole pattern turns with [GradientClock]'s phase.
 */
object SoulPanel {
    /** The radii of [SoulRenderPipelines.ROUND_SMALL], `ROUND_MEDIUM` and `ROUND_LARGE`. */
    const val SMALL = 4f
    const val MEDIUM = 6f
    const val LARGE = 10f

    /** A radius of half the shorter side ([SoulRenderPipelines.ROUND_PILL]). */
    const val PILL = -1f

    /** Corner bits for [draw]'s `corners`, as round_rect's `CORNERS`: 1 top-left, 2 top-right, 4 bottom-right, 8 bottom-left. */
    const val ALL = 15
    const val LEFT = 0b1001
    const val BOTTOM = 0b1100
    const val BOTTOM_LEFT = 0b1000

    /** Largest radius and ring width the vertex data holds (Soul px). */
    const val MAX_RADIUS = 63f
    const val MAX_WIDTH = 31f

    /**
     * The radius of a shape [inset] inside one of [radius], sharing its corner: concentric (same arc center), so
     * `radius - inset`, never below 0; a pill stays a pill. Nested rounding goes through this (rule://render-layer).
     */
    fun inner(radius: Float, inset: Float): Float = if (radius < 0f) PILL else (radius - inset).coerceAtLeast(0f)

    /** The radius of a shape [outset] around one of [radius] (a halo, an outer ring): concentric, `radius + outset`. */
    fun outer(radius: Float, outset: Float): Float = if (radius < 0f) PILL else radius + outset

    /**
     * [top] composited over [bottom] (source-over, straight alpha): the color a translucent fill showed when it was
     * stacked on its border rect. Floating panels that relied on that stack to hide what lies under them (popups,
     * tooltips, Move GUI panels) pass `over(fill, border)` as their fill, so they keep the look they had.
     */
    fun over(top: Int, bottom: Int): Int {
        val ta = (top ushr 24) / 255f
        val ba = (bottom ushr 24) / 255f * (1f - ta)
        val a = ta + ba
        if (a <= 0f) return 0
        fun ch(shift: Int) = ((((top ushr shift) and 0xFF) * ta + ((bottom ushr shift) and 0xFF) * ba) / a).roundToInt()
        return ((a * 255f).roundToInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Vertex format of [SoulRenderPipelines.PANEL]; `core/panel.vsh` documents each attribute. */
    val FORMAT: VertexFormat =
        VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV1", GpuFormat.RG16_SINT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
            .addAttribute("LineWidth", GpuFormat.R32_FLOAT)
            .build()

    /**
     * Draws a panel over ([x0], [y0])-([x1], [y1]): [fill] inside, and a ring [ringWidth] wide of [ring] along the
     * inside of the edge when [ring] is not fully transparent. [radius] is the outer corner radius ([PILL] for a pill);
     * only the [corners] bits are rounded.
     */
    fun draw(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        radius: Float,
        fill: Int,
        ring: Int = 0,
        ringWidth: Float = 1f,
        scissor: ScreenRectangle? = null,
        corners: Int = ALL,
    ) {
        if ((fill ushr 24) == 0 && (ring ushr 24) == 0) return
        val width = if ((ring ushr 24) == 0) 0f else ringWidth
        g.guiRenderState.addGuiElement(PanelRenderState(Matrix3x2f(g.pose()), x0, y0, x1, y1, radius, corners, fill, ring, ring, width, -1f, scissor))
    }

    /**
     * [draw] with a two-color ring: [ring] and [ring2] blend round the ring, turned by [phase] (0..1 turns, from a
     * [GradientClock]).
     */
    fun drawGradient(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        radius: Float,
        fill: Int,
        ring: Int,
        ring2: Int,
        phase: Float,
        ringWidth: Float,
        scissor: ScreenRectangle? = null,
    ) {
        g.guiRenderState.addGuiElement(PanelRenderState(Matrix3x2f(g.pose()), x0, y0, x1, y1, radius, ALL, fill, ring, ring2, ringWidth, phase - floor(phase), scissor))
    }

    /**
     * The part of the [radius]-rounded box ([x0], [y0])-([x1], [y1]) that lies inside ([sx0], [sy0])-([sx1], [sy1]),
     * filled with [color]: an accent stripe or bar along a card's edge drawn as a slice of the card's own shape, so its
     * ends follow the card's corners instead of overlapping them.
     */
    fun slice(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        radius: Float,
        sx0: Int,
        sy0: Int,
        sx1: Int,
        sy1: Int,
        color: Int,
        scissor: ScreenRectangle? = null,
        corners: Int = ALL,
    ) {
        if ((color ushr 24) == 0 || sx1 <= sx0 || sy1 <= sy0) return
        g.guiRenderState.addGuiElement(
            PanelRenderState(Matrix3x2f(g.pose()), x0, y0, x1, y1, radius, corners, color, 0, 0, 0f, -1f, scissor, intArrayOf(sx0, sy0, sx1, sy1)),
        )
    }
}

/**
 * The phase of a moving gradient: advanced by the time since the last call, so it moves at the same pace at any frame
 * rate, and a changed period changes the pace without a jump.
 */
class GradientClock {
    private var phase = 0.0
    private var lastNs = 0L

    /** Turns so far (0..1) at one turn per [periodSeconds]. */
    fun phase(periodSeconds: Double): Float {
        val now = System.nanoTime()
        if (lastNs != 0L) phase = (phase + (now - lastNs) / 1e9 / periodSeconds.coerceAtLeast(0.1)) % 1.0
        lastNs = now
        return phase.toFloat()
    }
}

/** One [SoulPanel] quad. */
private class PanelRenderState(
    private val pose: Matrix3x2fc,
    private val x0: Int,
    private val y0: Int,
    private val x1: Int,
    private val y1: Int,
    private val radius: Float,
    private val corners: Int,
    private val fill: Int,
    private val ring: Int,
    private val ring2: Int,
    private val ringWidth: Float,
    private val phase: Float,
    private val scissor: ScreenRectangle?,
    /** A [SoulPanel.slice]'s box (x0, y0, x1, y1): only this part of the shape is drawn. */
    private val clip: IntArray? = null,
) : GuiElementRenderState {
    private val bounds: ScreenRectangle? =
        run {
            val b = if (clip == null) ScreenRectangle(x0, y0, x1 - x0, y1 - y0) else ScreenRectangle(clip[0], clip[1], clip[2] - clip[0], clip[3] - clip[1])
            val t = b.transformMaxBounds(pose)
            scissor?.intersection(t) ?: t
        }

    override fun buildVertices(vc: VertexConsumer) {
        val q = SNAPPED
        snapQuad(pose, x0, y0, x1, y1, q)
        val hw = (q[2] - q[0]) * 0.5f
        val hh = (q[3] - q[1]) * 0.5f
        val chroma = Chroma.isChroma(fill) || (ringWidth > 0f && (Chroma.isChroma(ring) || (phase >= 0f && Chroma.isChroma(ring2))))
        val slope = if (chroma) Chroma.slope() else 0f
        val chromaPhase = if (chroma) Chroma.phase() else 0f
        // Whole steps of 0.5 px (radius) and 0.25 px (width) and the corner bits, each a byte over 127 (core/panel.vsh).
        val r = if (radius < 0f) 127 else (radius.coerceIn(0f, SoulPanel.MAX_RADIUS) * 2f).roundToInt()
        val w = (ringWidth.coerceIn(0f, SoulPanel.MAX_WIDTH) * 4f).roundToInt()
        val nx = (r + 0.5f) / 127f
        val ny = (w + 0.5f) / 127f
        val nz = ((corners and 15) + 0.5f) / 127f
        val uv = UVS
        if (w == 0) {
            // No ring: UV1 and UV2 carry the clip box instead (core/panel.vsh), relative to the shape's center, open on
            // every side that is not a slice's.
            val cx = (q[0] + q[2]) * 0.5f
            val cy = (q[1] + q[3]) * 0.5f
            uv[0] = -OPEN; uv[1] = -OPEN; uv[2] = OPEN; uv[3] = OPEN
            if (clip != null) {
                val s = CLIPPED
                snapQuad(pose, clip[0], clip[1], clip[2], clip[3], s)
                if (clip[0] > x0) uv[0] = eighths(s[0] - cx)
                if (clip[1] > y0) uv[1] = eighths(s[1] - cy)
                if (clip[2] < x1) uv[2] = eighths(s[2] - cx)
                if (clip[3] < y1) uv[3] = eighths(s[3] - cy)
            }
        } else {
            uv[0] = rg(ring); uv[1] = ba(ring); uv[2] = rg(ring2); uv[3] = ba(ring2)
        }
        vertex(vc, q[0], q[1], -hw, -hh, slope, chromaPhase, nx, ny, nz, uv)
        vertex(vc, q[0], q[3], -hw, hh, slope, chromaPhase, nx, ny, nz, uv)
        vertex(vc, q[2], q[3], hw, hh, slope, chromaPhase, nx, ny, nz, uv)
        vertex(vc, q[2], q[1], hw, -hh, slope, chromaPhase, nx, ny, nz, uv)
    }

    private fun vertex(vc: VertexConsumer, x: Float, y: Float, u: Float, v: Float, slope: Float, chromaPhase: Float, nx: Float, ny: Float, nz: Float, uv: IntArray) {
        Chroma.vertex(vc, pose, x, y, slope, chromaPhase)
            .setUv(u, v)
            .setColor(fill)
            .setUv1(uv[0], uv[1])
            .setUv2(uv[2], uv[3])
            .setNormal(nx, ny, nz)
            .setLineWidth(phase)
    }

    /** A clip coordinate in eighth units, as a short: off by at most 1/16 unit, well inside the half pixel the shader's hard edge allows. */
    private fun eighths(v: Float): Int = (v * 8f).roundToInt().coerceIn(-OPEN, OPEN)

    /** Red and green as one short, then blue and alpha: RGBA8 packed into the two RG16_SINT attributes. */
    private fun rg(argb: Int): Int = (argb ushr 8) and 0xFFFF

    private fun ba(argb: Int): Int = ((argb and 0xFF) shl 8) or (argb ushr 24)

    override fun pipeline(): RenderPipeline = SoulRenderPipelines.PANEL

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle? = bounds

    private companion object {
        /** [snapQuad]'s outputs and the UV1/UV2 shorts: vertices are built on the render thread only. */
        val SNAPPED = FloatArray(4)
        val CLIPPED = FloatArray(4)
        val UVS = IntArray(4)

        /** An open clip side (eighth units, 4096 units out): far outside any panel. */
        const val OPEN = 32767
    }
}
