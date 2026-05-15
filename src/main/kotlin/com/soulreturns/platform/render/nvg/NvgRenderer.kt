// Adapted from Odin (github.com/odtheking/Odin) — BSD 3-Clause License.
// Original backend by Aton; design by Stivais.
package com.soulreturns.platform.render.nvg

import com.soulreturns.util.SoulLogger
import net.minecraft.client.Minecraft
import org.lwjgl.nanovg.NVGColor
import org.lwjgl.nanovg.NVGPaint
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_LEFT
import org.lwjgl.nanovg.NanoVG.NVG_ALIGN_TOP
import org.lwjgl.nanovg.NanoVG.NVG_HOLE
import org.lwjgl.nanovg.NanoVG.nvgArcTo
import org.lwjgl.nanovg.NanoVG.nvgBeginFrame
import org.lwjgl.nanovg.NanoVG.nvgBeginPath
import org.lwjgl.nanovg.NanoVG.nvgBoxGradient
import org.lwjgl.nanovg.NanoVG.nvgCircle
import org.lwjgl.nanovg.NanoVG.nvgClosePath
import org.lwjgl.nanovg.NanoVG.nvgCreateFontMem
import org.lwjgl.nanovg.NanoVG.nvgCreateImageMem
import org.lwjgl.nanovg.NanoVG.nvgEndFrame
import org.lwjgl.nanovg.NanoVG.nvgFill
import org.lwjgl.nanovg.NanoVG.nvgFillColor
import org.lwjgl.nanovg.NanoVG.nvgFillPaint
import org.lwjgl.nanovg.NanoVG.nvgFontFaceId
import org.lwjgl.nanovg.NanoVG.nvgFontSize
import org.lwjgl.nanovg.NanoVG.nvgGlobalAlpha
import org.lwjgl.nanovg.NanoVG.nvgImagePattern
import org.lwjgl.nanovg.NanoVG.nvgLineTo
import org.lwjgl.nanovg.NanoVG.nvgLinearGradient
import org.lwjgl.nanovg.NanoVG.nvgMoveTo
import org.lwjgl.nanovg.NanoVG.nvgPathWinding
import org.lwjgl.nanovg.NanoVG.nvgRGBA
import org.lwjgl.nanovg.NanoVG.nvgRect
import org.lwjgl.nanovg.NanoVG.nvgResetScissor
import org.lwjgl.nanovg.NanoVG.nvgRestore
import org.lwjgl.nanovg.NanoVG.nvgRotate
import org.lwjgl.nanovg.NanoVG.nvgRoundedRect
import org.lwjgl.nanovg.NanoVG.nvgSave
import org.lwjgl.nanovg.NanoVG.nvgScale
import org.lwjgl.nanovg.NanoVG.nvgScissor
import org.lwjgl.nanovg.NanoVG.nvgStroke
import org.lwjgl.nanovg.NanoVG.nvgStrokeColor
import org.lwjgl.nanovg.NanoVG.nvgStrokeWidth
import org.lwjgl.nanovg.NanoVG.nvgText
import org.lwjgl.nanovg.NanoVG.nvgTextAlign
import org.lwjgl.nanovg.NanoVG.nvgTextBounds
import org.lwjgl.nanovg.NanoVG.nvgTextBox
import org.lwjgl.nanovg.NanoVG.nvgTextBoxBounds
import org.lwjgl.nanovg.NanoVG.nvgTextLineHeight
import org.lwjgl.nanovg.NanoVG.nvgTranslate
import org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS
import org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES
import org.lwjgl.nanovg.NanoVGGL3.nvgCreate
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Singleton NanoVG renderer providing a Kotlin-friendly API over LWJGL's NanoVG bindings.
 *
 * **Lifecycle.** The NanoVG GL context is created lazily on the first call to [beginFrame] —
 * at that point we're guaranteed to be on Minecraft's render thread with an active OpenGL
 * context. There is no explicit dispose hook; the context lives for the JVM lifetime.
 *
 * **Threading.** All public methods must be called from Minecraft's render thread. NanoVG is
 * not thread-safe and the underlying OpenGL context is bound to one thread.
 *
 * **Coordinate space.** NanoVG draws in screen-pixel space (the active framebuffer's pixel
 * dimensions). The [beginFrame] caller is responsible for setting up the viewport. Internally
 * we factor `nvgBeginFrame` by [devicePixelRatio] so a single logical pixel renders crisply
 * on both low- and high-DPI displays.
 *
 * **Color format.** All `color: Int` arguments are ARGB packed (`0xAARRGGBB`).
 *
 * Surface is intentionally minimal — text, rect/roundedRect, hollowRect, gradientRect,
 * dropShadow, circle, line, intersection-aware scissor, transform stack. Image/SVG support
 * is deferred; add when a feature needs it.
 */
