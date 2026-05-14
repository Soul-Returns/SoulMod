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
        val intrinsicH = size
        val (w, h) = c.constrain(intrinsicW, intrinsicH)
        val out = SoulMeasured(w, h)
        measured = out
        return out
    }

    override fun drawSelf(
        x: Float,
        y: Float,
    ) {
        val m = measured ?: return
        modifier.drawBackgrounds(x, y, m.width, m.height)
        NvgRenderer.text(text, x, y, size, color, font)
    }
}
