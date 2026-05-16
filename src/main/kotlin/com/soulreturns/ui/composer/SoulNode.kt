package com.soulreturns.ui.composer

/**
 * Inclusive min / max size constraints handed to a node during measure.
 *
 * `maxWidth` / `maxHeight` may be [Float.POSITIVE_INFINITY] meaning "unbounded — be as small
 * or large as you want". Children should fall back to a sensible intrinsic size in that case.
 * Mirrors `androidx.compose.ui.unit.Constraints` semantics, restricted to floats.
 */
data class SoulConstraints(
    val minWidth: Float = 0f,
    val maxWidth: Float = Float.POSITIVE_INFINITY,
    val minHeight: Float = 0f,
    val maxHeight: Float = Float.POSITIVE_INFINITY,
) {
    /** Clamp [w] / [h] to the constraint range and return the pair. */
    fun constrain(
        w: Float,
        h: Float,
    ): Pair<Float, Float> = w.coerceIn(minWidth, maxWidth) to h.coerceIn(minHeight, maxHeight)

    fun hasBoundedWidth(): Boolean = maxWidth != Float.POSITIVE_INFINITY

    fun hasBoundedHeight(): Boolean = maxHeight != Float.POSITIVE_INFINITY

    /** New constraints with width/height insets applied; min values floor at 0. */
    fun inset(
        horizontal: Float,
        vertical: Float,
    ): SoulConstraints =
        SoulConstraints(
            minWidth = (minWidth - horizontal).coerceAtLeast(0f),
            maxWidth = if (hasBoundedWidth()) (maxWidth - horizontal).coerceAtLeast(0f) else maxWidth,
            minHeight = (minHeight - vertical).coerceAtLeast(0f),
            maxHeight = if (hasBoundedHeight()) (maxHeight - vertical).coerceAtLeast(0f) else maxHeight,
        )

    companion object {
        fun fixed(
            width: Float,
            height: Float,
        ) = SoulConstraints(width, width, height, height)
    }
}

/**
 * Result of measuring a node — its chosen size plus the positions where each child should be
 * drawn relative to the node's own top-left.
 *
 * [childPositions] is parallel to the node's `children` list; index `i` is the offset for the
 * `i`th child. For leaf nodes (no children) the list is empty.
 */
data class SoulMeasured(
    val width: Float,
    val height: Float,
    val childPositions: List<Position> = emptyList(),
) {
    data class Position(val x: Float, val y: Float)
}

/**
 * A piece of text a [SoulNode] would draw via NanoVG, exposed to the walker so the
 * Minecraft-font dispatch in `SoulHud` can paint it through Mojang's font instead. Position
 * coordinates are in the same panel-local space the walker uses (`(node.x + offset.x,
 * node.y + offset.y)`).
 */
data class MojangTextSpec(
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val color: Int,
)

/**
 * Implemented by nodes that paint text directly via `NvgRenderer.text` rather than via a
 * child [com.soulreturns.ui.foundation.TextNode] (e.g. `TabsNode`, `DropdownNode`'s trigger).
 * The walker invokes [emitMojangTexts] with the node's computed top-left so the implementor
 * can call `emit(...)` once per piece of text it would draw. The walker then forwards each
 * spec to the SoulHud Minecraft-font pipeline.
 *
 * Implementors must ALSO skip the NanoVG `text` call in their own `drawSelf` when the
 * containing HUD has `useMinecraftFont` on — otherwise the same string would render twice
 * (Inter underneath, Mojang on top, with subtle misalignment).
 */
interface MojangTextEmitter {
    fun emitMojangTexts(
        x: Float,
        y: Float,
        clip: ClipRect?,
        emit: (MojangTextSpec) -> Unit,
    )

    /**
     * Emit any panel-local "occlusion" rectangles — regions where this node paints
     * NanoVG content (e.g. an open dropdown popup) that must NOT be overlaid by the
     * post-PIP Mojang text dispatch. The dispatcher in `SoulHud.renderOne` filters out
     * any Mojang text whose origin falls inside an occlusion rect — without this, HUD
     * row text would render on top of the popup since Mojang's `drawString` happens
     * after the PIP composite. Default is no-op for nodes that don't paint occluding
     * overlays.
     */
    fun emitOcclusions(
        x: Float,
        y: Float,
        emit: (ClipRect) -> Unit,
    ) {}
}

/**
 * Axis-aligned rectangle in composable-space coordinates. Plumbed through [SoulNode.walk]
 * by nodes that constrain their children's visible area (e.g. [com.soulreturns.ui.foundation.ScrollableList])
 * so downstream consumers — like SoulHud's Minecraft-font dispatch — can clip non-NanoVG
 * draws (which don't honor the NVG scissor stack) to the same viewport the NVG draw would
 * use.
 */
