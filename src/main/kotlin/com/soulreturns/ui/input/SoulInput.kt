package com.soulreturns.ui.input

/**
 * Frame-scoped pointer state + hit-region dispatch for the Soul UI framework.
 *
 * **Lifecycle (per frame):**
 * 1. [startFrame] — host (e.g. `NvgFrame.submit`) sets the cursor coords + clears recorded
 *    regions before composing/drawing.
 * 2. Layout nodes call [recordRegion] from their `drawSelf` when a `clickable` /
 *    `scrollable` element is in their modifier chain.
 * 3. [flush] — after draw, walks recorded regions:
 *    - Computes the new [hoveredKeys] set (read by composables next frame).
 *    - Dispatches any [queueClick] / [queueScroll] events to the deepest matching region.
 *
 * **Event arrival timing:** mouse click + scroll events come from Fabric's
 * `ScreenMouseEvents` callbacks which fire on the main thread BEFORE the render frame. The
 * host enqueues them via [queueClick] / [queueScroll]; they're consumed in [flush] *after*
 * draw so handlers see fresh hit regions. Events with no matching region are silently
 * dropped — they bubble up to Minecraft via the screen-event return contract handled by
 * `SoulGuiHudAdapter`.
 *
 * **Key stability:** [hoveredKeys] persists across frames using `equals()`-based identity.
 * Composables use the composer's `nextAutoKey()` for stable per-frame IDs; dynamic lists
 * should pass explicit content-derived keys.
 *
 * Single-threaded — all calls must happen on Minecraft's render thread.
 */
object SoulInput {
    private val regions: MutableList<HitRegion> = mutableListOf()

    /**
     * Per-frame tooltip regions, recorded by [recordTooltip]. Separate from [regions] because
     * tooltip rectangles aren't clickable — they exist purely to look up "what tooltip text
     * applies under the cursor right now" at end-of-frame. Cleared each [startFrame].
     */
    private val tooltipRegions: MutableList<TooltipRegion> = mutableListOf()

    private var pendingClick: PendingClick? = null
    private var pendingScroll: PendingScroll? = null
    private var pendingRelease: Boolean = false

    private data class TooltipRegion(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val depth: Int,
        val text: String,
    ) {
        fun contains(
            px: Float,
            py: Float,
        ): Boolean = px >= x && px < x + width && py >= y && py < y + height
    }

    /**
     * Number of panels that have called [beginPanel] but not yet [flush]. Used to coordinate
     * frame-boundary state — the last panel's flush commits the accumulated hover set and
     * clears any unconsumed pending events.
     */
    private var pendingPanels: Int = 0

    /**
     * Hover matches accumulated across every panel rendered in the current frame. Flushed
     * into [hoveredKeys] when the last panel finishes.
     */
    private val frameHoverAccumulator: MutableSet<Any> = mutableSetOf()

    /**
     * Key of the currently-focused element — typically the [com.soulreturns.ui.foundation
     * .TextField] that has the keyboard. Mouse clicks landing inside a region with a
     * `focusKey` set focus to that key; clicking outside any focusable region (or pressing
     * Escape) clears focus to `null`.
     */
    @Volatile
    var focusedKey: Any? = null
        private set

    /**
     * Per-frame queue of typed characters from `Screen.charTyped(codepoint, mods)`. The
     * focused composable drains these during its compose. Cleared on each [flush] at the
     * last-panel boundary so unconsumed typing doesn't leak into the next frame.
     */
    private val pendingChars: ArrayDeque<Int> = ArrayDeque()

    /**
     * Per-frame queue of "edit keys" (backspace, delete, arrows, etc.) from
     * `Screen.keyPressed`. Same lifecycle as [pendingChars].
     */
    private val pendingEditKeys: ArrayDeque<EditKey> = ArrayDeque()

    enum class EditKey { Backspace, Delete, Left, Right, Home, End, Enter, Escape }

    /** Latest cursor logical-pixel position. Defaults to off-screen until [startFrame] runs. */
    var cursorX: Float = -1f
        private set

