package com.soulreturns.ui.foundation

import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.HorizontalAlignment
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulMeasured
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.SoulNode
import com.soulreturns.ui.composer.alignHorizontal
import com.soulreturns.ui.composer.applySizeOverride
import com.soulreturns.ui.composer.arrangeAlong
import com.soulreturns.ui.composer.composable
import com.soulreturns.ui.composer.contentOffset
import com.soulreturns.ui.composer.drawBackgrounds
import com.soulreturns.ui.composer.recordHitRegions
import com.soulreturns.ui.composer.totalPaddingHorizontal
import com.soulreturns.ui.composer.totalPaddingVertical

/**
 * Vertical stack — children placed top-to-bottom.
 *
 * Height = sum of child heights + gaps (or `arrangement`-distributed if main axis is bounded).
 * Width = max child width. Both subject to `Modifier.size()` overrides + `padding()` insets.
 *
 * @param verticalArrangement How children are distributed along the column's main (Y) axis.
 *   `SpaceBetween` / `SpaceAround` / `SpaceEvenly` need a bounded height to be meaningful;
 *   under an unbounded parent they degrade to `Start`.
 * @param horizontalAlignment How each child is positioned along the cross (X) axis within
 *   the column's width.
 * @param gap Minimum vertical space between adjacent children.
 */
@SoulComposable
fun Column(
    modifier: SoulModifier = SoulModifier.Empty,
    verticalArrangement: Arrangement = Arrangement.Start,
    horizontalAlignment: HorizontalAlignment = HorizontalAlignment.Start,
    gap: Float = 0f,
    content: @SoulComposable () -> Unit,
) {
    SoulComposer.current.composable(
        ColumnNode(modifier, verticalArrangement, horizontalAlignment, gap),
        content,
    )
}

internal class ColumnNode(
    override val modifier: SoulModifier,
    private val arrangement: Arrangement,
    private val horizontalAlignment: HorizontalAlignment,
    private val gap: Float,
) : SoulNode() {
    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val outer = modifier.applySizeOverride(constraints)
        val padH = modifier.totalPaddingHorizontal()
        val padV = modifier.totalPaddingVertical()
        val offset = modifier.contentOffset()

        // Children measure with bounded width (filling column) but unbounded height.
        val inner =
            SoulConstraints(
                minWidth = 0f,
                maxWidth = if (outer.hasBoundedWidth()) (outer.maxWidth - padH).coerceAtLeast(0f) else Float.POSITIVE_INFINITY,
                minHeight = 0f,
                maxHeight = Float.POSITIVE_INFINITY,
            )

        val childMeasured = children.map { it.measure(inner) }
        val childHeights = childMeasured.map { it.height }
        val maxChildW = childMeasured.maxOfOrNull { it.width } ?: 0f

        // Default: column sizes to content. `fillMaxHeight()` raises outer.minHeight to
        // maxHeight via [applySizeOverride], so [outer.constrain] later expands the height.
        // `arrangeAlong` then sees the post-constrain height and can distribute extra space.
        val contentSumH = childHeights.sum() + gap * (childHeights.size - 1).coerceAtLeast(0)
        val totalW = maxChildW + padH
        val totalH = contentSumH + padV
        val (w, h) = outer.constrain(totalW, totalH)

        val arrangementSpace = (h - padV).coerceAtLeast(0f)
        val mainPositions = arrangeAlong(arrangement, childHeights, arrangementSpace, gap)
        val positions =
            childMeasured.mapIndexed { i, m ->
                val xOffset = offset.x + alignHorizontal(horizontalAlignment, m.width, maxChildW)
                val yOffset = offset.y + mainPositions[i]
                SoulMeasured.Position(xOffset, yOffset)
            }

        val out = SoulMeasured(w, h, positions)
        measured = out
        return out
    }

    override fun drawSelf(
        x: Float,
        y: Float,
        depth: Int,
    ) {
        val m = measured ?: return
        modifier.drawBackgrounds(x, y, m.width, m.height)
        modifier.recordHitRegions(x, y, m.width, m.height, depth)
    }
}
