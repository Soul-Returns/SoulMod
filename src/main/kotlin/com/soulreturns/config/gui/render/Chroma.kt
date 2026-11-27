package com.soulreturns.config.gui.render

import com.mojang.blaze3d.vertex.VertexConsumer
import com.soulreturns.config.ChromaConfig
import com.soulreturns.config.cfg
import com.soulreturns.ui.SoulUi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fc
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Screen-space chroma: every pixel drawn in a chroma color takes its hue from one diagonal rainbow over the whole
 * window, `hue = fract((x + y) / size - phase)` with x right and y down in physical px, so the bands travel
 * down-right and every chroma text and shape in Soul is a window onto the same moving rainbow
 * (`ChromaConfig.motion` "Across the screen"; "All together" drops the position term: one hue at a time). The
 * direction and the diagonal field follow SkyHanni's default chroma (its shader was a reference; no code taken).
 *
 * **How a color says "chroma"**: [ChromaColor.effective] returns [paint], an ARGB whose RGB is the reserved [MARK]
 * (the alpha stays the player's). Every Soul render state ([RoundRectRenderState], [MsdfTextRenderState],
 * [ShapeRenderState], [TexturedQuadRenderState]) checks [isChroma] and then draws with its pipeline's chroma
 * variant ([SoulRenderPipelines.chroma], the base shader with the `CHROMA` define): the vertex's position z, which
 * GUI drawing leaves at 0, carries the unwrapped hue parameter `(x + y) * slope - phase` of that vertex. It is
 * linear in screen position, so the rasterizer interpolates it exactly and the fragment shader only takes `fract`
 * and maps the hue to RGB. Scaled, moved or scissored elements all land on the same field.
 * Vanilla paths (`g.fill`, the Minecraft font, world rendering) cannot take the mark: use [fill] or [flatten].
 */
object Chroma {
    /** The reserved RGB that marks a chroma color. A plain color that happens to equal it is nudged by one ([plain]). */
    const val MARK: Int = 0x5B1D8E

    /**
     * Soul px per full rainbow along x (and along y): 200 physical px at Soul scale 1.5 (1080p's default), as in the
     * approved mock. In Soul px, not physical px, so the rainbow keeps its size relative to the text and HUDs it
     * runs through at every Soul scale and monitor (4K at Soul 3 shows the same picture as 1080p at 1.5).
     */
    const val SIZE: Float = 400f / 3f

    fun isChroma(argb: Int): Boolean = (argb and 0xFFFFFF) == MARK

    /** The marked color for a chroma color of [alpha] (0..255). */
    fun paint(alpha: Int): Int = ((alpha and 0xFF) shl 24) or MARK

    /** [argb] as a plain color that never reads as chroma. */
    fun plain(argb: Int): Int = if (isChroma(argb)) argb xor 1 else argb

    /** Seconds per hue loop at [speed] (Soul's scale 0.5-20; 6 = 3.3 s). */
    fun cycleSeconds(speed: Float): Double = (20.0 / speed.coerceAtLeast(0.5f)).coerceIn(1.0, 40.0)

    private var phase = 0.0
    private var lastNs = 0L

    /**
     * How far the field has moved, in rainbows (0..1). Advanced by the time since the last call at the current speed,
     * so dragging the speed slider changes the pace without making the bands jump.
     */
    fun phase(): Float {
        val now = System.nanoTime()
        if (lastNs != 0L) phase = (phase + (now - lastNs) / 1e9 / cycleSeconds(cfg.chroma.speed)) % 1.0
        lastNs = now
        return phase.toFloat()
    }

    /** Hue change per GUI px of (x + y): 0 for "All together". */
    fun slope(): Float {
        if (cfg.chroma.motion == ChromaConfig.TOGETHER) return 0f
        val gs = Minecraft.getInstance().window.guiScale.toFloat()
        return gs / (SIZE * SoulUi.scale)
    }

    /** Adds a vertex at ([x], [y]) under [pose] whose z carries the hue parameter for the chroma shaders. */
    fun vertex(vc: VertexConsumer, pose: Matrix3x2fc, x: Float, y: Float, slope: Float, phase: Float): VertexConsumer {
        val gx = pose.m00() * x + pose.m10() * y + pose.m20()
        val gy = pose.m01() * x + pose.m11() * y + pose.m21()
        return vc.addVertex(gx, gy, (gx + gy) * slope - phase)
    }

    /** The hue (0..1) at GUI px ([xGui], [yGui]) this frame. */
    fun hueAt(xGui: Float, yGui: Float): Float {
        val h = (xGui + yGui) * slope() - phase()
        return h - floor(h)
    }

    /** [argb] as a plain color: a chroma color becomes the rainbow's color at GUI px ([xGui], [yGui]). */
    fun flatten(argb: Int, xGui: Float, yGui: Float): Int =
        if (!isChroma(argb)) argb else (argb and 0xFF000000.toInt()) or (rainbow(hueAt(xGui, yGui)) and 0xFFFFFF)

    /** [argb] as a plain color where no screen position exists (world rendering): the hue at the screen's corner. */
    fun flatten(argb: Int): Int = flatten(argb, 0f, 0f)

    /** [argb] as a plain color at the point ([x], [y]) of the current pose. */
    fun flatten(argb: Int, pose: Matrix3x2fc, x: Float, y: Float): Int =
        if (!isChroma(argb)) argb
        else flatten(argb, pose.m00() * x + pose.m10() * y + pose.m20(), pose.m01() * x + pose.m11() * y + pose.m21())

    /** `g.fill` that understands chroma: a chroma color draws as part of the screen-space rainbow (same scissor). */
    fun fill(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
        if (!isChroma(argb)) {
            g.fill(x0, y0, x1, y1, argb)
            return
        }
        val ax = min(x0, x1)
        val bx = max(x0, x1)
        val ay = min(y0, y1)
        val by = max(y0, y1)
        if (bx == ax || by == ay) return
        val pose = Matrix3x2f(g.pose())
        val xs = floatArrayOf(ax.toFloat(), ax.toFloat(), bx.toFloat(), bx.toFloat())
        val ys = floatArrayOf(ay.toFloat(), by.toFloat(), by.toFloat(), ay.toFloat())
        val bounds = ScreenRectangle(ax, ay, bx - ax, by - ay).transformMaxBounds(pose)
        g.guiRenderState.addGuiElement(ShapeRenderState(pose, xs, ys, IntArray(4) { argb }, 4, bounds, chroma = true, scissor = g.scissorStack.peek()))
    }

    /** Full saturation/value hue (0..1) → 0xFFRRGGBB; the shaders' `chromaRgb` does the same. */
    fun rainbow(h: Float): Int {
        val x = (h - floor(h)) * 6f
        val i = x.toInt() % 6
        val f = x - x.toInt()
        val r: Float
        val g: Float
        val b: Float
        if (i == 0) { r = 1f; g = f; b = 0f }
        else if (i == 1) { r = 1f - f; g = 1f; b = 0f }
        else if (i == 2) { r = 0f; g = 1f; b = f }
        else if (i == 3) { r = 0f; g = 1f - f; b = 1f }
        else if (i == 4) { r = f; g = 0f; b = 1f }
        else { r = 1f; g = 0f; b = 1f - f }
        return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }
}
