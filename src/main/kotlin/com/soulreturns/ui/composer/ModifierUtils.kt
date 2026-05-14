package com.soulreturns.ui.composer

import com.soulreturns.platform.render.nvg.NvgRenderer

/**
 * Helpers shared by node implementations to interpret [SoulModifier] chains uniformly.
 *
 * Layout nodes call these during their `measure` and `drawSelf` so every node respects
 * `padding(...)`, `background(...)`, `size(...)` the same way without copy-pasting.
 *
 * `internal` because they're an implementation detail of the framework — features write
 * composables, not nodes, so they should never see these.
 */
internal fun SoulModifier.totalPaddingHorizontal(): Float =
    elements()
        .asSequence()
        .filterIsInstance<PaddingElement>()
        .sumOf { (it.left + it.right).toDouble() }
        .toFloat()

internal fun SoulModifier.totalPaddingVertical(): Float =
    elements()
        .asSequence()
        .filterIsInstance<PaddingElement>()
        .sumOf { (it.top + it.bottom).toDouble() }
        .toFloat()

/** Offset (from the node's top-left) where content placement starts after padding. */
internal fun SoulModifier.contentOffset(): SoulMeasured.Position {
    val elements = elements()
    val l = elements.asSequence().filterIsInstance<PaddingElement>().sumOf { it.left.toDouble() }.toFloat()
    val t = elements.asSequence().filterIsInstance<PaddingElement>().sumOf { it.top.toDouble() }.toFloat()
    return SoulMeasured.Position(l, t)
}

/**
 * Apply [SizeElement] and [FillElement] overrides to a constraint set.
 *
 *  - [SizeElement]: forces both min and max of an axis to the given value (overrides parent).
 *  - [FillElement]: when the parent supplies a bounded max on the axis, raises the min to
 *    that max so the child measures itself at the full available size. If the axis is
 *    unbounded, fill is a no-op (no "available" to fill).
 *
 * Chain order matters — later elements override earlier ones, mirroring CSS-style cascading.
 */
internal fun SoulModifier.applySizeOverride(c: SoulConstraints): SoulConstraints {
    var out = c
    for (el in elements()) {
        when (el) {
            is SizeElement -> {
                if (el.width != null) {
                    out = out.copy(minWidth = el.width, maxWidth = el.width)
                }
                if (el.height != null) {
                    out = out.copy(minHeight = el.height, maxHeight = el.height)
                }
            }
            is FillElement -> {
                if (el.width && out.hasBoundedWidth()) {
                    out = out.copy(minWidth = out.maxWidth)
                }
                if (el.height && out.hasBoundedHeight()) {
                    out = out.copy(minHeight = out.maxHeight)
                }
            }
            else -> {} // padding + background handled elsewhere
        }
    }
    return out
}

/** Returns true if a [SizeElement] forces the width axis. */
internal fun SoulModifier.hasFixedWidth(): Boolean = elements().any { it is SizeElement && it.width != null }

/** Returns true if a [SizeElement] forces the height axis. */
internal fun SoulModifier.hasFixedHeight(): Boolean = elements().any { it is SizeElement && it.height != null }

/**
 * Draw any [BackgroundElement]s under the node at logical position `(x, y)` with the given
 * size. Multiple backgrounds stack in chain order (outer-most first → drawn first → behind).
 */
internal fun SoulModifier.drawBackgrounds(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
) {
    elements().filterIsInstance<BackgroundElement>().forEach { bg ->
        if (bg.radius > 0f) {
            NvgRenderer.rect(x, y, width, height, bg.color, bg.radius)
        } else {
            NvgRenderer.rect(x, y, width, height, bg.color)
        }
    }
}
