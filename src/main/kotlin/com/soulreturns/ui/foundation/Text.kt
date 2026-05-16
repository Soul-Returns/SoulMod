package com.soulreturns.ui.foundation

import com.soulreturns.platform.render.nvg.NvgFont
import com.soulreturns.platform.render.nvg.NvgRenderer
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulMeasured
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.applySizeOverride
import com.soulreturns.ui.composer.composable
import com.soulreturns.ui.composer.drawBackgrounds
import com.soulreturns.ui.composer.recordHitRegions

/**
 * Single-line text. Width = `nvgTextBounds`; height = font size (rounded up).
 *
 * Honors `Modifier.background(...)` (painted before the glyphs) and `Modifier.size(...)`
 * overrides. `padding(...)` on a text node currently does nothing since Text has no children
 * to inset — wrap it in a [Box] if you need padding around the glyphs.
 *
 * For multi-line / wrapped text, use a future `WrappedText` composable; this one is the
 * simplest possible primitive for HUD labels and table cells.
 */
@SoulComposable
fun Text(
    text: String,
    size: Float = 12f,
    color: Int = 0xFFEEEEEE.toInt(),
    font: NvgFont = NvgRenderer.defaultFont,
    modifier: SoulModifier = SoulModifier.Empty,
) {
    SoulComposer.current.composable(TextNode(text, size, color, font, modifier))
}

internal class TextNode(
    private val text: String,
    private val size: Float,
    private val color: Int,
    private val font: NvgFont,
    override val modifier: SoulModifier,
) : com.soulreturns.ui.composer.SoulNode() {
    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val c = modifier.applySizeOverride(constraints)
        val intrinsicW = NvgRenderer.textWidth(text, size, font)
        // Line height: snap to integer screen pixels.
        //
        // Inter's natural line-height ratio is ~1.20, so the *target* height in screen
        // pixels is `size × 1.20 × panelScale`. We round that to a whole screen pixel and
        // divide back to composable space so the resulting `intrinsicH × panelScale` is an
        // exact integer screen pixel count. Combined with screen-pixel-snapped `gap` in
        // Column / ScrollableList, this makes every row's pitch constant in screen
        // pixels — eliminating the alternating tight/loose row gap pattern you get when
        // fractional composable accumulation hits banker's rounding at the render step.
        val ps = com.soulreturns.ui.input.SoulInput.panelScale.coerceAtLeast(0.0001f)
        val intrinsicHScreen = kotlin.math.round(size * LINE_HEIGHT_RATIO * ps).coerceAtLeast(1f)
        val intrinsicH = intrinsicHScreen / ps
        val (w, h) = c.constrain(intrinsicW, intrinsicH)
        val out = SoulMeasured(w, h)
        measured = out
        return out
    }

    private companion object {
        /**
         * Em-to-line-height ratio for the bundled Inter weights. 1.20 matches Inter's `hhea`
         * line-gap metrics closely enough for layout purposes — exact value via
         * `nvgTextMetrics` would be marginally better but require an NvgRenderer round-trip
         * per measure. Update if a font with a substantially different metric is ever made
         * the default.
         */
        const val LINE_HEIGHT_RATIO = 1.20f
    }

    override fun drawSelf(
        x: Float,
        y: Float,
        depth: Int,
    ) {
        val m = measured ?: return
        modifier.drawBackgrounds(x, y, m.width, m.height)
        modifier.recordHitRegions(x, y, m.width, m.height, depth)
        // Per-HUD overrides: when this Text sits inside a SoulHud (currentHudId set), the
        // HUD's effective "use bold font" + "draw text shadow" toggles can rewrite the
        // font + drawing path. Outside a HUD (e.g. inside a SoulScreen) both effects are
        // off — composables in screens manage their own font + emphasis explicitly.
        val hudId = com.soulreturns.ui.input.SoulInput.currentHudId
        val effectiveFont =
            if (hudId != null && com.soulreturns.ui.runtime.SoulHud.shouldUseBoldFont(hudId)) {
                NvgRenderer.boldVariantOf(font)
            } else {
                font
            }
        val withShadow =
            hudId != null && com.soulreturns.ui.runtime.SoulHud.shouldDrawTextShadow(hudId)
        if (withShadow) {
            val shadowMultiplier = com.soulreturns.ui.runtime.SoulHud.hudTextShadowSize()
            NvgRenderer.textShadow(text, x, y, size, color, effectiveFont, shadowMultiplier)
        } else {
            NvgRenderer.text(text, x, y, size, color, effectiveFont)
        }
    }
}