object NvgRenderer {
    private val logger = SoulLogger("Soul/NVG")

    private val nvgPaint = NVGPaint.malloc()
    private val nvgColor = NVGColor.malloc()
    private val nvgColor2 = NVGColor.malloc()
    private val fontBounds = FloatArray(4)
    private val fontMap = HashMap<NvgFont, NvgFontHandle>()

    private var scissor: Scissor? = null
    private var drawing: Boolean = false
    private var vg: Long = -1L

    /** Inter Regular — the default body face. Loaded lazily on first [getFontId] for this font. */
    val defaultFont: NvgFont by lazy {
        loadBundledFont("Inter-Regular", "/assets/soul/fonts/Inter-Regular.ttf")
    }

    /** Inter Medium — used for emphasized body / table headers. */
    val mediumFont: NvgFont by lazy {
        loadBundledFont("Inter-Medium", "/assets/soul/fonts/Inter-Medium.ttf")
    }

    /** Inter SemiBold — used for titles / strong headings. */
    val semiBoldFont: NvgFont by lazy {
        loadBundledFont("Inter-SemiBold", "/assets/soul/fonts/Inter-SemiBold.ttf")
    }

    /** Inter Bold — heavy weight for HUDs (via [boldVariantOf]) or strong emphasis. */
    val boldFont: NvgFont by lazy {
        loadBundledFont("Inter-Bold", "/assets/soul/fonts/Inter-Bold.ttf")
    }

    /** Inter Black — heaviest bundled weight. Reserved for the "HUD Bold Font" upgrade path. */
    val blackFont: NvgFont by lazy {
        loadBundledFont("Inter-Black", "/assets/soul/fonts/Inter-Black.ttf")
    }

    /**
     * Two steps heavier than [font] on the bundled Inter weight ladder
     * (Regular → SemiBold, Medium → Bold, SemiBold → Black, Bold → Black, Black → Black).
     * The skip-a-tier step is what makes the "HUD Bold Font" toggle visibly effective —
     * jumping a single tier (Regular → Medium) is too subtle at small HUD sizes to read
     * as "noticeably bolder" against the user's expectation. Relative weight relationships
     * survive the bump: a title that was SemiBold stays heavier than a body that was
     * Regular, because they jump to Black vs SemiBold respectively.
     */
    fun boldVariantOf(font: NvgFont): NvgFont =
        when (font) {
            defaultFont -> semiBoldFont
            mediumFont -> boldFont
            semiBoldFont -> blackFont
            boldFont -> blackFont
            else -> font
        }

    private fun loadBundledFont(
        name: String,
        classpathResource: String
    ): NvgFont {
        val stream =
            javaClass.getResourceAsStream(classpathResource)
                ?: error("Bundled font missing at $classpathResource")
        return NvgFont(name, stream)
    }

    private fun ensureContext() {
        if (vg != -1L) return
        vg = nvgCreate(NVG_ANTIALIAS or NVG_STENCIL_STROKES)
        require(vg != -1L) { "Failed to initialize NanoVG" }
        logger.info("NanoVG GL3 context created (handle=$vg)")
    }

