package com.soulreturns.ui.foundation

import com.soulreturns.platform.render.nvg.NvgRenderer
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulMeasured
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.SoulNode
import com.soulreturns.ui.composer.applySizeOverride
import com.soulreturns.ui.composer.composable
import com.soulreturns.ui.composer.recordHitRegions
import com.soulreturns.ui.theme.SoulTheme

/**
 * Horizontal progress bar. Stateless — caller passes [progress] in `[0, 1]`; widget renders
 * a filled portion of the track.
 *
 * Sized via the modifier: by default takes full available width and 6px tall (`Theme.dimens
 * .separatorThickness * 6`-ish). Wrap in a `Box(modifier = .width(N))` or apply
 * `.fillMaxWidth()` on the modifier directly to control width.
 */
@SoulComposable
fun ProgressBar(
    progress: Float,
    modifier: SoulModifier = SoulModifier.Empty,
    color: Int = SoulTheme.colors.accent,
    trackColor: Int = SoulTheme.colors.panelInset,
    height: Float = 6f,
) {
    SoulComposer.current.composable(
        ProgressBarNode(
            progress = progress.coerceIn(0f, 1f),
            modifier = modifier,
            color = color,
            trackColor = trackColor,
            barHeight = height,
        ),
    )
}

internal class ProgressBarNode(
    private val progress: Float,
    override val modifier: SoulModifier,
    private val color: Int,
    private val trackColor: Int,
    private val barHeight: Float,
) : SoulNode() {
    companion object {
        private const val DEFAULT_W = 120f
    }

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val c = modifier.applySizeOverride(constraints)
        // Hug content width when unbounded; otherwise fill the available width.
        val w =
            when {
                c.minWidth == c.maxWidth -> c.maxWidth
                c.hasBoundedWidth() -> c.maxWidth
                else -> DEFAULT_W
            }
        val h = barHeight
        val (cw, ch) = c.constrain(w, h)
        val out = SoulMeasured(cw, ch)
        measured = out
        return out
    }

    override fun drawSelf(
        x: Float,
        y: Float,
        depth: Int,
    ) {
        val m = measured ?: return
        val radius = m.height / 2f
        // Track
        NvgRenderer.rect(x, y, m.width, m.height, trackColor, radius)
        // Fill
        if (progress > 0f) {
            val fillW = (m.width * progress).coerceAtLeast(m.height)
            NvgRenderer.rect(x, y, fillW, m.height, color, radius)
        }
        modifier.recordHitRegions(x, y, m.width, m.height, depth)
    }
}
