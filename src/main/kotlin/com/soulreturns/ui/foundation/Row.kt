package com.soulreturns.ui.foundation

import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulMeasured
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.SoulNode
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.alignVertical
import com.soulreturns.ui.composer.applySizeOverride
import com.soulreturns.ui.composer.arrangeAlong
import com.soulreturns.ui.composer.composable
import com.soulreturns.ui.composer.contentOffset
import com.soulreturns.ui.composer.drawBackgrounds
import com.soulreturns.ui.composer.recordHitRegions
import com.soulreturns.ui.composer.totalPaddingHorizontal
import com.soulreturns.ui.composer.totalPaddingVertical
import com.soulreturns.ui.composer.weightValue

/**
 * Horizontal stack — children placed left-to-right.
 *
 * Width = sum of child widths + gaps (or `arrangement`-distributed if main axis is bounded).
 * Height = max child height. Both subject to `Modifier.size()` overrides + `padding()`
 * insets. The [gap] parameter is the horizontal space between adjacent children (no leading
 * or trailing gap).
 *
 * @param horizontalArrangement How children are distributed along the row's main (X) axis.
 *   `SpaceBetween` / `SpaceAround` / `SpaceEvenly` need a bounded width to be meaningful;
 *   under an unbounded parent they degrade to `Start`.
 * @param verticalAlignment How each child is positioned along the cross (Y) axis within the
 *   row's height.
 */
@SoulComposable
fun Row(
    modifier: SoulModifier = SoulModifier.Empty,
    horizontalArrangement: Arrangement = Arrangement.Start,
    verticalAlignment: VerticalAlignment = VerticalAlignment.Top,
    gap: Float = 0f,
    content: @SoulComposable () -> Unit,
) {
    SoulComposer.current.composable(
        RowNode(modifier, horizontalArrangement, verticalAlignment, gap),
        content,
    )
}

internal class RowNode(
    override val modifier: SoulModifier,
    private val arrangement: Arrangement,
    private val verticalAlignment: VerticalAlignment,
    private val gap: Float,
) : SoulNode() {
    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val outer = modifier.applySizeOverride(constraints)
        val padH = modifier.totalPaddingHorizontal()
        val padV = modifier.totalPaddingVertical()
        val offset = modifier.contentOffset()

        val baseMaxH =
            if (outer.hasBoundedHeight()) (outer.maxHeight - padV).coerceAtLeast(0f) else Float.POSITIVE_INFINITY

        // Two-phase measure to support `Modifier.weight(...)` siblings:
        //   1. Measure unweighted children with unbounded main axis (intrinsic).
        //   2. Distribute remaining main-axis space across weighted children proportionally
        //      and re-measure each at its computed fixed width.
        // If the row's own main axis is unbounded, there's nothing to share, so weighted
        // children also fall back to intrinsic.
        val weights = children.map { it.modifier.weightValue() }
        val totalWeight = weights.sum()

        val baseInner =
            SoulConstraints(
                minWidth = 0f,
                maxWidth = Float.POSITIVE_INFINITY,
                minHeight = 0f,
                maxHeight = baseMaxH,
            )
        val intrinsicMeasured: List<SoulMeasured?> =
            children.mapIndexed { i, c ->
                if (weights[i] > 0f) null else c.measure(baseInner)
            }
        val gapCount = (children.size - 1).coerceAtLeast(0)
        val totalGap = gap * gapCount
        val intrinsicSumW =
            intrinsicMeasured.filterNotNull().sumOf { it.width.toDouble() }.toFloat()

        val availableW =
            if (outer.hasBoundedWidth()) (outer.maxWidth - padH).coerceAtLeast(0f) else Float.POSITIVE_INFINITY
        val weightedMeasured: List<SoulMeasured?> =
            if (totalWeight > 0f && outer.hasBoundedWidth()) {
                val remaining = (availableW - intrinsicSumW - totalGap).coerceAtLeast(0f)
                children.mapIndexed { i, c ->
                    if (weights[i] > 0f) {
                        val share = remaining * (weights[i] / totalWeight)
                        c.measure(
                            SoulConstraints(
                                minWidth = share,
                                maxWidth = share,
                                minHeight = 0f,
                                maxHeight = baseMaxH,
                            ),
                        )
                    } else {
                        null
                    }
                }
            } else {
                children.mapIndexed { i, c ->
                    if (weights[i] > 0f) c.measure(baseInner) else null
                }
            }

        val childMeasured =
            children.mapIndexed { i, _ ->
                intrinsicMeasured[i] ?: weightedMeasured[i] ?: SoulMeasured(0f, 0f)
            }
        val childWidths = childMeasured.map { it.width }
        val maxChildH = childMeasured.maxOfOrNull { it.height } ?: 0f

        // Default: row sizes to content. `fillMaxWidth()` raises outer.minWidth to
        // maxWidth via [applySizeOverride], so [outer.constrain] later expands the width.
        // `arrangeAlong` then sees the post-constrain width and can distribute extra space
        // (which is how SpaceBetween / SpaceAround / SpaceEvenly become meaningful).
        val contentSumW = childWidths.sum() + gap * (childWidths.size - 1).coerceAtLeast(0)
        val totalW = contentSumW + padH
        val totalH = maxChildH + padV
        val (w, h) = outer.constrain(totalW, totalH)

        // Cross-axis (vertical) alignment is computed against the row's **own** content
        // height — when the row expands taller than its tallest child (e.g. `fillMaxHeight`
        // or a forced `height(...)`), children align inside that larger area.
        val crossAxisSize = (h - padV).coerceAtLeast(maxChildH)
        val arrangementSpace = (w - padH).coerceAtLeast(0f)
        val mainPositions = arrangeAlong(arrangement, childWidths, arrangementSpace, gap)
        val positions =
            childMeasured.mapIndexed { i, m ->
                val xOffset = offset.x + mainPositions[i]
                val yOffset = offset.y + alignVertical(verticalAlignment, m.height, crossAxisSize)
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
