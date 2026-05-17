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
    internal val text: String,
    internal val size: Float,
    internal val color: Int,
    internal val font: NvgFont,
    override val modifier: SoulModifier,
) : com.soulreturns.ui.composer.SoulNode(),
    com.soulreturns.ui.composer.MojangTextEmitter {
    override fun emitMojangTexts(
        x: Float,
        y: Float,
        clip: com.soulreturns.ui.composer.ClipRect?,
        emit: (com.soulreturns.ui.composer.MojangTextSpec) -> Unit,
    ) {
        emit(com.soulreturns.ui.composer.MojangTextSpec(text, x, y, size, color))
    }

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val c = modifier.applySizeOverride(constraints)
        // If this TextNode is composing inside a HUD whose "Use Minecraft Font" override is
        // active, measure against Mojang's font metrics — otherwise the layout uses Inter
        // widths but the draw uses Mojang widths, and text boxes shift on screen. The
        // `currentHudId` is set by `NvgFrame.submit` AND by `SoulHud.renderOne`'s synchronous
        // pre-compose pass, so this check works in both phases.
        val hudId = com.soulreturns.ui.input.SoulInput.currentHudId
        val useMc = hudId != null && com.soulreturns.ui.runtime.SoulHud.shouldUseMinecraftFont(hudId)
        val intrinsicW =
            if (useMc) {
                mojangTextWidth(text, size)
            } else {
                NvgRenderer.textWidth(text, size, font)
            }
        // Line height: snap to integer screen pixels.
        //
        // Inter's natural line-height ratio is ~1.20, so the *target* height in screen
        // pixels is `size × 1.20 × panelScale`. We round that to a whole screen pixel and
        // divide back to composable space so the resulting `intrinsicH × panelScale` is an
        // exact integer screen pixel count. Combined with screen-pixel-snapped `gap` in
        // Column / ScrollableList, this makes every row's pitch constant in screen
        // pixels — eliminating the alternating tight/loose row gap pattern you get when
        // fractional composable accumulation hits banker's rounding at the render step.
        //
        // Mojang's font has line height 9 at its native size, so we approximate the same
        // 1.20 ratio by using `size × MC_LINE_HEIGHT_RATIO` for the pre-snap height —
        // empirically that keeps row pitch stable across the swap and avoids cramped rows.
        val ps = com.soulreturns.ui.input.SoulInput.panelScale.coerceAtLeast(0.0001f)
        val ratio = if (useMc) MC_LINE_HEIGHT_RATIO else LINE_HEIGHT_RATIO
        val intrinsicHScreen = kotlin.math.round(size * ratio * ps).coerceAtLeast(1f)
        val intrinsicH = intrinsicHScreen / ps
        val (w, h) = c.constrain(intrinsicW, intrinsicH)
        val out = SoulMeasured(w, h)
        measured = out
        return out
    }

    /**
     * Width of [text] when rendered via Mojang's `Font` at composable-space [size].
     * Mojang's natural glyph metrics are at ~9-pixel line height, so we scale the raw
     * pixel width by `size / 9`. Resource pack font overrides flow through automatically
     * because we always read the live `Minecraft.font` instance.
     */
    private fun mojangTextWidth(
        text: String,
        size: Float,
    ): Float {
        val font = net.minecraft.client.Minecraft.getInstance().font
        return font.width(text).toFloat() * (size / MC_NATIVE_LINE_HEIGHT)
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

        /**
         * Same ratio for Minecraft's vanilla font, used when a HUD has "Use Minecraft Font"
         * enabled. Mojang's renderer reports the line height directly (`font.lineHeight = 9`
         * at native size), but visually the glyph blocks plus shadow take ~1.0× the font
         * size — flatter than Inter — so we use a smaller ratio here. Tuned empirically.
         */
        const val MC_LINE_HEIGHT_RATIO = 1.0f

        /**
         * Native pixel size of Mojang's bitmap font. `font.width(text)` returns pixels at
         * this scale; multiplying by `size / MC_NATIVE_LINE_HEIGHT` converts to composable
         * units so the layout box matches what Mojang will draw post-scale.
         */
        const val MC_NATIVE_LINE_HEIGHT = 9f
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
        // When the HUD wants Mojang's font, skip the NVG glyph draw entirely. The
        // [SoulHud.renderOne] pipeline collects this text's screen-space position during
        // its pre-compose pass and dispatches via `GuiGraphics.drawString` AFTER the PIP
        // composites — so the panel backgrounds / shapes still render through NanoVG, but
        // the glyphs come from Minecraft's font (respecting resource-pack overrides).
        if (hudId != null && com.soulreturns.ui.runtime.SoulHud.shouldUseMinecraftFont(hudId)) {
            return
        }
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