    var cursorY: Float = -1f
        private set

    /**
     * Origin of the panel currently being rendered, in absolute GUI-scaled coords. Used to
     * translate event coordinates (which arrive in absolute space) into panel-local space
     * for hit-testing against [HitRegion]s, which are themselves panel-local.
     */
    private var panelOriginX: Float = 0f
    private var panelOriginY: Float = 0f

    /**
     * Scale factor applied to the current panel's NanoVG content (`nvgScale(s, s)` inside
     * the render block). Hit regions get recorded in **unscaled content coords** because
     * the layout system measures at intrinsic sizes; the cursor (and click coords) arrive
     * in **scaled screen coords**. Hit-testing therefore divides cursor by [panelScale] to
     * line them up.
     */
    private var panelScale: Float = 1f

    /** Set of region keys under the cursor at end of the previous frame's [flush]. */
    var hoveredKeys: Set<Any> = emptySet()
        private set

    /**
     * Key of the region currently being "pressed" — mouse button is held down after a click
     * that landed inside the region. Cleared on mouse-up. Survives the cursor leaving the
     * region's bounds, which is what makes drag interactions (sliders, drag-and-drop) work.
     *
     * Read this from a composable's body to drive continuous-update widgets (e.g. a slider
     * reading [cursorX] each frame while [isPressed] returns true).
     */
    var pressedKey: Any? = null
        private set

    /** Returns true if [key] was hovered as of the most recent [flush]. */
    fun isHovered(key: Any): Boolean = hoveredKeys.contains(key)

    /**
     * Announce that a panel is about to start rendering. Must be paired with a [flush] call
     * from the same panel. Increments [pendingPanels] so [flush] knows when it's the last
     * panel of a frame and can commit aggregated state.
     *
     * Call this **eagerly** (at `NvgFrame.submit` time, not inside the deferred lambda) so
     * the counter reflects all queued panels before any of them actually flushes.
     */
    fun beginPanel() {
        pendingPanels++
    }

    /** Returns true if [key] is the currently-pressed key. See [pressedKey] for semantics. */
    fun isPressed(key: Any): Boolean = pressedKey == key

    /**
     * Reset frame state. Call from the host at the very start of a frame's compose / draw
     * cycle.
     *
     * @param cursorX Panel-local cursor X, i.e. `absoluteCursor.x - panelOriginX`.
     * @param cursorY Panel-local cursor Y. `(-1f, -1f)` means the cursor is unavailable
     *   (no screen open, mouse hidden) — hit testing skips.
     * @param panelOriginX X offset of the panel within the GUI-scaled coordinate system.
     *   Used to convert absolute event coords (clicks, scrolls) into panel-local space at
     *   [flush] time.
     * @param panelOriginY See [panelOriginX].
     */
    fun startFrame(
        cursorX: Float,
        cursorY: Float,
        panelOriginX: Float = 0f,
        panelOriginY: Float = 0f,
        panelScale: Float = 1f,
    ) {
        val safeScale = panelScale.coerceAtLeast(0.001f)
        // Convert panel-local SCALED cursor → unscaled content coords so widgets reading
        // [cursorX] / [cursorY] see coordinates that match their layout-time rect.
        this.cursorX = cursorX / safeScale
        this.cursorY = cursorY / safeScale
        this.panelOriginX = panelOriginX
        this.panelOriginY = panelOriginY
        this.panelScale = safeScale
        regions.clear()
        tooltipRegions.clear()
    }

    /**
     * Record a tooltip region — paired with a [TooltipElement] in a modifier chain. Scissor
     * clipping is applied so a tooltip on a sub-item scrolled out of the viewport doesn't
     * fire when the cursor lands on a sibling outside the scrollable.
     */
    fun recordTooltip(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        depth: Int,
        text: String,
    ) {
        val s = com.soulreturns.platform.render.nvg.NvgRenderer.currentScissorBounds()
        var rx = x
        var ry = y
        var rw = width
        var rh = height
        if (s != null) {
            if (x >= s.maxX || x + width <= s.x || y >= s.maxY || y + height <= s.y) return
            rx = x.coerceAtLeast(s.x)
            ry = y.coerceAtLeast(s.y)
            rw = ((x + width).coerceAtMost(s.maxX) - rx).coerceAtLeast(0f)
            rh = ((y + height).coerceAtMost(s.maxY) - ry).coerceAtLeast(0f)
            if (rw <= 0f || rh <= 0f) return
        }
        tooltipRegions.add(TooltipRegion(rx, ry, rw, rh, depth, text))
    }

