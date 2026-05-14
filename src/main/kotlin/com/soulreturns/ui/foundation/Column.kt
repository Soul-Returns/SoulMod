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
 * Vertical stack — children placed top-to-bottom, each at `y = previous y + previous height + gap`.
 *
 * Width = max child width; height = sum of child heights + gaps. Both subject to `Modifier.size()`
 * overrides + `padding()` insets. The [gap] parameter is the vertical space between adjacent
 * children (no trailing gap after the last child).
 */
@SoulComposable
fun Column(
    modifier: SoulModifier = SoulModifier.Empty,
    gap: Float = 0f,
    content: @SoulComposable () -> Unit,
) {
    SoulComposer.current.composable(ColumnNode(modifier, gap), content)
}

internal class ColumnNode(
    override val modifier: SoulModifier,
    private val gap: Float,
) : SoulNode() {
    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val outer = modifier.applySizeOverride(constraints)
        val padH = modifier.totalPaddingHorizontal()
        val padV = modifier.totalPaddingVertical()
        val offset = modifier.contentOffset()

        // Children measure with width constraints from the box but unbounded height (each
        // child takes its intrinsic height; column heights are summed).
        val inner =
            SoulConstraints(
                minWidth = 0f,
                maxWidth = if (outer.hasBoundedWidth()) outer.maxWidth - padH else Float.POSITIVE_INFINITY,
                minHeight = 0f,
                maxHeight = Float.POSITIVE_INFINITY,
            )

        var cursorY = 0f
        var maxChildW = 0f
        val positions =
            children.mapIndexed { i, child ->
                val m = child.measure(inner)
                val pos = SoulMeasured.Position(offset.x, offset.y + cursorY)
                cursorY += m.height
                if (i < children.size - 1) cursorY += gap
                if (m.width > maxChildW) maxChildW = m.width
                pos
            }

        val totalW = maxChildW + padH
        val totalH = cursorY + padV
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
