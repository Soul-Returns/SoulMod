package com.soulreturns.config.gui.render

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fc
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Free-form colored quads (vanilla [RenderPipelines.GUI], POSITION_COLOR): arcs and stroked lines
 * that the SDF rectangles cannot draw. Anti-aliased by a feather band whose vertex alpha fades to 0
 * over about one physical pixel on each side. Build with [Shapes], submit once per shape group.
 * [chroma]: every vertex is a chroma color ([Chroma]); drawn with the GUI pipeline's chroma variant.
 */
class ShapeRenderState(
    private val pose: Matrix3x2fc,
    /** x, y, color per vertex; four vertices per quad. */
    private val xs: FloatArray,
    private val ys: FloatArray,
    private val colors: IntArray,
    private val count: Int,
    private val bnds: ScreenRectangle,
    private val chroma: Boolean = false,
    /** The GL scissor (as vanilla's `fill` takes it from the extractor's scissor stack); null = none. */
    private val scissor: ScreenRectangle? = null,
) : GuiElementRenderState {
    private val pipeline = (if (chroma) SoulRenderPipelines.chroma(RenderPipelines.GUI) else null) ?: RenderPipelines.GUI
    private val clipped: ScreenRectangle? = if (scissor == null) bnds else scissor.intersection(bnds)

    override fun buildVertices(vertexConsumer: VertexConsumer) {
        if (chroma) {
            val slope = Chroma.slope()
            val phase = Chroma.phase()
            for (i in 0 until count) Chroma.vertex(vertexConsumer, pose, xs[i], ys[i], slope, phase).setColor(colors[i])
            return
        }
        for (i in 0 until count) vertexConsumer.addVertexWith2DPose(pose, xs[i], ys[i]).setColor(colors[i])
    }

    override fun pipeline() = pipeline

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle? = clipped
}

/**
 * Collects anti-aliased quads in the current pose's units. [feather] is the AA band (one physical
 * pixel in these units). Call [submit] to draw everything added so far as one element.
 */
class Shapes(private val feather: Float) {
    private var xs = FloatArray(256)
    private var ys = FloatArray(256)
    private var cs = IntArray(256)
    private var n = 0
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE

    private fun v(x: Float, y: Float, c: Int) {
        if (n == xs.size) {
            xs = xs.copyOf(n * 2)
            ys = ys.copyOf(n * 2)
            cs = cs.copyOf(n * 2)
        }
        xs[n] = x
        ys[n] = y
        cs[n] = c
        n++
        minX = min(minX, x)
        minY = min(minY, y)
        maxX = max(maxX, x)
        maxY = max(maxY, y)
    }

    /** Emitted in vanilla's winding (as `fill`: top-left, bottom-left, bottom-right, top-right); the other way is culled. */
    private fun quad(ax: Float, ay: Float, ac: Int, bx: Float, by: Float, bc: Int, cx: Float, cy: Float, cc: Int, dx: Float, dy: Float, dc: Int) {
        val area = (ax * by - bx * ay) + (bx * cy - cx * by) + (cx * dy - dx * cy) + (dx * ay - ax * dy)
        v(ax, ay, ac)
        if (area <= 0f) {
            v(bx, by, bc)
            v(cx, cy, cc)
            v(dx, dy, dc)
        } else {
            v(dx, dy, dc)
            v(cx, cy, cc)
            v(bx, by, bc)
        }
    }

