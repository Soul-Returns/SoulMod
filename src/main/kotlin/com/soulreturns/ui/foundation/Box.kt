package com.soulreturns.ui.foundation

import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulMeasured
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.SoulNode
import com.soulreturns.ui.composer.applySizeOverride
import com.soulreturns.ui.composer.composable
import com.soulreturns.ui.composer.contentOffset
import com.soulreturns.ui.composer.drawBackgrounds
import com.soulreturns.ui.composer.totalPaddingHorizontal
import com.soulreturns.ui.composer.totalPaddingVertical

/**
 * Single-child / stacked-children container. Children render at the same origin (top-left of
 * the box's content area, accounting for padding). Box sizes itself to the largest child.
 *
 * For positioning multiple children at offsets, use [Column] or [Row] for sequential layout;
 * a future `BoxWithAlignment` will handle absolute positioning by `Alignment` corners.
 */
@SoulComposable
fun Box(
    modifier: SoulModifier = SoulModifier.Empty,
    content: @SoulComposable () -> Unit = {},
) {
    SoulComposer.current.composable(BoxNode(modifier), content)
}

internal class BoxNode(override val modifier: SoulModifier) : SoulNode() {
    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val outer = modifier.applySizeOverride(constraints)
        val padH = modifier.totalPaddingHorizontal()
        val padV = modifier.totalPaddingVertical()
        val inner = outer.inset(padH, padV)
        val offset = modifier.contentOffset()

        var contentW = 0f
        var contentH = 0f
        val positions =
            children.map { child ->
                val m = child.measure(inner)
                if (m.width > contentW) contentW = m.width
                if (m.height > contentH) contentH = m.height
                SoulMeasured.Position(offset.x, offset.y)
            }

        val totalW = contentW + padH
        val totalH = contentH + padV
        val (w, h) = outer.constrain(totalW, totalH)
        val out = SoulMeasured(w, h, positions)
        measured = out
        return out
    }

    override fun drawSelf(
        x: Float,
        y: Float,
    ) {
        val m = measured ?: return
        modifier.drawBackgrounds(x, y, m.width, m.height)
    }
}
