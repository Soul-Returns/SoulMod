package com.soulreturns.ui.composer

/**
 * Chainable visual / behavioral modifier attached to a composable.
 *
 * `SoulModifier` is an immutable linked list of [Element]s. Build chains with extension
 * functions on the [Empty] singleton:
 *
 * ```
 * val mod = SoulModifier.padding(8f).background(0xD81A1A1A.toInt(), 6f).size(width = 200f)
 * ```
 *
 * Layout nodes inspect the chain via [elements] in composition order and react to each
 * concrete element type they know about (others are ignored — silently extensible). For v1
 * the supported elements are [PaddingElement], [BackgroundElement], [SizeElement]; further
 * ones (clickable, hoverable, scrollable) are added in [P2.3+].
 *
 * Modeled on `androidx.compose.ui.Modifier`. Simpler in two ways:
 *  - Element implementations are kotlin `data class`es with no behavior, not full node
 *    instances. The layout node interprets them. This avoids the per-modifier composable
 *    wrapper boilerplate Compose's modifier system requires.
 *  - There's no "fold left/right" merge semantics — order matters and elements act
 *    additively (padding stacks, background stacks etc.).
 */
sealed interface SoulModifier {
    /** Single behavioral / visual atom in a modifier chain. */
    interface Element : SoulModifier

    /** Empty chain — the starting point of every modifier construction. */
    object Empty : SoulModifier

    /** A modifier composed of an outer chain plus a trailing element. */
    data class Chain(val outer: SoulModifier, val element: Element) : SoulModifier

    /** Append [element] to this chain. */
    fun then(element: Element): SoulModifier = Chain(this, element)

    /** Flatten the chain into outer-to-inner order. Useful for iteration during measure/draw. */
    fun elements(): List<Element> {
        val out = ArrayList<Element>()
        var cursor: SoulModifier = this
        // Walk inner→outer collecting, then reverse so callers see outer→inner order.
        while (cursor is Chain) {
            out += cursor.element
            cursor = cursor.outer
        }
        out.reverse()
        return out
    }
}

// ─────────────────────────── built-in modifier elements ───────────────────────────

/** Padding inside the node — children measure with insets, node size grows by 2× padding. */
data class PaddingElement(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) : SoulModifier.Element

/** Solid (rounded) background painted before children. Color is ARGB packed. */
data class BackgroundElement(val color: Int, val radius: Float = 0f) : SoulModifier.Element

/**
 * Fixed (or minimum) size override.
 *
 * `width` / `height` of `null` means "no constraint" (use the child's intrinsic size).
 * Non-null values force both min and max of that dimension to the given value.
 */
data class SizeElement(val width: Float? = null, val height: Float? = null) : SoulModifier.Element

// ─────────────────────────── extension API (call sites) ───────────────────────────

/** Add uniform padding on all four sides. */
fun SoulModifier.padding(all: Float): SoulModifier = then(PaddingElement(all, all, all, all))

/** Add horizontal and vertical padding. */
fun SoulModifier.padding(
    horizontal: Float = 0f,
    vertical: Float = 0f,
): SoulModifier = then(PaddingElement(horizontal, vertical, horizontal, vertical))

/** Add per-side padding. Missing arguments default to 0. */
fun SoulModifier.padding(
    left: Float = 0f,
    top: Float = 0f,
    right: Float = 0f,
    bottom: Float = 0f,
): SoulModifier = then(PaddingElement(left, top, right, bottom))

/** Paint a (rounded) background under the node's content. */
fun SoulModifier.background(
    color: Int,
    radius: Float = 0f,
): SoulModifier = then(BackgroundElement(color, radius))

/** Force exact size on one or both axes. Null = no override. */
fun SoulModifier.size(
    width: Float? = null,
    height: Float? = null,
): SoulModifier = then(SizeElement(width, height))

/** Convenience: square size (both axes set to the same value). */
fun SoulModifier.size(size: Float): SoulModifier = size(width = size, height = size)

/** Force the width axis only. */
fun SoulModifier.width(width: Float): SoulModifier = then(SizeElement(width = width))

/** Force the height axis only. */
fun SoulModifier.height(height: Float): SoulModifier = then(SizeElement(height = height))

/**
 * Marker element instructing the parent layout that this node should expand to fill all
 * available space along the named axis. Interpreted by [com.soulreturns.ui.foundation.Box],
 * [com.soulreturns.ui.foundation.Column] and [com.soulreturns.ui.foundation.Row]'s measure
 * passes — they pass the unbounded constraints down with min = max for the affected axis so
 * the child sizes itself to fill.
 */
data class FillElement(val width: Boolean, val height: Boolean) : SoulModifier.Element

/** Make the node fill all available width (`maxWidth` of the parent's constraints). */
fun SoulModifier.fillMaxWidth(): SoulModifier = then(FillElement(width = true, height = false))

/** Make the node fill all available height (`maxHeight` of the parent's constraints). */
fun SoulModifier.fillMaxHeight(): SoulModifier = then(FillElement(width = false, height = true))

/** Make the node fill all available space on both axes. */
fun SoulModifier.fillMaxSize(): SoulModifier = then(FillElement(width = true, height = true))
