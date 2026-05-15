package com.soulreturns.ui.composer

import com.soulreturns.ui.input.HitRegion
import com.soulreturns.ui.input.SoulInput

/**
 * Marks the node as clickable. The owning layout node records a [HitRegion] during
 * `drawSelf`; on subsequent end-of-frame [SoulInput.flush], a click landing inside the
 * region invokes [onClick].
 *
 * [key] should be stable across frames for the same logical button — used for hover-state
 * lookup via [SoulInput.isHovered]. Layout-stable composables can use the composer's
 * `nextAutoKey()`; dynamic lists should pass a content-derived key.
 */
data class ClickableElement(val key: Any, val onClick: () -> Unit) : SoulModifier.Element

/**
 * Marks the node as scrollable. The owning layout node records a [HitRegion] with the
 * scroll handler. On end-of-frame [SoulInput.flush], a scroll event landing inside the
 * region invokes [onScroll] with the vertical scroll delta (positive = wheel up).
 */
data class ScrollableElement(val key: Any, val onScroll: (Float) -> Unit) : SoulModifier.Element

/**
 * Attaches hover-tooltip text to a node. The node's bounds become a tooltip region that
 * the framework hit-tests against the cursor; the topmost matching tooltip is rendered as
 * an overlay after the main composition. Works on any composable, clickable or not.
 */
data class TooltipElement(val text: String) : SoulModifier.Element

/** Add a click handler with an explicit stable key. */
fun SoulModifier.clickable(
    key: Any,
    onClick: () -> Unit,
): SoulModifier = then(ClickableElement(key, onClick))

/** Add a scroll handler with an explicit stable key. */
fun SoulModifier.scrollable(
    key: Any,
    onScroll: (Float) -> Unit,
): SoulModifier = then(ScrollableElement(key, onScroll))

/** Attach hover-tooltip text to this node. Empty strings are ignored (no overlay shown). */
fun SoulModifier.tooltip(text: String): SoulModifier = if (text.isEmpty()) this else then(TooltipElement(text))

/**
 * Walk this modifier chain and emit a hit region for any [ClickableElement] /
 * [ScrollableElement] elements present. Called from layout node `drawSelf` after the
 * background paint.
 *
 * Multiple clickable/scrollable elements on the same node merge into one [HitRegion] —
 * the chain is iterated and the resulting region carries the last-set key plus combined
 * click + scroll handlers.
 */
internal fun SoulModifier.recordHitRegions(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    depth: Int,
) {
    var key: Any? = null
    var onClick: (() -> Unit)? = null
    var onScroll: ((Float) -> Unit)? = null
    var tooltip: String? = null
    for (el in elements()) {
        when (el) {
            is ClickableElement -> {
                key = el.key
                onClick = el.onClick
            }
            is ScrollableElement -> {
                key = el.key
                onScroll = el.onScroll
            }
            is TooltipElement -> tooltip = el.text
            else -> {}
        }
    }
    if (key != null) {
        SoulInput.recordRegion(
            HitRegion(
                key = key,
                x = x,
                y = y,
                width = width,
                height = height,
                depth = depth,
                onClick = onClick,
                onScroll = onScroll,
            ),
        )
    }
    if (tooltip != null) {
        SoulInput.recordTooltip(x, y, width, height, depth, tooltip)
    }
}