    /**
     * Look up the topmost tooltip text under the cursor for this frame, or `null` if no
     * tooltip region matches. Hosts call this from inside their NvgFrame block after
     * composing/drawing the main UI so they can paint the tooltip overlay last.
     */
    fun findHoveredTooltip(): String? {
        if (cursorX < 0f || cursorY < 0f) return null
        return tooltipRegions
            .filter { it.contains(cursorX, cursorY) }
            .maxByOrNull { it.depth }
            ?.text
    }

    /**
     * Record a hit region during the draw pass. The region is clipped to the active NanoVG
     * scissor — entirely-outside regions are dropped, partially-inside regions have their
     * bounds shrunk to the visible portion. Without the clip step, a sub-item at the very
     * bottom of a [com.soulreturns.ui.foundation.ScrollableList] whose region extends past
     * the viewport edge would intercept clicks landing in the area just below the scrollable
     * (e.g. a footer button's padding zone).
     */
    fun recordRegion(region: HitRegion) {
        val s = com.soulreturns.platform.render.nvg.NvgRenderer.currentScissorBounds()
        if (s == null) {
            regions.add(region)
            return
        }
        val rx1 = region.x
        val ry1 = region.y
        val rx2 = rx1 + region.width
        val ry2 = ry1 + region.height
        if (rx1 >= s.maxX || rx2 <= s.x || ry1 >= s.maxY || ry2 <= s.y) {
            return
        }
        val clipX = rx1.coerceAtLeast(s.x)
        val clipY = ry1.coerceAtLeast(s.y)
        val clipW = (rx2.coerceAtMost(s.maxX) - clipX).coerceAtLeast(0f)
        val clipH = (ry2.coerceAtMost(s.maxY) - clipY).coerceAtLeast(0f)
        if (clipW <= 0f || clipH <= 0f) return
        if (clipX == rx1 && clipY == ry1 && clipW == region.width && clipH == region.height) {
            regions.add(region)
        } else {
            regions.add(region.copy(x = clipX, y = clipY, width = clipW, height = clipH))
        }
    }

    /** Enqueue a click event to be matched against regions during the next [flush]. */
    fun queueClick(
        x: Float,
        y: Float,
    ) {
        pendingClick = PendingClick(x, y)
    }

    /**
     * Enqueue a scroll event. [vsd] is the vertical scroll delta in wheel notches (positive
     * = up, negative = down) as supplied by Fabric.
     */
    fun queueScroll(
        x: Float,
        y: Float,
        vsd: Float,
    ) {
        pendingScroll = PendingScroll(x, y, vsd)
    }

    /** Enqueue a mouse-up event. Cleared press state lands in the next [flush]. */
    fun queueRelease() {
        pendingRelease = true
    }

    /**
     * Set focus. Called by clickable elements on press, or programmatically (e.g. when a
     * screen first opens and wants a particular field focused).
     *
     * Pass `null` to clear focus.
     */
    fun setFocus(key: Any?) {
        focusedKey = key
    }

    /** Enqueue a Unicode codepoint from `Screen.charTyped(...)`. */
    fun queueChar(codepoint: Int) {
        pendingChars.addLast(codepoint)
    }

    /** Enqueue an edit key from `Screen.keyPressed(...)`. */
    fun queueEditKey(key: EditKey) {
        pendingEditKeys.addLast(key)
    }

    /**
     * Drain typed-character events queued since the last frame. Each call returns the chars
     * queued and removes them so the next composable doesn't double-process. Focused-element
     * composables call this during their compose to update internal text state.
     */
    fun drainChars(): List<Int> {
        if (pendingChars.isEmpty()) return emptyList()
        val copy = pendingChars.toList()
        pendingChars.clear()
        return copy
    }

