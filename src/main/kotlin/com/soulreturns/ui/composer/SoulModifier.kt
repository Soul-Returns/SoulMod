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

/**
 * Which corners participate in a [BackgroundElement]'s radius. Used to round only the
 * corners of a child panel that touch the parent's outer rounded edge — e.g. a top-nav
 * inside a rounded card rounds only its top corners; its bottom edge stays square because
 * it meets the body content along a flat boundary.
 *
 * Single-corner variants (`TopLeft` etc.) are used when an inner panel touches only one
 * outer corner of the card — e.g. a sidebar that sits below a top nav touches only the
 * card's bottom-left corner.
 */
enum class CornerRounding {
    Full,
    Top,
    Bottom,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
}

/**
 * Solid background painted before children. Color is ARGB packed; [radius] applies only to
 * the corners selected by [rounding] (default: all four). When [rounding] is `Top` / `Bottom`
 * the renderer uses `NvgRenderer.halfRoundedRect` so the unrounded edge is straight.
 */
data class BackgroundElement(
    val color: Int,
    val radius: Float = 0f,
    val rounding: CornerRounding = CornerRounding.Full,
) : SoulModifier.Element

/**
 * Linear-gradient background painted before children. Both colors are ARGB; the gradient
 * interpolates from [color1] (at the start edge of [direction]) to [color2] (at the end).
 * Use a fully-transparent color at one end (`0x00000000`) to fade into whatever's behind.
 */
data class GradientBackgroundElement(
    val color1: Int,
    val color2: Int,
    val direction: com.soulreturns.platform.render.nvg.NvgGradient,
    val radius: Float = 0f,
) : SoulModifier.Element

/** Which side(s) a [BoxShadowElement] projects its shadow onto. */
enum class ShadowSide { Top, Right, Bottom, Left, All }

/**
 * CSS-style outer box-shadow — a soft dark zone painted **outside** the node's bounds.
 * Use [side] to project the shadow only onto a specific neighbour (`Right` for a sidebar
 * that should fade into the body, `Bottom` for a top nav that should fade downward, etc).
 *
 * For `side = All` the implementation uses `NvgRenderer.dropShadow` (a four-sided ring).
 * For a specific side, it paints a directional gradient strip adjacent to that edge.
 *
 * [blur] is the falloff radius; [spread] expands the inner edge outward before the blur.
 */
data class BoxShadowElement(
    val blur: Float,
    val spread: Float = 0f,
    val side: ShadowSide = ShadowSide.All,
    /**
     * Color at the source-adjacent edge for directional shadows. Default is the same
     * translucent black `dropShadow` uses internally. Pass the panel's bg color (fully
     * opaque) for a "panel extends and fades" effect — the gradient begins seamlessly
     * adjacent to the panel and fades to transparent.
     */
    val color: Int = 0x80000000.toInt(),
) : SoulModifier.Element

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
    rounding: CornerRounding = CornerRounding.Full,
): SoulModifier = then(BackgroundElement(color, radius, rounding))

/**
 * Paint a (rounded) linear-gradient background under the node's content. The gradient runs
 * from [color1] at the start of [direction] to [color2] at the end — use a transparent color
 * at one end to fade into whatever's behind (e.g. for a soft sidebar edge that bleeds into
 * the content panel).
 */
fun SoulModifier.gradientBackground(
    color1: Int,
    color2: Int,
    direction: com.soulreturns.platform.render.nvg.NvgGradient,
    radius: Float = 0f,
): SoulModifier = then(GradientBackgroundElement(color1, color2, direction, radius))

/**
 * Add a CSS-style outer box-shadow. Paints a soft dark ring outside the node's bounds; pair
 * with a `.background(...)` modifier on the same node so the shadow has a visible "from"
 * shape. The shadow uses the background's radius (if any) so rounded panels look right.
 */
fun SoulModifier.boxShadow(
    blur: Float,
    spread: Float = 0f,
    side: ShadowSide = ShadowSide.All,
    color: Int = 0x80000000.toInt(),
): SoulModifier = then(BoxShadowElement(blur, spread, side, color))

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

/**
 * Claim a proportional share of the parent's *leftover* main-axis space after unweighted
 * siblings have measured at their intrinsic size. Mirrors Compose's `Modifier.weight(...)`.
 *
 * Only honored by [com.soulreturns.ui.foundation.Row] and [com.soulreturns.ui.foundation.Column]
 * when their own main axis is bounded — under an unbounded parent there is no "leftover"
 * to distribute, so weighted children fall back to their intrinsic size.
 *
 * If multiple weighted siblings exist, each gets `weight / totalWeight` of the remaining
 * space. Combine with `Arrangement.SpaceBetween` etc. only if you want extra spacing on
 * top of the weighted split — weights consume all leftover space themselves.
 */
data class WeightElement(val weight: Float) : SoulModifier.Element

fun SoulModifier.weight(weight: Float): SoulModifier = then(WeightElement(weight))

/** Internal lookup used by Row/Column measure passes. Returns `0f` when no weight is set. */
internal fun SoulModifier.weightValue(): Float = elements().filterIsInstance<WeightElement>().firstOrNull()?.weight ?: 0f