    /**
     * A ring band of [width] on radius [r] around ([cx], [cy]), from [start] clockwise over [sweep]
     * radians (0 = 12 o'clock), flat ends. AA on both rims.
     */
    fun arc(cx: Float, cy: Float, r: Float, width: Float, start: Double, sweep: Double, color: Int) {
        if (sweep <= 0.0 || width <= 0f) return
        val clear = color and 0x00FFFFFF
        val half = width / 2f
        val f = feather / 2f
        val rIn = r - half + f
        val rOut = r + half - f
        val segs = max(2, ceil(sweep / (2 * PI) * SEGMENTS).toInt())
        for (i in 0 until segs) {
            val a0 = start + sweep * i / segs
            val a1 = start + sweep * (i + 1) / segs
            // Screen y grows downward: 12 o'clock is -y, clockwise is +x.
            val s0 = sin(a0).toFloat()
            val c0 = -cos(a0).toFloat()
            val s1 = sin(a1).toFloat()
            val c1 = -cos(a1).toFloat()
            fun p(rad: Float, s: Float, c: Float) = (cx + rad * s) to (cy + rad * c)
            val (i0x, i0y) = p(rIn, s0, c0)
            val (i1x, i1y) = p(rIn, s1, c1)
            val (o0x, o0y) = p(rOut, s0, c0)
            val (o1x, o1y) = p(rOut, s1, c1)
            if (rOut > rIn) quad(i0x, i0y, color, o0x, o0y, color, o1x, o1y, color, i1x, i1y, color)
            val (e0x, e0y) = p(rOut + feather, s0, c0)
            val (e1x, e1y) = p(rOut + feather, s1, c1)
            quad(o0x, o0y, color, e0x, e0y, clear, e1x, e1y, clear, o1x, o1y, color)
            val (n0x, n0y) = p(max(0f, rIn - feather), s0, c0)
            val (n1x, n1y) = p(max(0f, rIn - feather), s1, c1)
            quad(n0x, n0y, clear, i0x, i0y, color, i1x, i1y, color, n1x, n1y, clear)
        }
    }

    /** A filled disc (round caps and joins of [line]). */
    fun disc(cx: Float, cy: Float, r: Float, color: Int) {
        val clear = color and 0x00FFFFFF
        val core = max(0f, r - feather / 2f)
        val outer = r + feather / 2f
        val segs = 24
        for (i in 0 until segs) {
            val a0 = 2 * PI * i / segs
            val a1 = 2 * PI * (i + 1) / segs
            val s0 = sin(a0).toFloat()
            val c0 = cos(a0).toFloat()
            val s1 = sin(a1).toFloat()
            val c1 = cos(a1).toFloat()
            quad(cx, cy, color, cx + core * s0, cy + core * c0, color, cx + core * s1, cy + core * c1, color, cx, cy, color)
            quad(cx + core * s0, cy + core * c0, color, cx + outer * s0, cy + outer * c0, clear, cx + outer * s1, cy + outer * c1, clear, cx + core * s1, cy + core * c1, color)
        }
    }

