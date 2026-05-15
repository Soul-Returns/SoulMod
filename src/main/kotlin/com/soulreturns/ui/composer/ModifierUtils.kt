package com.soulreturns.ui.composer

import com.soulreturns.platform.render.nvg.NvgGradient
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
    // BoxShadow always paints first so the BackgroundElement / GradientBackgroundElement
    // can cover the hollow interior of the shadow ring. Without this ordering the bg would
    // paint first and then the shadow would draw a dark ring *over* the bg edges.
    val all = elements()
    val shadow = all.filterIsInstance<BoxShadowElement>().firstOrNull()
    if (shadow != null) {
        val bgRadius = all.filterIsInstance<BackgroundElement>().firstOrNull()?.radius ?: 0f
        when (shadow.side) {
            ShadowSide.All ->
                NvgRenderer.dropShadow(x, y, width, height, shadow.blur, shadow.spread, bgRadius)
            else -> drawDirectionalShadow(x, y, width, height, shadow)
        }
    }
    for (el in all) {
        when (el) {
            is BackgroundElement -> {
                if (el.radius <= 0f) {
                    NvgRenderer.rect(x, y, width, height, el.color)
                } else {
                    val r = el.radius
                    when (el.rounding) {
                        CornerRounding.Full -> NvgRenderer.rect(x, y, width, height, el.color, r)
                        CornerRounding.Top ->
                            NvgRenderer.halfRoundedRect(x, y, width, height, el.color, r, roundTop = true)
                        CornerRounding.Bottom ->
                            NvgRenderer.halfRoundedRect(x, y, width, height, el.color, r, roundTop = false)
                        CornerRounding.TopLeft ->
                            NvgRenderer.cornerRoundedRect(x, y, width, height, el.color, topLeft = r)
                        CornerRounding.TopRight ->
                            NvgRenderer.cornerRoundedRect(x, y, width, height, el.color, topRight = r)
                        CornerRounding.BottomLeft ->
                            NvgRenderer.cornerRoundedRect(x, y, width, height, el.color, bottomLeft = r)
                        CornerRounding.BottomRight ->
                            NvgRenderer.cornerRoundedRect(x, y, width, height, el.color, bottomRight = r)
                    }
                }
            }
            is GradientBackgroundElement -> {
                NvgRenderer.gradientRect(
                    x = x,
                    y = y,
                    w = width,
                    h = height,
                    color1 = el.color1,
                    color2 = el.color2,
                    gradient = el.direction,
                    radius = el.radius,
                )
            }
            else -> {}
        }
    }
}

/**
 * Paint a soft directional shadow on one side of the rect by drawing an external linear-
 * gradient strip from `0x80000000` (the "source-adjacent" edge) to fully transparent.
 *
 * For `Bottom`: a strip at `(x, y+height, width, blur+spread)` with top-to-bottom gradient.
 * For `Right`:  a strip at `(x+width, y, blur+spread, height)` with left-to-right gradient.
 * For `Top`/`Left`: mirror images of the above.
 *
 * Doesn't draw a "ring" around the rect — that's what [NvgRenderer.dropShadow] is for. This
 * is the "shadow only on one neighbour" variant, used when the panel's other sides face
 * the card edge (no neighbour to bleed into).
 */
private fun drawDirectionalShadow(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    shadow: BoxShadowElement,
) {
    val depth = shadow.blur + shadow.spread
    if (depth <= 0f) return
    val solid = shadow.color
    // Transparent end of the gradient — preserve the solid's RGB so the linear interpolation
    // stays in the same hue (otherwise an opaque grey would fade through black to clear and
    // briefly look darker partway through).
    val clear = solid and 0x00FFFFFF
    when (shadow.side) {
        ShadowSide.Right ->
            NvgRenderer.gradientRect(
                x = x + width,
                y = y,
                w = depth,
                h = height,
                color1 = solid,
                color2 = clear,
                gradient = NvgGradient.LeftToRight,
                radius = 0f,
            )
        ShadowSide.Left ->
            NvgRenderer.gradientRect(
                x = x - depth,
                y = y,
                w = depth,
                h = height,
                color1 = clear,
                color2 = solid,
                gradient = NvgGradient.LeftToRight,
                radius = 0f,
            )
        ShadowSide.Bottom ->
            NvgRenderer.gradientRect(
                x = x,
                y = y + height,
                w = width,
                h = depth,
                color1 = solid,
                color2 = clear,
                gradient = NvgGradient.TopToBottom,
                radius = 0f,
            )
        ShadowSide.Top ->
            NvgRenderer.gradientRect(
                x = x,
                y = y - depth,
                w = width,
                h = depth,
                color1 = clear,
                color2 = solid,
                gradient = NvgGradient.TopToBottom,
                radius = 0f,
            )
        ShadowSide.All -> Unit // handled in drawBackgrounds via NvgRenderer.dropShadow
    }
}
