package com.soulreturns.ui.input

/**
 * One axis-aligned rectangle recorded during a frame's draw pass that can receive pointer
 * events at end-of-frame dispatch.
 *
 * `clickable` / `scrollable` modifier elements emit one of these from their layout node's
 * `drawSelf`, recording the node's absolute logical-pixel position + size, the user-supplied
 * key (used for hover-state lookup next frame), and any handlers.
 *
 * `depth` is the tree depth at draw time, used to break ties when nested regions overlap —
 * the deepest matching region wins, so a button inside a card receives the click rather than
 * the card itself.
 */
data class HitRegion(
    val key: Any,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val depth: Int,
    val onClick: (() -> Unit)? = null,
    val onScroll: ((Float) -> Unit)? = null,
) {
    fun contains(
        px: Float,
        py: Float,
    ): Boolean = px >= x && px < x + width && py >= y && py < y + height
}
