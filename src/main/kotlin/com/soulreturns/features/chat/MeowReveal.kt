package com.soulreturns.features.chat

import com.soulreturns.config.gui.render.Shapes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import org.joml.Matrix3x2f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Auto meow reveal (`mocks/meow/`, "Cat + paws"), one choreography for the menu and over the game,
 * in seconds from its start: paw prints walk to the spot (one every [PRINT_STEP], each fading over
 * [PRINT_LIFE]), the entry pops in at [POP_AT], the cat peeks out from behind it at [CAT_AT], blinks,
 * hops and ducks back (gone at [CAT_END]); over the game the card fades last ([FADE_AT], [FADE_LEN]).
 * Every value is a function of elapsed time, so the frame rate never changes the motion.
 */
object MeowReveal {
    const val PRINT_STEP = 0.07
    const val PRINT_LIFE = 1.3
    const val POP_AT = 0.85
    const val POP_LEN = 0.4
    const val CAT_AT = 1.4
    const val CAT_LEN = 1.35
    const val CAT_END = CAT_AT + CAT_LEN
    const val FADE_AT = 2.9
    const val FADE_LEN = 0.6

    /** The cat's window: it rises inside it, and the entry covers its bottom [CAT_OVERLAP] px. */
    const val CAT_W = 50
    const val CAT_H = 46
    const val CAT_OVERLAP = 4

    const val PRINT_SIZE = 20f

    /** Mock `#7FB0FF`, the prints and the cat's lines. */
    const val ACCENT_LIGHT = 0xFF7FB0FF.toInt()
    private const val CAT_FILL = 0xFF262626.toInt()
    private const val EAR = 0x733B82F6
    private const val NOSE = 0xFF93C5FD.toInt()
    private const val WHISKER = 0x997FB0FF.toInt()

    // ------------------------------------------------------------------ timeline (CSS keyframes, `ease` per segment)

    /** CSS `ease`: cubic-bezier(0.25, 0.1, 0.25, 1). */
    private fun ease(p: Double): Double {
        if (p <= 0.0) return 0.0
        if (p >= 1.0) return 1.0
        var lo = 0.0
        var hi = 1.0
        var u = p
        repeat(24) {
            u = (lo + hi) / 2
            val x = 3 * (1 - u) * (1 - u) * u * 0.25 + 3 * (1 - u) * u * u * 0.25 + u * u * u
            if (x < p) lo = u else hi = u
        }
        return 3 * (1 - u) * (1 - u) * u * 0.1 + 3 * (1 - u) * u * u + u * u * u
    }

    /** Keyframes [at] (0..1) with [values], read at progress [p]; held before the first and after the last (fill both). */
    private fun keyframes(p: Double, at: DoubleArray, values: DoubleArray): Double {
        if (p <= at[0]) return values[0]
        for (i in 0 until at.size - 1) {
            if (p < at[i + 1]) {
                val local = (p - at[i]) / (at[i + 1] - at[i])
                return values[i] + (values[i + 1] - values[i]) * ease(local)
            }
        }
        return values.last()
    }

    private fun progress(t: Double, start: Double, len: Double): Double = ((t - start) / len).coerceIn(0.0, 1.0)

    private val STEP_OPACITY_AT = doubleArrayOf(0.0, 0.10, 0.60, 1.0)
    private val STEP_OPACITY = doubleArrayOf(0.0, 0.95, 0.5, 0.0)
    private val STEP_SCALE_AT = doubleArrayOf(0.0, 0.10, 0.18, 1.0)
    private val STEP_SCALE = doubleArrayOf(0.5, 1.1, 1.0, 1.0)
    private val POP_AT_ = doubleArrayOf(0.0, 0.65, 1.0)
    private val POP_OPACITY = doubleArrayOf(0.0, 1.0, 1.0)
    private val POP_SCALE = doubleArrayOf(0.5, 1.05, 1.0)
    private val PEEK_AT = doubleArrayOf(0.0, 0.18, 0.24, 0.52, 0.59, 0.66, 0.78, 1.0)
    private val PEEK_Y = doubleArrayOf(48.0, -2.0, 0.0, 0.0, -5.0, 0.0, 0.0, 48.0)
    private val BLINK_AT = doubleArrayOf(0.0, 0.37, 0.41, 0.45, 1.0)
    private val BLINK = doubleArrayOf(1.0, 1.0, 0.1, 1.0, 1.0)

