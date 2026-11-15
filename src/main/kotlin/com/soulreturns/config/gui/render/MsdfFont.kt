package com.soulreturns.config.gui.render

import com.soulreturns.config.cfg
import com.soulreturns.util.SoulLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.resources.Identifier

/**
 * One MSDF font weight, parsed from an msdf-atlas-gen JSON + its atlas PNG. One instance per weight
 * (see [MsdfFonts]). JSON units: `advance` / `planeBounds` / kerning in em, `atlasBounds` in atlas
 * pixels, yOrigin = top (`top` < `bottom`, +y down).
 *
 * **Metrics are normalized to the vanilla font**, so any family drops in wherever Minecraft's font
 * (or a texture-pack font) would go. At nominal [size] `s` — the size at which the vanilla font is
 * drawn at scale `s / 8` — every family has the vanilla cap height (`7/8 s`), the vanilla baseline
 * (`7/8 s` below the text's top, so capitals start exactly at the top) and the vanilla line height
 * (`9/8 s`). Each family's em is scaled by [scale] to reach that cap height: families differ by up
 * to 15% in how much of the em their capitals fill, so equal em sizes looked like different sizes,
 * and anchoring on the font's own ascender put Baloo 2's baseline 1.08 em down (text sat low and
 * spilled into the next row). Lowercase ends up a little taller than vanilla's (5.4 vs 5 px at 1×)
 * because smooth fonts have proportionally larger x-heights; matching the x-height instead left
 * every capital ~9% shorter than vanilla's, which read as a smaller font.
 */