    /**
     * OS-level pixel density (physical-pixels-per-CSS-pixel, e.g. 2.0 on a HiDPI display).
     * Used by the [beginFrame] convenience overload for callers without explicit DPR control.
     */
    fun devicePixelRatio(): Float {
        val window = Minecraft.getInstance().window
        return if (window.screenWidth == 0) 1f else (window.width / window.screenWidth.toFloat())
    }

    /**
     * Open a NanoVG frame at an explicit logical size + DPR. Must be paired with [endFrame].
     *
     * @param logicalWidth Width of the coordinate space used inside the frame. NVG draws
     *   at logical-pixel coordinates from `(0, 0)` to `(logicalWidth, logicalHeight)`.
     * @param logicalHeight See [logicalWidth].
     * @param dpr Physical pixels per logical pixel — drives how NanoVG rasterizes glyphs and
     *   stroke widths so they stay crisp at the actual framebuffer resolution. The active GL
     *   viewport should be `(0, 0, logicalWidth * dpr, logicalHeight * dpr)` for correct
     *   projection.
     *
     * The caller is responsible for knowing the framebuffer-vs-logical ratio. For PIP
     * rendering this is `physicalTextureWidth / requestedContentWidth`; for direct
     * main-framebuffer rendering it would be Minecraft's GUI scale.
     */
    fun beginFrame(
        logicalWidth: Float,
        logicalHeight: Float,
        dpr: Float,
    ) {
        check(!drawing) { "NvgRenderer.beginFrame called while already in a frame" }
        ensureContext()
        nvgBeginFrame(vg, logicalWidth, logicalHeight, dpr)
        nvgTextAlign(vg, NVG_ALIGN_LEFT or NVG_ALIGN_TOP)
        drawing = true
    }

    /** Convenience [beginFrame] using the OS [devicePixelRatio] — pass the physical size. */
    fun beginFrame(
        physicalWidth: Float,
        physicalHeight: Float,
    ) {
        val dpr = devicePixelRatio()
        beginFrame(physicalWidth / dpr, physicalHeight / dpr, dpr)
    }

    fun endFrame() {
        check(drawing) { "NvgRenderer.endFrame called outside a frame" }
        nvgEndFrame(vg)
        drawing = false
    }

    // ─────────────────────────── transforms ───────────────────────────
    fun push() = nvgSave(vg)

    fun pop() = nvgRestore(vg)

    fun scale(
        x: Float,
        y: Float
    ) = nvgScale(vg, x, y)

    fun translate(
        x: Float,
        y: Float
    ) = nvgTranslate(vg, x, y)

    fun rotate(amount: Float) = nvgRotate(vg, amount)

    fun globalAlpha(amount: Float) = nvgGlobalAlpha(vg, amount.coerceIn(0f, 1f))

    // ─────────────────────────── scissor stack ───────────────────────────

    /**
     * Constrain subsequent draws to [x, y, x+w, y+h]. Nested calls intersect with the parent
     * scissor (NanoVG's native [nvgScissor] only *replaces*, not intersects — Odin's stack
     * preserves intersection semantics so a child scissor never paints outside its parent).
     */
    fun pushScissor(
        x: Float,
        y: Float,
        w: Float,
        h: Float
    ) {
        scissor = Scissor(scissor, x, y, w + x, h + y)
        scissor?.applyScissor(vg)
    }

    fun popScissor() {
        nvgResetScissor(vg)
        scissor = scissor?.previous
        scissor?.applyScissor(vg)
    }

    /**
     * Intersected bounds of the active scissor stack, or `null` when nothing has pushed.
     * Used by input plumbing to clip hit-region records to the visible viewport — without
     * this, an off-screen child of a [com.soulreturns.ui.foundation.ScrollableList] would
     * register a clickable region that intercepts clicks meant for siblings rendered below
     * (or above) the scrollable. The intersection is computed once per call by walking the
     * stack; cheap because the stack is shallow in practice.
     */
    fun currentScissorBounds(): ScissorBounds? {
        val top = scissor ?: return null
        var x = top.x
        var y = top.y
        var maxX = top.maxX
        var maxY = top.maxY
        var p = top.previous
        while (p != null) {
            x = max(x, p.x)
            y = max(y, p.y)
            maxX = min(maxX, p.maxX)
            maxY = min(maxY, p.maxY)
            p = p.previous
        }
        return ScissorBounds(x, y, maxX, maxY)
    }