    /** The entry's pop: opacity and scale (origin: its bottom center). Opacity 0 before [POP_AT]. */
    fun popAlpha(t: Double): Float = keyframes(progress(t, POP_AT, POP_LEN), POP_AT_, POP_OPACITY).toFloat()

    fun popScale(t: Double): Float = keyframes(progress(t, POP_AT, POP_LEN), POP_AT_, POP_SCALE).toFloat()

    /** Over the game: the whole reveal's opacity and lift while it fades out. */
    fun fadeAlpha(t: Double): Float = (1.0 - ease(progress(t, FADE_AT, FADE_LEN))).toFloat()

    fun fadeLift(t: Double): Float = (-8.0 * ease(progress(t, FADE_AT, FADE_LEN))).toFloat()

    /** The cat's drop inside its window (0 = sitting up, 48 = hidden) and its eyes' height (1 open, 0.1 shut). */
    fun catDrop(t: Double): Float = keyframes(progress(t, CAT_AT, CAT_LEN), PEEK_AT, PEEK_Y).toFloat()

    fun catBlink(t: Double): Float = keyframes(progress(t, CAT_AT, CAT_LEN), BLINK_AT, BLINK).toFloat()

    // ------------------------------------------------------------------ drawing

    private fun fade(color: Int, a: Float): Int = (((color ushr 24) * a.coerceIn(0f, 1f)).roundToInt() shl 24) or (color and 0xFFFFFF)

    /**
     * [n] paw prints from ([x0], [y0]) to ([x1], [y1]), alternating 6 px left and right of the line,
     * toes along it; print i starts at i × [PRINT_STEP].
     */
    fun trail(g: GuiGraphicsExtractor, t: Double, x0: Float, y0: Float, x1: Float, y1: Float, n: Int, feather: Float, alpha: Float = 1f) {
        val ang = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val rot = ang + Math.PI / 2
        val s = Shapes(feather)
        for (i in 0 until n) {
            val p = progress(t, i * PRINT_STEP, PRINT_LIFE)
            val a = keyframes(p, STEP_OPACITY_AT, STEP_OPACITY).toFloat() * alpha
            if (a <= 0.004f) continue
            val k = i / (n - 1f)
            val side = if (i % 2 == 1) 6f else -6f
            val x = x0 + (x1 - x0) * k - sin(ang).toFloat() * side
            val y = y0 + (y1 - y0) * k + cos(ang).toFloat() * side
            val scale = keyframes(p, STEP_SCALE_AT, STEP_SCALE).toFloat()
            paw(s, x, y, PRINT_SIZE * scale, rot, fade(ACCENT_LIGHT, a))
        }
        s.submit(g)
    }

    /** The mock's paw (viewBox 24: a pad and four toes) centered on ([cx], [cy]), [size] across, turned by [rot] radians. */
    fun paw(s: Shapes, cx: Float, cy: Float, size: Float, rot: Double, color: Int) {
        val k = size / 24f
        val c = cos(rot).toFloat()
        val sn = sin(rot).toFloat()
        fun px(x: Float, y: Float) = cx + ((x - 12f) * c - (y - 12f) * sn) * k
        fun py(x: Float, y: Float) = cy + ((x - 12f) * sn + (y - 12f) * c) * k
        val seg = 20
        val ex = FloatArray(seg)
        val ey = FloatArray(seg)
        for (i in 0 until seg) {
            val a = 2 * Math.PI * i / seg
            val x = 12f + 5.6f * cos(a).toFloat()
            val y = 16.2f + 4.6f * sin(a).toFloat()
            ex[i] = px(x, y)
            ey[i] = py(x, y)
        }
        s.polygon(ex, ey, color)
        for ((x, y) in TOES) s.disc(px(x, y), py(x, y), 2.3f * k, color)
    }

    private val TOES = listOf(5.6f to 10.4f, 9.7f to 6.4f, 14.3f to 6.4f, 18.4f to 10.4f)