    /** Drain edit-key events. Same semantics as [drainChars]. */
    fun drainEditKeys(): List<EditKey> {
        if (pendingEditKeys.isEmpty()) return emptyList()
        val copy = pendingEditKeys.toList()
        pendingEditKeys.clear()
        return copy
    }

    /**
     * End-of-frame: dispatch any queued click / scroll events, then compute hover set for
     * next frame from the cursor's current position. Returns true if any event was
     * consumed (used by the bridge to cancel the underlying Minecraft event).
     */
    fun flush(): FlushResult {
        var clickConsumed = false
        var scrollConsumed = false

        // Click + scroll consumption: only clear the pending event if it hit one of THIS
        // panel's regions. If it misses, leave it for the next panel's flush — multi-panel
        // layouts depend on this so a click in panel B isn't swallowed by panel A's earlier
        // flush. The last panel's flush below clears any unmatched leftovers.
        pendingClick?.let { c ->
            val localX = (c.x - panelOriginX) / panelScale
            val localY = (c.y - panelOriginY) / panelScale
            val hit = deepestRegionAt(localX, localY) { it.onClick != null }
            if (hit != null) {
                hit.onClick?.invoke()
                pressedKey = hit.key
                clickConsumed = true
                pendingClick = null
            }
        }
        pendingScroll?.let { s ->
            val localX = (s.x - panelOriginX) / panelScale
            val localY = (s.y - panelOriginY) / panelScale
            val hit = deepestRegionAt(localX, localY) { it.onScroll != null }
            if (hit != null) {
                hit.onScroll?.invoke(s.vsd)
                scrollConsumed = true
                pendingScroll = null
            }
        }
        if (pendingRelease) {
            pressedKey = null
            pendingRelease = false
        }

        // Accumulate hover matches from THIS panel's regions into the frame-wide set.
        val cx = cursorX
        val cy = cursorY
        if (cx >= 0f && cy >= 0f) {
            for (r in regions) {
                if (r.contains(cx, cy)) frameHoverAccumulator.add(r.key)
            }
        }

        // Last panel of the frame? Commit accumulated hover state, drop any unmatched
        // pending events (no more panels will try them).
        pendingPanels = (pendingPanels - 1).coerceAtLeast(0)
        if (pendingPanels == 0) {
            hoveredKeys = frameHoverAccumulator.toSet()
            frameHoverAccumulator.clear()
            pendingClick = null
            pendingScroll = null
        }

        return FlushResult(clickConsumed = clickConsumed, scrollConsumed = scrollConsumed)
    }

    private inline fun deepestRegionAt(
        x: Float,
        y: Float,
        predicate: (HitRegion) -> Boolean,
    ): HitRegion? {
        var best: HitRegion? = null
        for (r in regions) {
            if (!predicate(r)) continue
            if (!r.contains(x, y)) continue
            if (best == null || r.depth >= best.depth) best = r
        }
        return best
    }

    /** Reset all state. Used in tests; not needed in normal operation. */
    internal fun reset() {
        regions.clear()
        pendingClick = null
        pendingScroll = null
        pendingRelease = false
        cursorX = -1f
        cursorY = -1f
        hoveredKeys = emptySet()
        pressedKey = null
        panelOriginX = 0f
        panelOriginY = 0f
        panelScale = 1f
        pendingPanels = 0
        frameHoverAccumulator.clear()
    }

    private data class PendingClick(val x: Float, val y: Float)

    private data class PendingScroll(val x: Float, val y: Float, val vsd: Float)
}

/**
 * Result of a [SoulInput.flush] — used by the host (e.g. `SoulGuiHudAdapter`) to decide
 * whether to cancel the underlying Minecraft mouse event so it doesn't double-fire as a
 * vanilla inventory slot click.
 */
data class FlushResult(
    val clickConsumed: Boolean = false,
    val scrollConsumed: Boolean = false,
)