data class ClipRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    /** Intersection with [other]; null if the rectangles don't overlap. */
    fun intersect(other: ClipRect): ClipRect? {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val right = minOf(x + width, other.x + other.width)
        val bottom = minOf(y + height, other.y + other.height)
        if (right <= left || bottom <= top) return null
        return ClipRect(left, top, right - left, bottom - top)
    }
}

/**
 * Base class for all Soul UI tree nodes.
 *
 * The runtime produces a tree of [SoulNode] each frame by re-running the composable lambda
 * provided to [SoulComposer.build]. Each node knows how to:
 *  - [measure] itself + children given size constraints,
 *  - [drawSelf] its own visual (background, text, etc.) at a given screen position,
 *  - the children list is owned by the node — the [SoulComposer] populates it during build.
 *
 * **Lifecycle:** instances are produced fresh every frame. Don't hold references across
 * frames or store input/animation state on the node — use external state holders instead.
 */
abstract class SoulNode {
    /** Modifier chain attached at composition time. Default: empty. */
    open val modifier: SoulModifier = SoulModifier.Empty

    /** Children added during composition. Mutable so [SoulComposer] can attach. */
    val children: MutableList<SoulNode> = mutableListOf()

    /** Result of the most recent [measure] call; null until measured. */
    var measured: SoulMeasured? = null
        protected set

    /**
     * Compute this node's size + child placements given parent [constraints].
     *
     * Implementations should:
     *  1. Honor modifier-driven constraints (size, padding) — see [SoulModifier.elements].
     *  2. Measure each child with appropriate inner constraints.
     *  3. Compute final width/height and child positions.
     *  4. Store the result via [SoulMeasured].
     */
    abstract fun measure(constraints: SoulConstraints): SoulMeasured

    /**
     * Render the node's own visual at logical screen position `(x, y)`. [depth] is the
     * tree depth — used by clickable/scrollable modifiers to break ties when multiple
     * nested regions match a cursor position (the deepest wins).
     */
    abstract fun drawSelf(
        x: Float,
        y: Float,
        depth: Int,
    )

    /**
     * Public entry point: measure (if not already) and draw the node + all children
     * recursively. The runtime calls this on the root node once per frame.
     *
     * [depth] starts at 0 at the root and increments by 1 for each level deeper. Used by
     * input-modifier hit-region recording so click events route to the deepest matching
     * node (e.g. a button inside a card claims the click, not the card).
     */
    open fun draw(
        x: Float,
        y: Float,
        constraints: SoulConstraints,
        depth: Int = 0,
    ) {
        if (measured == null) measure(constraints)
        val m = measured!!
        drawSelf(x, y, depth)
        children.forEachIndexed { i, child ->
            val pos = m.childPositions.getOrNull(i) ?: SoulMeasured.Position(0f, 0f)
            child.draw(
                x + pos.x,
                y + pos.y,
                SoulConstraints.fixed(child.measured?.width ?: 0f, child.measured?.height ?: 0f),
                depth + 1,
            )
        }
    }

    /**
     * Read-only traversal that visits this node + every descendant at its computed
     * composable-space position. Uses the cached `measured` child positions — call after a
     * [measure] / [draw] pass (positions are populated during measure).
     *
     * Differs from [draw] in that it doesn't invoke [drawSelf] and doesn't touch NVG /
     * hit-region state — safe to call OUTSIDE an active NanoVG frame. Used by the
     * SoulHud Minecraft-font pipeline to harvest [com.soulreturns.ui.foundation.TextNode]
     * positions before the NVG block runs (deferred), so the corresponding Mojang text
     * draws can be queued in the GuiRenderState ABOVE the PIP composite.
     *
     * [clip] is the active panel-local clip rectangle. Nodes that introduce scissor /
     * scroll (e.g. [com.soulreturns.ui.foundation.ScrollableList]) override this to narrow
     * the clip and apply scroll offsets to their children's positions. Leaves and the
     * default base impl pass the clip through unchanged.
     */
    open fun walk(
        x: Float,
        y: Float,
        clip: ClipRect? = null,
        visit: (node: SoulNode, x: Float, y: Float, clip: ClipRect?) -> Unit,
    ) {
        visit(this, x, y, clip)
        val m = measured ?: return
        children.forEachIndexed { i, child ->
            val pos = m.childPositions.getOrNull(i) ?: SoulMeasured.Position(0f, 0f)
            child.walk(x + pos.x, y + pos.y, clip, visit)
        }
    }
}