    // ─────────────────────────── paths ───────────────────────────
    fun line(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        thickness: Float,
        color: Int,
    ) {
        nvgBeginPath(vg)
        nvgMoveTo(vg, x1, y1)
        nvgLineTo(vg, x2, y2)
        nvgStrokeWidth(vg, thickness)
        setStrokeColor(color)
        nvgStroke(vg)
    }

    fun rect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Int,
        radius: Float,
    ) {
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x, y, w, h + .5f, radius)
        setFillColor(color)
        nvgFill(vg)
    }

    /**
     * Solid triangle defined by three corner points. Winding order doesn't matter for fill —
     * NanoVG fills the closed path regardless. Useful for caret glyphs / arrows / pointer
     * indicators that the bundled Inter font lacks the geometric-shape codepoints to render
     * via [text].
     */
    fun filledTriangle(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
        color: Int,
    ) {
        nvgBeginPath(vg)
        nvgMoveTo(vg, x1, y1)
        nvgLineTo(vg, x2, y2)
        nvgLineTo(vg, x3, y3)
        nvgClosePath(vg)
        setFillColor(color)
        nvgFill(vg)
    }

    fun rect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Int,
    ) {
        nvgBeginPath(vg)
        nvgRect(vg, x, y, w, h + .5f)
        setFillColor(color)
        nvgFill(vg)
    }

    fun hollowRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        thickness: Float,
        color: Int,
        radius: Float,
    ) {
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x, y, w, h, radius)
        nvgStrokeWidth(vg, thickness)
        nvgPathWinding(vg, NVG_HOLE)
        setStrokeColor(color)
        nvgStroke(vg)
    }

    fun gradientRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color1: Int,
        color2: Int,
        gradient: NvgGradient,
        radius: Float,
    ) {
        nvgBeginPath(vg)
        nvgRoundedRect(vg, x, y, w, h, radius)
        applyGradient(color1, color2, x, y, w, h, gradient)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    /**
     * Per-classpath-path image cache. NanoVG image handles are integers owned by the NVG
     * context — created via [nvgCreateImageMem] and never freed (the cache lives for the
     * JVM lifetime, matching the renderer's lifecycle).
     */
    private val imageCache: MutableMap<String, Int> = HashMap()

    /**
     * Load a PNG / JPG / GIF from the classpath and return its NanoVG image handle. The
     * file at [resourcePath] (e.g. `"assets/soul/textures/gui/discord.png"`) is read once;
     * subsequent calls return the cached handle. Throws if the resource is missing or NanoVG
     * fails to decode the bytes — both indicate a packaging bug, never an end-user issue.
     */
    fun loadImage(resourcePath: String): Int {
        imageCache[resourcePath]?.let { return it }
        val bytes =
            javaClass.classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
                ?: error("NvgRenderer.loadImage: resource not found on classpath: $resourcePath")
        val buf = MemoryUtil.memAlloc(bytes.size)
        buf.put(bytes).flip()
        return try {
            val handle = nvgCreateImageMem(vg, 0, buf)
            require(handle != 0) { "NvgRenderer.loadImage: nvgCreateImageMem failed for $resourcePath" }
            imageCache[resourcePath] = handle
            handle
        } finally {
            MemoryUtil.memFree(buf)
        }
    }

    /**
     * Paint an image (loaded via [loadImage]) into the rect `[x, y, x+w, y+h]`. [alpha] is a
     * multiplier on the image's own alpha channel — useful for hover effects (e.g. dim icons
     * at 0.7 idle and 1.0 on hover).
     */
    fun image(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        imageHandle: Int,
        alpha: Float = 1f,
    ) {
        nvgImagePattern(vg, x, y, w, h, 0f, imageHandle, alpha.coerceIn(0f, 1f), nvgPaint)
        nvgBeginPath(vg)
        nvgRect(vg, x, y, w, h)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    fun circle(
        x: Float,
        y: Float,
        radius: Float,
        color: Int,
    ) {
        nvgBeginPath(vg)
        nvgCircle(vg, x, y, radius)
        setFillColor(color)
        nvgFill(vg)
    }

    /**
     * Soft drop shadow centered on the rectangle [x, y, x+width, y+height].
     *
     * [blur] is the soft-falloff radius. [spread] expands the inner edge of the shadow outward
     * before the blur is applied; useful for thick "glow"-style shadows. [radius] should match
     * the corner radius of whatever shape is being shadowed.
     */
    fun dropShadow(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        blur: Float,
        spread: Float,
        radius: Float,
    ) {
        nvgRGBA(0, 0, 0, 125, nvgColor)
        nvgRGBA(0, 0, 0, 0, nvgColor2)

        nvgBoxGradient(
            vg,
            x - spread,
            y - spread,
            width + 2 * spread,
            height + 2 * spread,
            radius + spread,
            blur,
            nvgColor,
            nvgColor2,
            nvgPaint,
        )
        nvgBeginPath(vg)
        nvgRoundedRect(
            vg,
            x - spread - blur,
            y - spread - blur,
            width + 2 * spread + 2 * blur,
            height + 2 * spread + 2 * blur,
            radius + spread,
        )
        nvgRoundedRect(vg, x, y, width, height, radius)
        nvgPathWinding(vg, NVG_HOLE)
        nvgFillPaint(vg, nvgPaint)
        nvgFill(vg)
    }

    /**
     * Per-corner rounded rect. Any radius of `0f` produces a square corner. Used to round
     * just the corners of an inner panel that meet a parent's outer rounded edge (e.g. a
     * sidebar's bottom-left rounded to match the card's bottom-left, but bottom-right
     * square because it meets the body across a vertical edge).
     */
    fun cornerRoundedRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Int,
        topLeft: Float = 0f,
        topRight: Float = 0f,
        bottomRight: Float = 0f,
        bottomLeft: Float = 0f,
    ) {
        nvgBeginPath(vg)
        // Start at the top edge, just after the top-left corner.
        nvgMoveTo(vg, x + topLeft, y)
        nvgLineTo(vg, x + w - topRight, y)
        if (topRight > 0f) {
            nvgArcTo(vg, x + w, y, x + w, y + topRight, topRight)
        } else {
            nvgLineTo(vg, x + w, y)
        }
        nvgLineTo(vg, x + w, y + h - bottomRight)
        if (bottomRight > 0f) {
            nvgArcTo(vg, x + w, y + h, x + w - bottomRight, y + h, bottomRight)
        } else {
            nvgLineTo(vg, x + w, y + h)
        }
        nvgLineTo(vg, x + bottomLeft, y + h)
        if (bottomLeft > 0f) {
            nvgArcTo(vg, x, y + h, x, y + h - bottomLeft, bottomLeft)
        } else {
            nvgLineTo(vg, x, y + h)
        }
        nvgLineTo(vg, x, y + topLeft)
        if (topLeft > 0f) {
            nvgArcTo(vg, x, y, x + topLeft, y, topLeft)
        } else {
            nvgLineTo(vg, x, y)
        }
        nvgClosePath(vg)
        setFillColor(color)
        nvgFill(vg)
    }

    /**
     * Rounded rect with separate top + bottom radii — useful for cards joined to other UI.
     * [roundTop] = true: round top corners only; false: round bottom corners only.
     */
    fun halfRoundedRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Int,
        radius: Float,
        roundTop: Boolean,
    ) {
        nvgBeginPath(vg)
        if (roundTop) {
            nvgMoveTo(vg, x, y + h)
            nvgLineTo(vg, x + w, y + h)
            nvgLineTo(vg, x + w, y + radius)
            nvgArcTo(vg, x + w, y, x + w - radius, y, radius)
            nvgLineTo(vg, x + radius, y)
            nvgArcTo(vg, x, y, x, y + radius, radius)
            nvgLineTo(vg, x, y + h)
        } else {
            nvgMoveTo(vg, x, y)
            nvgLineTo(vg, x + w, y)
            nvgLineTo(vg, x + w, y + h - radius)
            nvgArcTo(vg, x + w, y + h, x + w - radius, y + h, radius)
            nvgLineTo(vg, x + radius, y + h)
            nvgArcTo(vg, x, y + h, x, y + h - radius, radius)
            nvgLineTo(vg, x, y)
        }
        nvgClosePath(vg)
        setFillColor(color)
        nvgFill(vg)
    }

    // ─────────────────────────── text ───────────────────────────
    fun text(
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        font: NvgFont = defaultFont,
    ) {
        nvgFontSize(vg, size)
        nvgFontFaceId(vg, getFontId(font))
        setFillColor(color)
        // +.5 on Y compensates for stb_truetype sub-pixel rounding when y is non-integer.
        nvgText(vg, x, y + .5f, text)
    }

    fun textShadow(
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        font: NvgFont = defaultFont,
    ) {
        nvgFontFaceId(vg, getFontId(font))
        nvgFontSize(vg, size)
        // **Tight 1 px shadow at 70 % alpha black, plain two-pass.**
        //
        // The +1 offset works because HUD text defaults to a heavier Inter weight
        // (Regular → SemiBold via [boldVariantOf] when `hudBoldFont` is on, the
        // default). Heavier strokes are 3–4 px thick at 11–12 px body size, so the
        // anti-aliased edge is only ~0.5 px and the halo crossover region shrinks
        // to a sliver. 70 % alpha softens what little residual variance remains.
        //
        // **Round ONCE, then add the offset.** `kotlin.math.round` uses banker's
        // rounding (half-to-even). At fractional `y` values — which happen at
        // non-integer effective scales, fractional panel origins, or fractional
        // row spacing — `round(y)` and `round(y + 1f)` can differ by **0, 1, or 2**
        // depending on which side of the half-pixel each value lands on, e.g.
        // `round(23.5)=24, round(24.5)=24` → delta 0, or `round(24.5)=24,
        // round(25.5)=26` → delta 2. The visible bug was every-second-row
        // alternating between a tight and a 2 px-spread shadow. Locking the rounded
        // base position and offsetting from there keeps the delta at exactly 1 px
        // regardless of sub-pixel alignment.
        //
        // Don't reintroduce `NVG_DESTINATION_OUT` cleanup or `nvgFontBlur` for the
        // shadow pass — both produce non-deterministic per-row dropouts under
        // HUD-text load. See the "Text shadow rendering" note in CLAUDE.md.
        val sx = round(x)
        val sy = round(y)
        setFillColor(0xB3000000.toInt())
        nvgText(vg, sx + 1f, sy + 1f, text)
        setFillColor(color)
        nvgText(vg, sx, sy, text)
    }

    fun textWidth(
        text: String,
        size: Float,
        font: NvgFont = defaultFont,
    ): Float {
        nvgFontSize(vg, size)
        nvgFontFaceId(vg, getFontId(font))
        return nvgTextBounds(vg, 0f, 0f, text, fontBounds)
    }

    fun drawWrappedString(
        text: String,
        x: Float,
        y: Float,
        w: Float,
        size: Float,
        color: Int,
        font: NvgFont = defaultFont,
        lineHeight: Float = 1f,
    ) {
        nvgFontSize(vg, size)
        nvgFontFaceId(vg, getFontId(font))
        nvgTextLineHeight(vg, lineHeight)
        setFillColor(color)
        nvgTextBox(vg, x, y, w, text)
    }

    /** Returns `[minX, minY, maxX, maxY]` bounding box of a wrapped paragraph. */
    fun wrappedTextBounds(
        text: String,
        w: Float,
        size: Float,
        font: NvgFont = defaultFont,
        lineHeight: Float = 1f,
    ): FloatArray {
        val bounds = FloatArray(4)
        nvgFontSize(vg, size)
        nvgFontFaceId(vg, getFontId(font))
        nvgTextLineHeight(vg, lineHeight)
        nvgTextBoxBounds(vg, 0f, 0f, w, text, bounds)
        return bounds
    }

    // ─────────────────────────── internals ───────────────────────────

    private fun setFillColor(color: Int) {
        nvgRGBA(
            ((color shr 16) and 0xFF).toByte(),
            ((color shr 8) and 0xFF).toByte(),
            (color and 0xFF).toByte(),
            ((color shr 24) and 0xFF).toByte(),
            nvgColor,
        )
        nvgFillColor(vg, nvgColor)
    }

    private fun setStrokeColor(color: Int) {
        nvgRGBA(
            ((color shr 16) and 0xFF).toByte(),
            ((color shr 8) and 0xFF).toByte(),
            (color and 0xFF).toByte(),
            ((color shr 24) and 0xFF).toByte(),
            nvgColor,
        )
        nvgStrokeColor(vg, nvgColor)
    }

    private fun applyGradient(
        color1: Int,
        color2: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        direction: NvgGradient,
    ) {
        nvgRGBA(
            ((color1 shr 16) and 0xFF).toByte(),
            ((color1 shr 8) and 0xFF).toByte(),
            (color1 and 0xFF).toByte(),
            ((color1 shr 24) and 0xFF).toByte(),
            nvgColor,
        )
        nvgRGBA(
            ((color2 shr 16) and 0xFF).toByte(),
            ((color2 shr 8) and 0xFF).toByte(),
            (color2 and 0xFF).toByte(),
            ((color2 shr 24) and 0xFF).toByte(),
            nvgColor2,
        )
        when (direction) {
            NvgGradient.LeftToRight ->
                nvgLinearGradient(vg, x, y, x + w, y, nvgColor, nvgColor2, nvgPaint)
            NvgGradient.TopToBottom ->
                nvgLinearGradient(vg, x, y, x, y + h, nvgColor, nvgColor2, nvgPaint)
        }
    }

    private fun getFontId(font: NvgFont): Int {
        return fontMap.getOrPut(font) {
            val buffer = font.buffer()
            // freeData = false: NanoVG retains the pointer; we keep the strong reference in NvgFontHandle.
            val id = nvgCreateFontMem(vg, font.name, buffer, false)
            val sizeBytes = buffer.remaining()
            if (id == -1) {
                logger.warn("nvgCreateFontMem returned -1 for '${font.name}' (size=$sizeBytes B) — text will not render")
            } else {
                logger.info("Registered font '${font.name}' as id=$id (TTF size=$sizeBytes bytes)")
            }
            NvgFontHandle(id, buffer)
        }.id
    }

    private class Scissor(
        val previous: Scissor?,
        val x: Float,
        val y: Float,
        val maxX: Float,
        val maxY: Float,
    ) {
        fun applyScissor(vg: Long) {
            if (previous == null) {
                nvgScissor(vg, x, y, maxX - x, maxY - y)
            } else {
                val ix = max(x, previous.x)
                val iy = max(y, previous.y)
                val iw = max(0f, (min(maxX, previous.maxX) - ix))
                val ih = max(0f, (min(maxY, previous.maxY) - iy))
                nvgScissor(vg, ix, iy, iw, ih)
            }
        }
    }

    private data class NvgFontHandle(val id: Int, val buffer: ByteBuffer)
}

/** Linear-gradient direction for [NvgRenderer.gradientRect]. */
enum class NvgGradient {
    LeftToRight,
    TopToBottom,
}

/** Intersected scissor rectangle in logical units (matches [NvgRenderer.currentScissorBounds]). */
data class ScissorBounds(val x: Float, val y: Float, val maxX: Float, val maxY: Float)
