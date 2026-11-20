package com.soulreturns.features.profileviewer.gui.kit

import net.minecraft.world.item.ItemStack

/**
 * The Profile Viewer's card toolkit, in one sentence: a section **builds** a small tree of [Node]s
 * every frame (pure, no side effects), the shell **measures** it at the body width and **paints** it
 * into the scrolled body. Nothing in a section knows a scroll offset, an absolute y or a GL detail.
 *
 * Why a tree instead of the old two-pass canvas: the mock's layouts are width-driven (auto-fill grids,
 * spans, shortest-column masonry, wrapping tile rows), so a card's height depends on the column
 * width the grid gives it. A tree can measure any child at any width as often as the layout needs;
 * a cursor-based canvas could not. Click handlers are the only place side effects are allowed:
 * they run on a click, never while drawing.
 */

/** Text measurement, all a node needs to size itself. Soul px. */
interface KitMeasure {
    fun width(text: String, size: Float, bold: Boolean = false): Int

    /** Width of a letter-spaced label ([tracking] in em, as CSS `letter-spacing`). */
    fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean = true): Int
}

/** Corner shapes for [KitCanvas.rect] (named, so no render pipeline leaks into section code). */
object Corner {
    const val SQUARE = 0
    const val SMALL = 1
    const val MEDIUM = 2
    const val LARGE = 3
    const val PILL = 4
}

/**
 * The surface nodes paint on, implemented by the screen. Soul-logical px. Hover, clicks and tooltips
 * are already clipped to the scrolled body: a node simply asks.
 */
interface KitCanvas : KitMeasure {
    /** Top and bottom of the visible body: nodes fully outside may skip painting. */
    val viewTop: Int
    val viewBottom: Int

    /** Wall clock for countdowns and pulses. */
    val nowMs: Long

    /** Per-section interactive state (segments, toggles, search fields). */
    val state: ViewState

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int = Corner.MEDIUM)

    /** One line of plain text, vertically centered between [top] and [bottom]. */
    fun text(text: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean = false)

    /** Letter-spaced uppercase label centered on ([cx], [cy]) (chips, table headers). */
    fun tracked(text: String, cx: Int, cy: Int, color: Int, size: Float, tracking: Float, bold: Boolean = true)

    /** A line icon `textures/gui/icons/<name>.png`, tinted. */
    fun icon(name: String, x: Int, y: Int, size: Int, color: Int)

    /** A real item (Hypixel `item_model` textures) drawn [size] px square, with its count. */
    fun item(stack: ItemStack, x: Int, y: Int, size: Int, count: Boolean = true)

    /** A player's face from the skin cache (the default skin until theirs loads). */
    fun face(uuidUndashed: String, x: Int, y: Int, size: Int)

    /** Pointer inside the rect, inside the body, and no popover or modal on top. */
    fun hovered(x0: Int, y0: Int, x1: Int, y1: Int): Boolean

    /** Run [action] when the rect is clicked (clipped to the body). */
    fun onClick(x0: Int, y0: Int, x1: Int, y1: Int, action: () -> Unit)

    /** A Soul tooltip for the hovered rect; [key] keeps a live value from restarting the delay. */
    fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>, key: String? = null)

    /** The item's vanilla tooltip, at the pointer. */
    fun itemTooltip(stack: ItemStack)

    /** A single-line text field bound to [ViewState.field]([key]). */
    fun field(key: String, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String)

    /** Switches the viewer to another section (`ctx.open` from inside a click). */
    fun openSection(id: String)
}

/**
 * A piece of the section body. [measure] must be pure (it runs any number of times per frame at
 * different widths); [paint] draws at the given box. Nodes are per-frame values: build, lay out, drop.
 */
abstract class Node {
    /** Grid columns this node takes inside a [GridNode]; [FULL] = the whole row. */
    open val span: Int get() = 1

    private var cachedW = -1
    private var cachedH = 0

    /** Height at width [w], memoized per frame (a grid asks the same width more than once). */
    fun height(m: KitMeasure, w: Int): Int {
        if (w != cachedW) {
            cachedH = measure(m, w)
            cachedW = w
        }
        return cachedH
    }

    protected abstract fun measure(m: KitMeasure, w: Int): Int

    abstract fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int)

    companion object {
        const val FULL = Int.MAX_VALUE
    }
}

/**
 * Interactive state of the open player, owned by the screen and kept across frames, profile switches
 * and section changes (cleared on a new player). Keys are namespaced by section id by [Ui], so two
 * sections never collide.
 */
class ViewState {
    private val values = HashMap<String, String>()
    private val fields = HashMap<String, com.soulreturns.ui.screen.SoulTextField>()

    fun get(key: String): String? = values[key]

    fun set(key: String, value: String) {
        values[key] = value
    }

    fun flag(key: String): Boolean = values[key] == "1"

    fun toggle(key: String) {
        values[key] = if (flag(key)) "0" else "1"
    }

    /** The text field behind [key], created empty on first use. */
    fun field(key: String): com.soulreturns.ui.screen.SoulTextField = fields.getOrPut(key) { com.soulreturns.ui.screen.SoulTextField() }

    /** The text in [key]'s field ("" when never typed in). */
    fun text(key: String): String = fields[key]?.text.orEmpty()

    /** The field that has focus, if any. */
    fun focusedField(): com.soulreturns.ui.screen.SoulTextField? = fields.values.firstOrNull { it.focused }

    fun blurFields() {
        fields.values.forEach { it.focused = false }
    }

    fun clear() {
        values.clear()
        fields.clear()
    }
}
