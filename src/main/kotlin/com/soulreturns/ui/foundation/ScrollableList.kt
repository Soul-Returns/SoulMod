package com.soulreturns.ui.foundation

import com.soulreturns.platform.render.nvg.NvgRenderer
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
import com.soulreturns.ui.input.HitRegion
import com.soulreturns.ui.input.SoulInput

/**
 * Vertical scrollable container — children laid out top-to-bottom, clipped to the viewport
 * defined by this node's measured bounds, scrollable via the mouse wheel.
 *
 * Stateless — the caller owns [scrollOffset] (logical pixels from the top of the content)
 * and receives [onScroll] with the **new clamped offset** (not a delta). The widget
 * computes `maxScroll = (contentHeight - viewportHeight)` internally and clamps to
 * `[0, maxScroll]` before invoking the callback — so the caller can just store whatever
 * value arrives without writing its own clamp logic.
 *
 * ```
 * var scroll = 0f
 * ScrollableList(
 *     scrollOffset = scroll,
 *     onScroll = { newOffset -> scroll = newOffset },
 *     modifier = SoulModifier.Empty.fillMaxWidth().height(120f),
 * ) { items.forEach { Text(it) } }
 * ```
 *
 * The list **requires a bounded height** via modifier (`.height(N)` or `.fillMaxHeight()`).
 * Without it the viewport equals content and scrolling is meaningless.
 *
 * Wheel scrolling fires only while a container screen is open (since the input adapter
 * gates scroll events on `AbstractContainerScreen`).
 */
@SoulComposable
fun ScrollableList(
    scrollOffset: Float,
    onScroll: (newOffset: Float) -> Unit,
    modifier: SoulModifier = SoulModifier.Empty,
    gap: Float = 0f,
    /** Pixels of scroll-offset change per wheel notch. Tune for the row height. */
    scrollStep: Float = 10f,
    key: Any = SoulComposer.current.nextAutoKey(),
    content: @SoulComposable () -> Unit,
) {
    SoulComposer.current.composable(
        ScrollableListNode(
            scrollOffset = scrollOffset,
            gap = gap,
            scrollStep = scrollStep,
            ownerKey = key,
            onScroll = onScroll,
            modifier = modifier,
        ),
        content,
    )
}

internal class ScrollableListNode(
    private val scrollOffset: Float,
    private val gap: Float,
    private val scrollStep: Float,
    private val ownerKey: Any,
    private val onScroll: (Float) -> Unit,
    override val modifier: SoulModifier,
) : SoulNode() {
    /** Total content height (sum of children + gaps + padding). Set in measure. */
    private var contentHeight: Float = 0f

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val outer = modifier.applySizeOverride(constraints)
        val padH = modifier.totalPaddingHorizontal()
        val padV = modifier.totalPaddingVertical()
        val offset = modifier.contentOffset()

        val inner =
            SoulConstraints(
                minWidth = 0f,
                maxWidth = if (outer.hasBoundedWidth()) (outer.maxWidth - padH).coerceAtLeast(0f) else Float.POSITIVE_INFINITY,
                minHeight = 0f,
                maxHeight = Float.POSITIVE_INFINITY,
            )
        val childMeasured = children.map { it.measure(inner) }
        val maxChildW = childMeasured.maxOfOrNull { it.width } ?: 0f

        var cursorY = 0f
        val positions =
            childMeasured.mapIndexed { i, m ->
                val pos = SoulMeasured.Position(offset.x, offset.y + cursorY)
                cursorY += m.height
                if (i < children.size - 1) cursorY += gap
                pos
            }

        val totalW = maxChildW + padH
        val totalH = cursorY + padV
        val (w, h) = outer.constrain(totalW, totalH)
        contentHeight = totalH
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
        // Record a scroll region directly — we own the handler so it can use up-to-date
        // contentHeight / viewport math at flush time. Standard `.scrollable()` modifier
        // would capture the lambda at compose time, before measure runs.
        val viewportHeight = m.height
        val maxScroll = (contentHeight - viewportHeight).coerceAtLeast(0f)
        SoulInput.recordRegion(
            HitRegion(
                key = ownerKey,
                x = x,
                y = y,
                width = m.width,
                height = m.height,
                depth = depth,
                onScroll = { wheelDelta ->
                    // Positive wheelDelta = wheel up = content scrolls up = offset goes down.
                    val newOffset = (scrollOffset - wheelDelta * scrollStep).coerceIn(0f, maxScroll)
                    onScroll(newOffset)
                },
            ),
        )
    }

    override fun draw(
        x: Float,
        y: Float,
        constraints: SoulConstraints,
        depth: Int,
    ) {
        if (measured == null) measure(constraints)
        val m = measured!!
        drawSelf(x, y, depth)

        // Clamp scroll offset at draw time too — if the caller passes a stale value out of
        // bounds (e.g. content shrank since the last render), we render at the clamped pos.
        val viewportHeight = m.height
        val maxScroll = (contentHeight - viewportHeight).coerceAtLeast(0f)
        val clampedScroll = scrollOffset.coerceIn(0f, maxScroll)

        val viewportTop = y
        val viewportBottom = y + viewportHeight
        NvgRenderer.pushScissor(x, y, m.width, m.height)
        try {
            children.forEachIndexed { i, child ->
                val pos = m.childPositions.getOrNull(i) ?: SoulMeasured.Position(0f, 0f)
                val childTop = y + pos.y - clampedScroll
                val childH = child.measured?.height ?: 0f
                val childBottom = childTop + childH
                // Skip entirely off-screen children. The scissor would clip them visually,
                // but their `draw()` still records hit regions — leading to click-through
                // bugs where an off-screen sidebar item steals clicks meant for a sibling
                // composable below the scroll list (e.g. a "Move GUI" footer button).
                if (childBottom < viewportTop || childTop > viewportBottom) return@forEachIndexed
                child.draw(
                    x + pos.x,
                    childTop,
                    SoulConstraints.fixed(child.measured?.width ?: 0f, childH),
                    depth + 1,
                )
            }
        } finally {
            NvgRenderer.popScissor()
        }
    }
}
