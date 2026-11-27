package com.soulreturns.config.gui.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import com.soulreturns.config.cfg
import com.soulreturns.util.SoulLogger
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Crisp MSDF text rendering, scale-independent. Lays a string out with [MsdfFont.layout] and submits
 * the glyph quads as one [MsdfTextRenderState] per atlas onto the extractor's `guiRenderState`.
 *
 * Coordinates match `GuiGraphicsExtractor` conventions: `x, y` is the text's top-left, and `size`
 * is a nominal size on the vanilla font's scale (size 8 = vanilla at 1×): every family shares the
 * vanilla baseline, cap height and line height, so Soul text and Minecraft-font text are
 * interchangeable in a layout (see [MsdfFont]). The current pose is copied so text scales with the UI.
 */
object SoulText {
    private val logger = SoulLogger("Soul/Text")

    @Volatile private var debugLogged = false

    private fun textureSetup(font: MsdfFont): TextureSetup? =
        try {
            val tex = Minecraft.getInstance().textureManager.getTexture(font.atlas)
            // getTextureView() THROWS if the texture isn't uploaded yet — guard it.
            val view: GpuTextureView = tex.textureView
            val sampler = RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                false,
            )
            TextureSetup.singleTexture(view, sampler)
        } catch (t: Throwable) {
            if (!debugLogged) logger.warn("MSDF atlas texture not ready", t)
            null
        }

    /**
     * Whether to render with a vector (MSDF) font. Forced on for the config menu (`forceCustom`);
     * otherwise honors `general.fontChoice` (Minecraft ⇒ the texture-pack font via [drawMc], any
     * other choice ⇒ the selected MSDF family). Config not ready (early startup) ⇒ default to custom.
     */
    private fun useCustom(forceCustom: Boolean, choiceOverride: Int? = null): Boolean = forceCustom ||
        try {
            (choiceOverride ?: cfg.general.fontChoice) != MsdfFonts.MINECRAFT
        } catch (_: Throwable) {
            true
        }

    /** MC font scale so its line matches an MSDF nominal [size] (size 8 = vanilla at 1×). */
    private fun mcScale(size: Float): Float = size / 8f

    fun width(
        text: String,
        size: Float,
        font: MsdfFont = MsdfFonts.regular,
        forceCustom: Boolean = false,
        fontChoice: Int? = null,
    ): Int = if (useCustom(forceCustom, fontChoice)) {
        font.width(text, size)
    } else {
        (Minecraft.getInstance().font.width(text) * mcScale(size)).toInt()
    }

    /** Draw [text] with its top-left at ([x], [y]) at nominal [size], with a drop shadow. */
    fun draw(
        g: GuiGraphicsExtractor,
        text: String,
        x: Int,
        y: Int,
        size: Float,
        color: Int,
        font: MsdfFont = MsdfFonts.regular,
        forceCustom: Boolean = false,
        shadow: Boolean = true,
        scissor: ScreenRectangle? = null,
        fontChoice: Int? = null,
    ) {
        if (text.isEmpty()) return
        if (!useCustom(forceCustom, fontChoice)) {
            drawMc(g, text, x, y, size, color, shadow)
            return
        }
        if (!font.loaded) return
        drawRun(g, text, snapX(g, x.toFloat()), y, size, color, font, shadow, scissor)
    }

    /** Logical [x] moved onto the nearest whole physical pixel under the current pose and GUI scale. */
    fun snapX(g: GuiGraphicsExtractor, x: Float): Float = g.pose().let { snap(x, it.m00(), it.m20()) }

    /**
     * One line drawn as several runs (colors, weights) from ([x], [y]), laid out exactly like the same
     * characters in a single [draw]: one snapped origin, each run at its fractional [RunPen] offset.
     * Font choice as [draw] ([forceCustom], [fontChoice]).
     */
    class Line(
        private val g: GuiGraphicsExtractor,
        x: Int,
        private val y: Int,
        private val size: Float,
        forceCustom: Boolean = false,
        fontChoice: Int? = null,
    ) {
        private val custom = useCustom(forceCustom, fontChoice)
        private val origin = if (custom) snapX(g, x.toFloat()) else x.toFloat()
        private val pen = RunPen(size)

        /** Where the next run starts. */
        val x: Float get() = origin + pen.pen

        fun text(text: String, color: Int, font: MsdfFont = MsdfFonts.regular, shadow: Boolean = true, scissor: ScreenRectangle? = null) {
            if (text.isEmpty()) return
            if (custom) {
                val rx = origin + pen.place(text, font.widthF(text, size), font)
                drawRun(g, text, rx, y, size, color, font, shadow, scissor)
            } else {
                val rx = origin + pen.place(text, Minecraft.getInstance().font.width(text) * mcScale(size), null)
                drawMc(g, text, rx.roundToInt(), y, size, color, shadow)
            }
        }

        /** Skip [px] (an icon, a fixed gap). */
        fun gap(px: Float) = pen.gap(px)
    }

    /**
     * MSDF [text] at exactly [originX] (no snap: a run inside a line whose origin was snapped once, see
     * [RunPen]); the baseline snaps like [draw].
     */
    fun drawRun(
        g: GuiGraphicsExtractor,
        text: String,
        originX: Float,
        y: Int,
        size: Float,
        color: Int,
        font: MsdfFont,
        shadow: Boolean = true,
        scissor: ScreenRectangle? = null,
    ) {
        if (text.isEmpty() || !font.loaded) return
        val setup = textureSetup(font) ?: return
        // Snap the baseline (and, in [draw], the origin) to whole physical pixels: a baseline between
        // two pixel rows renders every letter's bottom edge half-covered, so text looks soft and rows
        // of text wobble against each other. Advances stay fractional so spacing (and kerning) survive.
        val pose = g.pose()
        val baseline = snap(y + font.baselinePx(size), pose.m11(), pose.m21())
        val quads = ArrayList<MsdfTextRenderState.Quad>(text.length)
        var fbQuads: ArrayList<MsdfTextRenderState.Quad>? = null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        font.layout(text, size) { glyph, owner, penX, em ->
            val pb = glyph.planeBounds
            val ab = glyph.atlasBounds
            if (pb != null && ab != null) {
                val aW = owner.data.atlas.width.toFloat()
                val aH = owner.data.atlas.height.toFloat()
                val quad = MsdfTextRenderState.Quad(
                    x0 = originX + penX + pb.left * em,
                    y0 = baseline + pb.top * em,
                    x1 = originX + penX + pb.right * em,
                    y1 = baseline + pb.bottom * em,
                    u0 = ab.left / aW,
                    v0 = ab.top / aH,
                    u1 = ab.right / aW,
                    v1 = ab.bottom / aH,
                )
                if (quad.x0 < minX) minX = quad.x0
                if (quad.y0 < minY) minY = quad.y0
                if (quad.x1 > maxX) maxX = quad.x1
                if (quad.y1 > maxY) maxY = quad.y1
                // Codepoints this family lacks come from the fallback's atlas: a second draw,
                // since a render state samples one texture.
                if (owner === font) quads.add(quad) else (fbQuads ?: ArrayList<MsdfTextRenderState.Quad>().also { fbQuads = it }).add(quad)
            }
        }
        if (minX > maxX) return
        val inkBounds = ScreenRectangle(
            floor(minX).toInt(),
            floor(minY).toInt(),
            (ceil(maxX) - floor(minX)).toInt().coerceAtLeast(1),
            (ceil(maxY) - floor(minY)).toInt().coerceAtLeast(1),
        )

        // Emits one quad run (shadow pass first, then the main text on top) with its own atlas [setup].
        // The shadow sits down-right in proportion to the text as drawn ([TextShadow]), whatever Minecraft's
        // GUI scale is. Non-null bounds: the GUI renderer culls a null.
        fun emit(list: List<MsdfTextRenderState.Quad>, texSetup: TextureSetup) {
            if (list.isEmpty()) return
            if (shadow) {
                val scaleX = kotlin.math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01())
                val off = TextShadow.poseOffset(size, scaleX, Minecraft.getInstance().window.guiScale.toFloat(), MsdfFonts.isSemibold(font))
                val sPose = Matrix3x2f(pose).translate(off, off)
                g.guiRenderState.addGuiElement(
                    MsdfTextRenderState(SoulRenderPipelines.MSDF_TEXT, texSetup, sPose, list, TextShadow.color(color), scissor, inkBounds.transformMaxBounds(sPose)),
                )
            }
            val mainPose = Matrix3x2f(pose)
            g.guiRenderState.addGuiElement(
                MsdfTextRenderState(SoulRenderPipelines.MSDF_TEXT, texSetup, mainPose, list, color, scissor, inkBounds.transformMaxBounds(mainPose)),
            )
        }

        emit(quads, setup)
        fbQuads?.let { list -> font.fallback?.let { textureSetup(it) }?.let { emit(list, it) } }
    }

    /** A copy of a text run drawn behind it (glows, shadows): offset in the pose's units, color, soft or crisp edge. */
    class Copy(val dx: Float, val dy: Float, val color: Int, val soft: Boolean)

    /**
     * Draw [text] (MSDF [font] only, no fallback glyphs) with its top-left at ([x], [y]), [tracking] px
     * added after every glyph (CSS letter-spacing), over [copies] of the same glyph run: one layout,
     * one quad list for every copy. Fractional positions; the origin snaps like [draw].
     */
    fun drawWithCopies(
        g: GuiGraphicsExtractor,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        font: MsdfFont,
        tracking: Float,
        copies: List<Copy>,
    ) {
        if (text.isEmpty() || !font.loaded) return
        val setup = textureSetup(font) ?: return
        val pose = g.pose()
        val originX = snap(x, pose.m00(), pose.m20())
        val baseline = snap(y + font.baselinePx(size), pose.m11(), pose.m21())
        val quads = ArrayList<MsdfTextRenderState.Quad>(text.length)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var index = 0
        font.layout(text, size) { glyph, owner, penX, em ->
            val pb = glyph.planeBounds
            val ab = glyph.atlasBounds
            if (owner === font && pb != null && ab != null) {
                val aW = owner.data.atlas.width.toFloat()
                val aH = owner.data.atlas.height.toFloat()
                val px = originX + penX + index * tracking
                val q = MsdfTextRenderState.Quad(px + pb.left * em, baseline + pb.top * em, px + pb.right * em, baseline + pb.bottom * em, ab.left / aW, ab.top / aH, ab.right / aW, ab.bottom / aH)
                minX = min(minX, q.x0)
                minY = min(minY, q.y0)
                maxX = max(maxX, q.x1)
                maxY = max(maxY, q.y1)
                quads.add(q)
            }
            index++
        }
        if (quads.isEmpty()) return
        val ink = ScreenRectangle(floor(minX).toInt(), floor(minY).toInt(), (ceil(maxX) - floor(minX)).toInt().coerceAtLeast(1), (ceil(maxY) - floor(minY)).toInt().coerceAtLeast(1))
        for (c in copies) {
            val cPose = Matrix3x2f(pose).translate(c.dx, c.dy)
            val pipeline = if (c.soft) SoulRenderPipelines.MSDF_SOFT else SoulRenderPipelines.MSDF_TEXT
            g.guiRenderState.addGuiElement(MsdfTextRenderState(pipeline, setup, cPose, quads, c.color, null, ink.transformMaxBounds(cPose)))
        }
        val mainPose = Matrix3x2f(pose)
        g.guiRenderState.addGuiElement(MsdfTextRenderState(SoulRenderPipelines.MSDF_TEXT, setup, mainPose, quads, color, null, ink.transformMaxBounds(mainPose)))
    }

    /**
     * Snap a logical coordinate so it lands on a whole physical pixel under [scale] + [translate]
     * (one axis of the current pose) and Minecraft's GUI scale.
     */
    private fun snap(v: Float, scale: Float, translate: Float): Float {
        if (kotlin.math.abs(scale) < 1e-4f) return v
        val guiScale = Minecraft.getInstance().window.guiScale.toFloat()
        val physical = ((v * scale + translate) * guiScale).roundToInt()
        return (physical / guiScale - translate) / scale
    }

    /** Draw [text] horizontally centered on [cx]. */
    fun drawCentered(
        g: GuiGraphicsExtractor,
        text: String,
        cx: Int,
        y: Int,
        size: Float,
        color: Int,
        font: MsdfFont = MsdfFonts.regular,
        forceCustom: Boolean = false,
        shadow: Boolean = true,
        scissor: ScreenRectangle? = null,
        fontChoice: Int? = null,
    ) = draw(g, text, centeredX(text, cx, size, font, forceCustom, fontChoice), y, size, color, font, forceCustom, shadow, scissor, fontChoice)

    /** Left x that visually centers [text] on [cx] — by ink bounds for MSDF, by advance for MC font. */
    private fun centeredX(text: String, cx: Int, size: Float, font: MsdfFont, forceCustom: Boolean, fontChoice: Int? = null): Int {
        val half = if (useCustom(forceCustom, fontChoice)) font.inkCenterOffsetPx(text, size) else width(text, size, font, forceCustom, fontChoice) / 2f
        return (cx - half).roundToInt()
    }

    /**
     * Draw [text] centered on ([cx], [cy]) both horizontally AND vertically, using the font's real
     * glyph bounds for the vertical center (so it lands dead-center regardless of font/ascender).
     */
    fun drawCenteredOn(
        g: GuiGraphicsExtractor,
        text: String,
        cx: Int,
        cy: Int,
        size: Float,
        color: Int,
        font: MsdfFont = MsdfFonts.regular,
        forceCustom: Boolean = false,
        shadow: Boolean = true,
        scissor: ScreenRectangle? = null,
        fontChoice: Int? = null,
        tight: Boolean = false,
    ) = drawVCentered(g, text, centeredX(text, cx, size, font, forceCustom, fontChoice), cy, size, color, font, forceCustom, shadow, scissor, fontChoice, tight)

    /** Width of [text] with [tracking] px added between glyphs (MSDF fonts only). */
    fun widthTracked(text: String, size: Float, font: MsdfFont, tracking: Float): Float =
        font.widthF(text, size) + tracking * (text.length - 1).coerceAtLeast(0)

    /**
     * Draw [text] centered on ([cx], [cy]) like [drawCenteredOn], with [tracking] px between glyphs
     * (CSS letter-spacing). Each glyph is placed at its fractional advance through the pose, so the
     * spacing stays even; kerning between glyphs is lost, fine for short caps labels (chips).
     */
    fun drawCenteredTracked(
        g: GuiGraphicsExtractor,
        text: String,
        cx: Int,
        cy: Int,
        size: Float,
        color: Int,
        font: MsdfFont,
        tracking: Float,
        shadow: Boolean = true,
        scissor: ScreenRectangle? = null,
    ) {
        var x = cx - font.inkCenterOffsetPx(text, size) - tracking * (text.length - 1) / 2f
        for (ch in text) {
            val s = ch.toString()
            g.pose().pushMatrix()
            g.pose().translate(x, 0f)
            drawVCentered(g, s, 0, cy, size, color, font, forceCustom = true, shadow = shadow, scissor = scissor)
            g.pose().popMatrix()
            x += font.widthF(s, size) + tracking
        }
    }

    /**
     * Draw [text] left-aligned at [x] but *vertically centered* on [cy], using the font's real glyph
     * bounds — so text in a fixed-height row/box (labels, dropdowns, search, category rows) sits
     * dead-center regardless of the font's ascender.
     */
    fun drawVCentered(
        g: GuiGraphicsExtractor,
        text: String,
        x: Int,
        cy: Int,
        size: Float,
        color: Int,
        font: MsdfFont = MsdfFonts.regular,
        forceCustom: Boolean = false,
        shadow: Boolean = true,
        scissor: ScreenRectangle? = null,
        fontChoice: Int? = null,
        tight: Boolean = false,
    ) {
        val top = if (useCustom(forceCustom, fontChoice)) {
            // tight = center on THIS string's ink (best for a lone label); else the cap band, which
            // is string-independent so rows of labels (tabs, categories) share one baseline.
            val off = if (tight) font.tightCenterOffsetPx(text, size) else font.centerOffsetPx(size)
            (cy - off).roundToInt()
        } else {
            // MC font: a line is ~7px of a 9px cell; center its cap band.
            (cy - mcScale(size) * 4f).roundToInt()
        }
        draw(g, text, x, top, size, color, font, forceCustom, shadow, scissor, fontChoice)
    }

    /**
     * Draw [lines] as a vertically-stacked block whose *block* is horizontally centered on [cx],
     * with every line sharing one left origin — so wrapped words that start alike (e.g. Tic/Tac/Toe)
     * line up instead of each centering on its own width. A single line is centered normally.
     * Line vertical centers are [firstCy], firstCy+[step], …
     */
    fun drawBlockCentered(
        g: GuiGraphicsExtractor,
        lines: List<String>,
        cx: Int,
        firstCy: Int,
        step: Int,
        size: Float,
        color: Int,
        font: MsdfFont = MsdfFonts.regular,
        forceCustom: Boolean = false,
        scissor: ScreenRectangle? = null,
        fontChoice: Int? = null,
        tight: Boolean = false,
    ) {
        // Each line individually centered on cx; even integer [step] keeps inter-line gaps identical.
        var cy = firstCy
        for (l in lines) {
            drawCenteredOn(g, l, cx, cy, size, color, font, forceCustom, true, scissor, fontChoice, tight)
            cy += step
        }
    }

    /** Minecraft-font fallback (uses the active texture pack), scaled to roughly match [size]. */
    private fun drawMc(g: GuiGraphicsExtractor, text: String, x: Int, y: Int, size: Float, color: Int, shadow: Boolean) {
        val f = Minecraft.getInstance().font
        val scale = mcScale(size)
        g.pose().pushMatrix()
        g.pose().scale(scale, scale)
        mcText(g, f, text, false, (x / scale).roundToInt(), (y / scale).roundToInt(), color, shadow)
        g.pose().popMatrix()
    }

    /**
     * Vanilla text at ([x], [y]) of the current pose. Vanilla's text pipeline cannot take a chroma color ([Chroma]),
     * so chroma text goes letter by letter, each in the rainbow's color at its center.
     */
    internal fun mcText(g: GuiGraphicsExtractor, f: Font, text: String, bold: Boolean, x: Int, y: Int, color: Int, shadow: Boolean) {
        val style = Style.EMPTY.withBold(bold)
        if (!Chroma.isChroma(color)) {
            g.text(f, Component.literal(text).withStyle(style), x, y, color, shadow)
            return
        }
        var px = x
        var i = 0
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            val letter = Component.literal(text.substring(i, i + n)).withStyle(style)
            val w = f.width(letter)
            g.text(f, letter, px, y, Chroma.flatten(color, g.pose(), px + w / 2f, y + 4f), shadow)
            px += w
            i += n
        }
    }
}