    /** A straight stroke of [width] from ([x0], [y0]) to ([x1], [y1]) with round caps. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-4f) return
        val clear = color and 0x00FFFFFF
        val nx = -dy / len
        val ny = dx / len
        val core = max(0f, width / 2f - feather / 2f)
        val outer = width / 2f + feather / 2f
        quad(x0 + nx * core, y0 + ny * core, color, x1 + nx * core, y1 + ny * core, color, x1 - nx * core, y1 - ny * core, color, x0 - nx * core, y0 - ny * core, color)
        quad(x0 + nx * core, y0 + ny * core, color, x0 + nx * outer, y0 + ny * outer, clear, x1 + nx * outer, y1 + ny * outer, clear, x1 + nx * core, y1 + ny * core, color)
        quad(x0 - nx * core, y0 - ny * core, color, x1 - nx * core, y1 - ny * core, color, x1 - nx * outer, y1 - ny * outer, clear, x0 - nx * outer, y0 - ny * outer, clear)
        disc(x0, y0, width / 2f, color)
        disc(x1, y1, width / 2f, color)
    }

    /** A filled convex polygon through [px]/[py] (either winding), AA on its rim. */
    fun polygon(px: FloatArray, py: FloatArray, color: Int) {
        val k = min(px.size, py.size)
        if (k < 3) return
        val clear = color and 0x00FFFFFF
        var gx = 0f
        var gy = 0f
        for (i in 0 until k) {
            gx += px[i] / k
            gy += py[i] / k
        }
        // Unit outward normal of each edge i -> i+1.
        val nx = FloatArray(k)
        val ny = FloatArray(k)
        for (i in 0 until k) {
            val j = (i + 1) % k
            val dx = px[j] - px[i]
            val dy = py[j] - py[i]
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f)
            var ex = dy / len
            var ey = -dx / len
            if (ex * ((px[i] + px[j]) / 2f - gx) + ey * ((py[i] + py[j]) / 2f - gy) < 0f) {
                ex = -ex
                ey = -ey
            }
            nx[i] = ex
            ny[i] = ey
        }
        // Each corner moves along the bisector so both edges shift by half a feather (miter, capped).
        val ix = FloatArray(k)
        val iy = FloatArray(k)
        val ox = FloatArray(k)
        val oy = FloatArray(k)
        val h = feather / 2f
        for (i in 0 until k) {
            val p = (i + k - 1) % k
            var bx = nx[p] + nx[i]
            var by = ny[p] + ny[i]
            val bl = sqrt(bx * bx + by * by).coerceAtLeast(1e-4f)
            bx /= bl
            by /= bl
            val d = h / max(0.34f, bx * nx[i] + by * ny[i])
            ix[i] = px[i] - bx * d
            iy[i] = py[i] - by * d
            ox[i] = px[i] + bx * d
            oy[i] = py[i] + by * d
        }
        for (i in 1 until k - 1) quad(ix[0], iy[0], color, ix[i], iy[i], color, ix[i + 1], iy[i + 1], color, ix[i + 1], iy[i + 1], color)
        for (i in 0 until k) {
            val j = (i + 1) % k
            quad(ix[i], iy[i], color, ox[i], oy[i], clear, ox[j], oy[j], clear, ix[j], iy[j], color)
        }
    }

    /**
     * Submits the quads as one element; chroma quads ([Chroma]) go into a second element drawn after the plain ones.
     * [scissor] (screen coordinates, as `beginClip` records it) clips them; null = none.
     */
    fun submit(g: GuiGraphicsExtractor, scissor: ScreenRectangle? = null) {
        if (n == 0) return
        val pose = Matrix3x2f(g.pose())
        val b = ScreenRectangle(floor(minX).toInt() - 1, floor(minY).toInt() - 1, (ceil(maxX) - floor(minX)).toInt() + 2, (ceil(maxY) - floor(minY)).toInt() + 2)
            .transformMaxBounds(pose)
        var chromaN = 0
        for (i in 0 until n) if (Chroma.isChroma(cs[i])) chromaN++
        if (chromaN == 0 || chromaN == n) {
            g.guiRenderState.addGuiElement(ShapeRenderState(pose, xs, ys, cs, n, b, chroma = chromaN == n, scissor = scissor))
        } else {
            // Quads never mix: a quad's four vertices share one color's RGB (its feather only drops the alpha).
            val px = FloatArray(n - chromaN)
            val py = FloatArray(n - chromaN)
            val pc = IntArray(n - chromaN)
            val qx = FloatArray(chromaN)
            val qy = FloatArray(chromaN)
            val qc = IntArray(chromaN)
            var p = 0
            var q = 0
            for (i in 0 until n) {
                if (Chroma.isChroma(cs[i])) {
                    qx[q] = xs[i]; qy[q] = ys[i]; qc[q] = cs[i]; q++
                } else {
                    px[p] = xs[i]; py[p] = ys[i]; pc[p] = cs[i]; p++
                }
            }
            g.guiRenderState.addGuiElement(ShapeRenderState(pose, px, py, pc, p, b, scissor = scissor))
            g.guiRenderState.addGuiElement(ShapeRenderState(pose, qx, qy, qc, q, b, chroma = true, scissor = scissor))
        }
        xs = FloatArray(256)
        ys = FloatArray(256)
        cs = IntArray(256)
        n = 0
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
    }

    companion object {
        /** Segments of a full circle. */
        const val SEGMENTS = 96
    }
}