class MsdfFont(
    private val jsonResource: String,
    /** Atlas texture identifier — the PNG is lazily loaded by the texture manager on first use. */
    val atlas: Identifier,
) {
    private val logger = SoulLogger("Soul/Font")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class AtlasInfo(val distanceRange: Float = 4f, val size: Float = 40f, val width: Int = 0, val height: Int = 0)

    @Serializable
    data class Metrics(val emSize: Float = 1f, val lineHeight: Float = 1.2f, val ascender: Float = 0f, val descender: Float = 0f)

    @Serializable
    data class Bounds(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

    @Serializable
    data class Glyph(
        val unicode: Int,
        val advance: Float = 0f,
        val planeBounds: Bounds? = null,
        val atlasBounds: Bounds? = null,
    )

    /** msdf-atlas-gen's kerning entry; written by `scripts/msdf_kerning.py` from the font's GPOS. */
    @Serializable
    data class KerningPair(val unicode1: Int, val unicode2: Int, val advance: Float)

    @Serializable
    data class FontData(
        val atlas: AtlasInfo,
        val metrics: Metrics,
        val glyphs: List<Glyph>,
        val kerning: List<KerningPair> = emptyList(),
    )

    lateinit var data: FontData
        private set

    @PublishedApi internal var glyphs: Map<Int, Glyph> = emptyMap()
    @PublishedApi internal var kerning: Map<Long, Float> = emptyMap()

    /** Em multiplier that gives this family the vanilla cap height; 1 until loaded. */
    @PublishedApi internal var scale = 1f
    var loaded = false
        private set

    /**
     * Fallback font for codepoints this atlas lacks (set once in [MsdfFonts.init]). Baloo 2 / Nunito
     * don't ship glyphs like `↺` (reset), `→`, `✓` — Inter does, so those families fall back to Inter
     * per-glyph. Inter itself has no fallback. [SoulText] renders fallback glyphs from the fallback's
     * atlas in a second draw; measurement here consults it so widths/centering stay accurate.
     */
    var fallback: MsdfFont? = null

    fun init() {
        if (loaded) return
        try {
            val stream = javaClass.getResourceAsStream(jsonResource)
                ?: error("$jsonResource not found on classpath")
            data = json.decodeFromString<FontData>(stream.bufferedReader().use { it.readText() })
            glyphs = data.glyphs.associateBy { it.unicode }
            kerning = data.kerning.associate { pairKey(it.unicode1, it.unicode2) to it.advance }
            val capHeight = glyphs['H'.code]?.planeBounds?.top?.let { -it }
            scale = if (capHeight != null && capHeight > 0f) CAP_HEIGHT / capHeight else 1f
            loaded = true
            logger.info("MSDF font loaded: $jsonResource, ${glyphs.size} glyphs, atlas ${data.atlas.width}x${data.atlas.height}")
        } catch (t: Throwable) {
            logger.warn("Failed to load MSDF font $jsonResource", t)
        }
    }

    /** This atlas's own glyph for [codepoint] (no fallback), or null. */
    fun glyph(codepoint: Int): Glyph? = glyphs[codepoint]

    /** The fallback font's glyph for a [codepoint] this atlas lacks — else null (own glyph wins). */
    fun fallbackGlyph(codepoint: Int): Glyph? =
        if (glyphs.containsKey(codepoint)) null else fallback?.takeIf { it.loaded }?.glyphs?.get(codepoint)

    /** Whether this family (or its [fallback]) can draw [codepoint]; anything else draws nothing. */
    fun covers(codepoint: Int): Boolean =
        glyphs.containsKey(codepoint) || fallback?.takeIf { it.loaded }?.glyphs?.containsKey(codepoint) == true

    /**
     * Lays out [text] at nominal [size]: calls [visit] with each drawable glyph, the font that owns
     * it (this one or the [fallback]), its pen x in px from the origin, and its px-per-em. Applies
     * kerning between neighbors of this font. Returns the total advance in px. The one layout every
     * measurement and [SoulText.draw] share, so what is measured is exactly what is drawn.
     */
    inline fun layout(text: String, size: Float, visit: (glyph: Glyph, owner: MsdfFont, penX: Float, em: Float) -> Unit): Float {
        if (!loaded) return 0f
        val em = scale * size
        val fb = fallback?.takeIf { it.loaded }
        var pen = 0f
        var prev = -1
        for (ch in text) {
            val cp = ch.code
            val own = glyphs[cp]
            if (own != null) {
                if (prev >= 0) pen += (kerning[pairKey(prev, cp)] ?: 0f) * em
                visit(own, this, pen, em)
                pen += own.advance * em
                prev = cp
                continue
            }
            prev = -1
            val fg = fb?.glyphs?.get(cp)
            if (fb != null && fg != null) {
                val fbEm = fb.scale * size
                visit(fg, fb, pen, fbEm)
                pen += fg.advance * fbEm
            } else {
                pen += 0.5f * size
            }
        }
        return pen
    }

    /** Advance width of [text] at nominal [size], unrounded. */
    fun widthF(text: String, size: Float): Float = layout(text, size) { _, _, _, _ -> }

    /** Advance width of [text] at nominal [size], rounded the same way [SoulText.draw] snaps. */
    fun width(text: String, size: Float): Int = kotlin.math.round(widthF(text, size)).toInt()

    /**
     * Horizontal offset from the text origin (the `x` passed to [SoulText.draw]) to the *ink* center
     * of [text]. Centering on this (rather than half the advance width) keeps text visually centered
     * even when the first/last glyphs have asymmetric side-bearings.
     */
    fun inkCenterOffsetPx(text: String, size: Float): Float {
        val (l, r) = inkBounds(text, size)
        return (l + r) / 2f
    }

    /** The ink's [left, right] extent relative to the text origin, in px (for exact centering/alignment). */
    fun inkBounds(text: String, size: Float): Pair<Float, Float> {
        if (!loaded) return 0f to width(text, size).toFloat()
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        val pen = layout(text, size) { glyph, _, penX, em ->
            glyph.planeBounds?.let {
                val l = penX + it.left * em
                val r = penX + it.right * em
                if (l < left) left = l
                if (r > right) right = r
            }
        }
        return if (left > right) 0f to pen else left to right
    }

    /** Baseline offset from the text's top edge at nominal [size]: the vanilla 7/8. */
    fun baselinePx(size: Float): Float = BASELINE * size

    /** Line advance at nominal [size]: the vanilla 9/8. */
    fun lineHeightPx(size: Float): Float = LINE_HEIGHT * size

    private var capCenterCache = Float.NaN

    /**
     * Midpoint of the cap band (cap-top → baseline) in em, negative (above baseline). Computed once
     * from reference capitals/digits — deliberately **string-independent** so a row of labels shares
     * one baseline instead of each word shifting by its own ascenders/descenders.
     */
    private fun capCenterEm(): Float {
        if (!capCenterCache.isNaN()) return capCenterCache
        var top = 0f
        for (ch in "HMEBRX0") glyphs[ch.code]?.planeBounds?.let { if (it.top < top) top = it.top }
        capCenterCache = (if (top < 0f) top else -0.7f) / 2f
        return capCenterCache
    }

    /**
     * Distance from the text top (the `y` passed to [SoulText.draw]) to the visual vertical center
     * (the cap band), for centering text in a box. Font-dependent but string-independent, so labels
     * in a row align. Falls back to half the em before the atlas loads.
     */
    fun centerOffsetPx(size: Float): Float = if (loaded) baselinePx(size) + capCenterEm() * scale * size else size * 0.5f

    /**
     * Cap height (baseline → cap top) in px at font size [size], positive. String-independent and
     * cached (via [capCenterEm]); use to size glyph-like decorations (e.g. an icon) to match the
     * font's capitals. Falls back to 0.7·[size] before the atlas loads.
     */
    fun capHeightPx(size: Float): Float = if (loaded) -2f * capCenterEm() * scale * size else size * 0.7f

    /**
     * Like [centerOffsetPx] but centered on *this string's* tight ink box (accounts for its own
     * ascenders/descenders). Use for a single label centered in its own box (a room), not for rows
     * of labels that must share a baseline. Falls back to the cap-band center for space-only strings.
     */
    fun tightCenterOffsetPx(text: String, size: Float): Float {
        if (!loaded) return size * 0.5f
        var minTop = Float.MAX_VALUE
        var maxBottom = -Float.MAX_VALUE
        for (ch in text) {
            glyphs[ch.code]?.planeBounds?.let {
                if (it.top < minTop) minTop = it.top
                if (it.bottom > maxBottom) maxBottom = it.bottom
            }
        }
        if (minTop > maxBottom) return centerOffsetPx(size)
        return baselinePx(size) + (minTop + maxBottom) / 2f * scale * size
    }

    companion object {
        /** Vanilla proportions per nominal size unit: cap height 7/8, baseline 7/8, line 9/8. */
        const val CAP_HEIGHT = 0.875f
        const val BASELINE = 0.875f
        const val LINE_HEIGHT = 1.125f

        /**
         * Converts a size written for the pre-normalization em (layouts tuned on Baloo 2, the
         * default) into a nominal size that keeps that look: Baloo's cap height is 0.688 em.
         */
        const val LEGACY_EM = 0.688f / CAP_HEIGHT

        @PublishedApi
        internal fun pairKey(first: Int, second: Int): Long = (first.toLong() shl 32) or second.toLong()
    }
}

/**
 * The bundled MSDF font families (Inter + Nunito), each with a regular and semibold weight. Call
 * [init] once at startup. [regular] / [semibold] resolve to the family chosen by
 * `general.fontChoice`, so switching the font applies everywhere the UI reads a weight. When the
 * choice is "Minecraft" the config UI still needs a real vector font, so it falls back to Inter
 * (the HUD path uses [SoulText]'s Minecraft-font fallback instead).
 */
object MsdfFonts {
    // Font-choice indices — MUST match the order of the config "Font" dropdown.
    const val BALOO2 = 0
    const val NUNITO = 1
    const val INTER = 2
    const val MINECRAFT = 3

    private fun id(path: String) = Identifier.fromNamespaceAndPath("soul", path)

    private val interRegular = MsdfFont("/assets/soul/msdf/inter_msdf.json", id("textures/font/inter_msdf.png"))
    private val interSemibold = MsdfFont("/assets/soul/msdf/inter_semibold.json", id("textures/font/inter_semibold_msdf.png"))
    private val nunitoRegular = MsdfFont("/assets/soul/msdf/nunito_regular.json", id("textures/font/nunito_regular_msdf.png"))
    private val nunitoSemibold = MsdfFont("/assets/soul/msdf/nunito_semibold.json", id("textures/font/nunito_semibold_msdf.png"))
    // Baloo 2 at its normal weights (Regular + SemiBold), matching Inter/Nunito.
    private val baloo2Regular = MsdfFont("/assets/soul/msdf/baloo2_regular.json", id("textures/font/baloo2_regular_msdf.png"))
    private val baloo2Semibold = MsdfFont("/assets/soul/msdf/baloo2_semibold.json", id("textures/font/baloo2_semibold_msdf.png"))

    /** The selected HUD/overlay font family (defaults to Inter if config isn't ready). */
    fun choice(): Int = try {
        cfg.general.fontChoice
    } catch (_: Throwable) {
        INTER
    }

    /**
     * The selected config-menu font family — always a vector font (Baloo 2 / Nunito / Inter). The
     * menu can't render the Minecraft font, so [MINECRAFT] (or any out-of-range value) resolves to
     * Inter. Read by the config screens' text helpers; independent of the HUD [choice].
     */
    fun menuChoice(): Int = try {
        cfg.general.menuFontChoice
    } catch (_: Throwable) {
        BALOO2
    }

    val regular: MsdfFont get() = regularFor(choice())
    val semibold: MsdfFont get() = semiboldFor(choice())

    /** Config-menu regular weight (vector-only; Minecraft ⇒ Inter). */
    val menuRegular: MsdfFont get() = regularFor(menuChoice())

    /** Config-menu semibold weight (vector-only; Minecraft ⇒ Inter). */
    val menuSemibold: MsdfFont get() = semiboldFor(menuChoice())

    /** Regular weight for a specific choice index (Minecraft has no MSDF font → Inter placeholder). */
    fun regularFor(choice: Int): MsdfFont = when (choice) {
        BALOO2 -> baloo2Regular
        NUNITO -> nunitoRegular
        else -> interRegular
    }

    /** Semibold weight for a specific choice index (Minecraft has no MSDF font → Inter placeholder). */
    fun semiboldFor(choice: Int): MsdfFont = when (choice) {
        BALOO2 -> baloo2Semibold
        NUNITO -> nunitoSemibold
        else -> interSemibold
    }

    fun init() {
        interRegular.init()
        interSemibold.init()
        nunitoRegular.init()
        nunitoSemibold.init()
        baloo2Regular.init()
        baloo2Semibold.init()
        // Baloo 2 / Nunito lack glyphs Inter has (↺ reset, →, ✓, …) — fall back to Inter per-glyph,
        // matching weight so a semibold row's fallback icon reads as semibold too.
        nunitoRegular.fallback = interRegular
        baloo2Regular.fallback = interRegular
        nunitoSemibold.fallback = interSemibold
        baloo2Semibold.fallback = interSemibold
    }
}