    /**
     * The cat peeking from its window ([wx], [wy], [CAT_W] × [CAT_H]; clipped to it) at time [t].
     * Draw it before the entry it hides behind.
     */
    fun cat(g: GuiGraphicsExtractor, t: Double, wx: Int, wy: Int, feather: Float) {
        val drop = catDrop(t)
        if (drop >= CAT_H) return
        val s = Shapes(feather)
        val ox = wx.toFloat()
        val oy = wy + drop
        fun poly(vararg pts: Float, color: Int) {
            val xs = FloatArray(pts.size / 2) { ox + pts[it * 2] }
            val ys = FloatArray(pts.size / 2) { oy + pts[it * 2 + 1] }
            s.polygon(xs, ys, color)
        }
        // The mock's path: M5 47 L5 18 L9 2 L19 12 Q25 10 31 12 L41 2 L45 18 L45 47. Its convex head
        // (without the ear tips) and the two ears are filled, then the outline covers their seams.
        val brow = (1 until 8).map { quad(19f, 25f, 31f, it / 8f) to quad(12f, 10f, 12f, it / 8f) }
        val path = listOf(5f to 47f, 5f to 18f, 9f to 2f, 19f to 12f) + brow + listOf(31f to 12f, 41f to 2f, 45f to 18f, 45f to 47f)
        val head = path.filter { it.second != 2f }
        s.polygon(FloatArray(head.size) { ox + head[it].first }, FloatArray(head.size) { oy + head[it].second }, CAT_FILL)
        poly(5f, 18f, 9f, 2f, 19f, 12f, color = CAT_FILL)
        poly(31f, 12f, 41f, 2f, 45f, 18f, color = CAT_FILL)
        poly(10f, 8f, 15f, 12f, 11f, 14f, color = EAR)
        poly(40f, 8f, 35f, 12f, 39f, 14f, color = EAR)
        for (i in 0 until path.size - 1) s.line(ox + path[i].first, oy + path[i].second, ox + path[i + 1].first, oy + path[i + 1].second, 1.5f, ACCENT_LIGHT)
        // Eyes blink about their centers.
        val blink = catBlink(t)
        for (cx in listOf(18f, 32f)) {
            val seg = 16
            val xs = FloatArray(seg) { ox + cx + 2.6f * cos(2 * Math.PI * it / seg).toFloat() }
            val ys = FloatArray(seg) { oy + 24f + 3.4f * blink * sin(2 * Math.PI * it / seg).toFloat() }
            s.polygon(xs, ys, ACCENT_LIGHT)
        }
        poly(23.4f, 29f, 26.6f, 29f, 25f, 31f, color = NOSE)
        // Mouth: Q from (22, 33) and from (28, 33), both through (25, 35.5), meeting at (25, 31.5).
        for (x0 in listOf(22f, 28f)) {
            var lx = x0
            var ly = 33f
            for (i in 1..6) {
                val u = i / 6f
                val x = quad(x0, 25f, 25f, u)
                val y = quad(33f, 35.5f, 31.5f, u)
                s.line(ox + lx, oy + ly, ox + x, oy + y, 1f, ACCENT_LIGHT)
                lx = x
                ly = y
            }
        }
        // Whiskers as thin quads (round caps would double up their alpha).
        for ((a, b) in listOf((11f to 30f) to (1f to 28f), (11f to 32.5f) to (1f to 34f), (39f to 30f) to (49f to 28f), (39f to 32.5f) to (49f to 34f))) {
            bar(s, ox + a.first, oy + a.second, ox + b.first, oy + b.second, 0.8f, WHISKER)
        }
        s.submit(g, ScreenRectangle(wx, wy, CAT_W, CAT_H).transformMaxBounds(Matrix3x2f(g.pose())))
    }

    private fun quad(a: Float, c: Float, b: Float, u: Float): Float = (1 - u) * (1 - u) * a + 2 * (1 - u) * u * c + u * u * b

    private fun bar(s: Shapes, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val nx = -dy / len * w / 2
        val ny = dx / len * w / 2
        s.polygon(floatArrayOf(x0 + nx, x1 + nx, x1 - nx, x0 - nx), floatArrayOf(y0 + ny, y1 + ny, y1 - ny, y0 - ny), color)
    }
}
