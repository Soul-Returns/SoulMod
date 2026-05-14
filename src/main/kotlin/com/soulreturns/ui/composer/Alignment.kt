package com.soulreturns.ui.composer

/**
 * Cross-axis alignment of a [com.soulreturns.ui.foundation.Column]'s children — i.e. where
 * each child sits along the X axis relative to the column's content area.
 */
enum class HorizontalAlignment {
    Start,
    Center,
    End,
}

/**
 * Cross-axis alignment of a [com.soulreturns.ui.foundation.Row]'s children — i.e. where each
 * child sits along the Y axis relative to the row's content area.
 */
enum class VerticalAlignment {
    Top,
    Center,
    Bottom,
}

/**
 * Main-axis arrangement of children inside a [com.soulreturns.ui.foundation.Column] or
 * [com.soulreturns.ui.foundation.Row].
 *
 * Some modes (`SpaceBetween`, `SpaceAround`, `SpaceEvenly`) need the parent to know its
 * final main-axis size before placing children. If the parent's main-axis is unbounded
 * (e.g. a Column inside an unbounded-height container), these modes fall back to `Start`
 * because there's no "extra space" to distribute.
 */
enum class Arrangement {
    /** Children packed at the start of the axis (top / left). */
    Start,

    /** Children packed at the center of the axis. */
    Center,

    /** Children packed at the end of the axis (bottom / right). */
    End,

    /** Equal space between adjacent children; no space at the ends. */
    SpaceBetween,

    /** Equal space around each child (half-space at the ends). */
    SpaceAround,

    /** Equal space between children AND at the ends. */
    SpaceEvenly,
}

/**
 * Compute the X position for a child of given [childWidth] inside a container of
 * [containerWidth], honoring the [alignment]. Returns the offset from the container's left.
 */
internal fun alignHorizontal(
    alignment: HorizontalAlignment,
    childWidth: Float,
    containerWidth: Float,
): Float =
    when (alignment) {
        HorizontalAlignment.Start -> 0f
        HorizontalAlignment.Center -> ((containerWidth - childWidth) / 2f).coerceAtLeast(0f)
        HorizontalAlignment.End -> (containerWidth - childWidth).coerceAtLeast(0f)
    }

/** Y-axis analog of [alignHorizontal]. */
internal fun alignVertical(
    alignment: VerticalAlignment,
    childHeight: Float,
    containerHeight: Float,
): Float =
    when (alignment) {
        VerticalAlignment.Top -> 0f
        VerticalAlignment.Center -> ((containerHeight - childHeight) / 2f).coerceAtLeast(0f)
        VerticalAlignment.Bottom -> (containerHeight - childHeight).coerceAtLeast(0f)
    }

/**
 * Compute the placement positions of children laid out along an axis according to [arrangement].
 *
 * @param childSizes Main-axis sizes of each child, in order.
 * @param totalSize  Total main-axis size of the container's content area (after padding).
 *                   Pass the sum of `childSizes` if the container's axis is unbounded —
 *                   `SpaceBetween`-style arrangements then degrade to `Start`.
 * @param gap        Minimum gap between adjacent children (added on top of arrangement).
 * @return Per-child main-axis offset from the container's start.
 */
internal fun arrangeAlong(
    arrangement: Arrangement,
    childSizes: List<Float>,
    totalSize: Float,
    gap: Float,
): List<Float> {
    if (childSizes.isEmpty()) return emptyList()
    val sumSizes = childSizes.sum()
    val gapTotal = gap * (childSizes.size - 1).coerceAtLeast(0)
    val packedSize = sumSizes + gapTotal
    val freeSpace = (totalSize - packedSize).coerceAtLeast(0f)

    val effective = if (totalSize == Float.POSITIVE_INFINITY) Arrangement.Start else arrangement

    val positions = ArrayList<Float>(childSizes.size)
    when (effective) {
        Arrangement.Start -> {
            var cursor = 0f
            for ((i, s) in childSizes.withIndex()) {
                positions += cursor
                cursor += s
                if (i < childSizes.size - 1) cursor += gap
            }
        }
        Arrangement.Center -> {
            var cursor = freeSpace / 2f
            for ((i, s) in childSizes.withIndex()) {
                positions += cursor
                cursor += s
                if (i < childSizes.size - 1) cursor += gap
            }
        }
        Arrangement.End -> {
            var cursor = freeSpace
            for ((i, s) in childSizes.withIndex()) {
                positions += cursor
                cursor += s
                if (i < childSizes.size - 1) cursor += gap
            }
        }
        Arrangement.SpaceBetween -> {
            if (childSizes.size == 1) {
                positions += 0f
            } else {
                val between = freeSpace / (childSizes.size - 1) + gap
                var cursor = 0f
                for (s in childSizes) {
                    positions += cursor
                    cursor += s + between
                }
            }
        }
        Arrangement.SpaceAround -> {
            val slot = freeSpace / childSizes.size
            var cursor = slot / 2f
            for ((i, s) in childSizes.withIndex()) {
                positions += cursor
                cursor += s + slot
                if (i < childSizes.size - 1) cursor += gap
            }
        }
        Arrangement.SpaceEvenly -> {
            val slot = freeSpace / (childSizes.size + 1)
            var cursor = slot
            for ((i, s) in childSizes.withIndex()) {
                positions += cursor
                cursor += s + slot
                if (i < childSizes.size - 1) cursor += gap
            }
        }
    }
    return positions
}
