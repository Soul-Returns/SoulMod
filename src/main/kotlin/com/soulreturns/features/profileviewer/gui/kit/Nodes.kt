package com.soulreturns.features.profileviewer.gui.kit

import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import kotlin.math.max
import kotlin.math.min

// Line heights of the mock's type sizes (CSS line-height at the Soul font).
internal const val LH_BODY = 14
internal const val LH_SMALL = 13
internal const val LH_CAPTION = 12

/** A node that has a natural width when laid out in a row (chips, segments, links, fields). */
interface Inline {
    fun inlineWidth(m: KitMeasure): Int
}

/**
 * Draws a rect with a 1 px inside edge (CSS `box-shadow: inset 0 0 0 1px`). An opaque fill goes over
 * an edge-colored rect; a translucent (or empty) fill gets straight edge lines instead, stopping short
 * of the rounded corners, so the edge color never shows through it.
 */
internal fun KitCanvas.framed(x0: Int, y0: Int, x1: Int, y1: Int, bg: Int, edge: Int, corner: Int) {
    if (edge == 0) {
        rect(x0, y0, x1, y1, bg, corner)
    } else if (bg ushr 24 == 0xFF) {
        rect(x0, y0, x1, y1, edge, corner)
        rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, bg, corner)
    } else {
        if (bg ushr 24 != 0) rect(x0, y0, x1, y1, bg, corner)
        val r = if (corner == Corner.LARGE) 8 else if (corner == Corner.MEDIUM) 5 else if (corner == Corner.SMALL) 3 else 0
        rect(x0 + r, y0, x1 - r, y0 + 1, edge, Corner.SQUARE)
        rect(x0 + r, y1 - 1, x1 - r, y1, edge, Corner.SQUARE)
        rect(x0, y0 + r, x0 + 1, y1 - r, edge, Corner.SQUARE)
        rect(x1 - 1, y0 + r, x1, y1 - r, edge, Corner.SQUARE)
    }
}

/** Fraction bar (`.bar`): accent fill, gold when [maxed]. */
internal fun KitCanvas.bar(x: Int, y: Int, w: Int, f: Float, maxed: Boolean, h: Int = S.BAR_H, track: Int = S.BAR_TRACK, fill: Int = S.ACCENT) {
    rect(x, y, x + w, y + h, track, Corner.PILL)
    val fw = if (maxed) w else (w * f.coerceIn(0f, 1f)).toInt()
    if (fw > 0) rect(x, y, x + max(fw, h), y + h, if (maxed) S.MAXED else fill, Corner.PILL)
}

// ------------------------------------------------------------------ chips

/**
 * A small uppercase tag (`.chip`). Presets cover every chip the mock uses; [tip] shows on hover.
 */
class Chip(val text: String, val fg: Int, val bg: Int, val edge: Int = 0, val tip: String? = null) {
    fun width(m: KitMeasure): Int = m.trackedWidth(text, S.CHIP, S.CHIP_TRACKING) + S.CHIP_PAD_X * 2

    fun paint(c: KitCanvas, x: Int, cy: Int, tipKey: String? = null): Int {
        val w = width(c)
        val y0 = cy - S.CHIP_H / 2
        c.framed(x, y0, x + w, y0 + S.CHIP_H, bg, edge, Corner.SMALL)
        c.tracked(text, x + w / 2, cy, fg, S.CHIP, S.CHIP_TRACKING)
        if (tip != null) c.tooltip(x, y0, x + w, y0 + S.CHIP_H, listOf(tip), tipKey ?: "chip:$text")
        return w
    }

    fun withTip(tip: String): Chip = Chip(text, fg, bg, edge, tip)

    companion object {
        fun plain(text: String, tip: String? = null) = Chip(text, S.CHIP_TEXT, S.CHIP_BG, tip = tip)
        fun soul(text: String = "SOUL", tip: String? = null) = Chip(text, S.ACCENT_LIGHT, S.CHIP_SOUL_BG, tip = tip)
        fun onlyYou(tip: String? = "Stored in your stats.json. Other players never see it.") =
            Chip("ONLY YOU", S.CHIP_ONLY_TEXT, S.CHIP_ONLY_BG, S.CHIP_ONLY_EDGE, tip)
        fun you() = Chip("YOU", 0xFFFFFFFF.toInt(), S.CHIP_YOU_BG)
        fun accent(text: String, tip: String? = null) = Chip(text, S.CHIP_ACC_TEXT, S.CHIP_ACC_BG, tip = tip)
        fun warn(text: String, tip: String? = null) = Chip(text, S.MAXED, S.CHIP_WARN_BG, tip = tip)

        /** Profile game-mode chip (`.chip.mode`): ironman / island (Stranded) / bingo; null for classic. */
        fun gameMode(mode: String?, withTip: Boolean = false): Chip? {
            if (mode.isNullOrEmpty()) return null
            return when (mode) {
                "ironman" -> Chip("IRONMAN", 0xFFD8D8D8.toInt(), 0xFF33363B.toInt(), tip = if (withTip) "Ironman: no trading, no auctions, no bazaar." else null)
                "island" -> Chip("STRANDED", 0xFF8FE08F.toInt(), 0xFF1D3320.toInt(), tip = if (withTip) "Stranded: alone on one island." else null)
                "bingo" -> Chip("BINGO", S.MAXED, S.CHIP_WARN_BG, tip = if (withTip) "Bingo: a seasonal profile with goals." else null)
                else -> plain(mode.uppercase())
            }
        }
    }
}


/** Chips side by side, [S.CHIP_GAP] apart. */
class ChipsNode(val chips: List<Chip>) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = chips.sumOf { it.width(m) } + S.CHIP_GAP * max(0, chips.size - 1)
    override fun measure(m: KitMeasure, w: Int): Int = if (chips.isEmpty()) 0 else S.CHIP_H
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var cx = x
        for (chip in chips) cx += chip.paint(c, cx, y + h / 2) + S.CHIP_GAP
    }
}

// ------------------------------------------------------------------ text

/**
 * Text: one line (ellipsized) or wrapped. [align] -1 left, 0 center, 1 right.
 */
class TextNode(
    val text: Rich,
    val size: Float = S.BODY,
    val color: Int = S.TEXT,
    val bold: Boolean = false,
    val wrap: Boolean = true,
    val lineH: Int = LH_BODY,
    val align: Int = -1,
    val tip: List<String>? = null,
) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = text.width(m, size, bold)
    override fun measure(m: KitMeasure, w: Int): Int = if (wrap) text.wrap(m, size, w, bold).size * lineH else lineH
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val lines = if (wrap) text.wrap(c, size, w, bold) else listOf(text.ellipsize(c, size, w, bold))
        var ly = y
        for (line in lines) {
            val lw = line.width(c, size, bold)
            val lx = if (align < 0) x else if (align == 0) x + (w - lw) / 2 else x + w - lw
            line.draw(c, lx, ly, ly + lineH, size, color, bold)
            ly += lineH
        }
        if (tip != null) c.tooltip(x, y, x + w, y + h, tip, "text:${text.plain}")
    }
}

/** A clickable text link (`a`, `.lnk`): light accent, white on hover. */
class LinkNode(val label: String, val size: Float = S.CAPTION, val onClick: () -> Unit) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = m.width(label, size)
    override fun measure(m: KitMeasure, w: Int): Int = LH_CAPTION
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val lw = inlineWidth(c)
        val hover = c.hovered(x, y, x + lw, y + h)
        c.text(label, x, y, y + h, if (hover) 0xFFFFFFFF.toInt() else S.ACCENT_LIGHT, size)
        c.onClick(x, y, x + lw, y + h, onClick)
    }
}

/** Uppercase small group label (`.cosh`, `.gl-h`). */
class LabelNode(val text: String, val color: Int = S.TEXT_FAINT) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = LH_CAPTION
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val tw = c.trackedWidth(text.uppercase(), S.LABEL, S.CHIP_TRACKING)
        c.tracked(text.uppercase(), x + tw / 2, y + h / 2, color, S.LABEL, S.CHIP_TRACKING)
    }
}

/** A fixed vertical gap (or, inside a row, a flexible spacer when [grow]). */
class SpaceNode(val h: Int, val grow: Boolean = false) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = if (grow) 0 else h
    override fun measure(m: KitMeasure, w: Int): Int = if (grow) 0 else h
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) = Unit
}

/** A line icon. */
class IconNode(val name: String, val size: Int = 14, val color: Int = S.TEXT_DIM) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = size
    override fun measure(m: KitMeasure, w: Int): Int = size
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) = c.icon(name, x, y + (h - size) / 2, size, color)
}

/** A player's face. */
class FaceNode(val uuid: String, val size: Int) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = size
    override fun measure(m: KitMeasure, w: Int): Int = size
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) = c.face(uuid, x, y + (h - size) / 2, size)
}

/** Pulsing placeholder lines (`.skel-lines`), for a card whose data is still loading. */
class SkeletonNode(val lines: Int) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = lines * 8 + (lines - 1) * 7
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val t = (c.nowMs % 2200) / 1100f
        val k = if (t > 1f) 2f - t else t
        val shade = 0x26 + (k * 10).toInt()
        val color = (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade
        for (i in 0 until lines) {
            val ly = y + i * 15
            val lw = if (i == lines - 1) w * 3 / 5 else w
            c.rect(x, ly, x + lw, ly + 8, color, Corner.SMALL)
        }
    }
}

/** An escape hatch: a section-specific drawing (a tree, a map) with its own measure and paint. */
class CustomNode(
    private val heightAt: (KitMeasure, Int) -> Int,
    override val span: Int = 1,
    private val draw: (KitCanvas, Int, Int, Int, Int) -> Unit,
) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = heightAt(m, w)
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) = draw(c, x, y, w, h)
}

// ------------------------------------------------------------------ containers

/** Children top to bottom, [gap] apart; consecutive [LevelRowNode]s (`.cl`) stack flush, as in the mock. */
open class StackNode(val children: List<Node>, val gap: Int = S.GAP, override val span: Int = 1) : Node() {
    private fun gapBefore(prev: Node?, ch: Node): Int = if (prev == null) 0 else if (prev is LevelRowNode && ch is LevelRowNode) 0 else gap

    override fun measure(m: KitMeasure, w: Int): Int {
        var h = 0
        var prev: Node? = null
        for (ch in children) {
            val chH = ch.height(m, w)
            if (chH == 0 && ch is SpaceNode) continue
            h += gapBefore(prev, ch) + chH
            prev = ch
        }
        return h
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var cy = y
        var prev: Node? = null
        for (ch in children) {
            val chH = ch.height(c, w)
            if (chH == 0 && ch is SpaceNode) continue
            cy += gapBefore(prev, ch)
            if (cy + chH >= c.viewTop && cy <= c.viewBottom) ch.paint(c, x, cy, w, chH)
            cy += chH
            prev = ch
        }
    }
}

/**
 * Children left to right, vertically centered. [Inline] children take their natural width; the
 * others (and grow spacers) share what is left equally.
 */
class RowNode(val children: List<Node>, val gap: Int = 8, val alignTop: Boolean = false) : Node(), Inline {
    private fun widths(m: KitMeasure, w: Int): IntArray {
        val out = IntArray(children.size)
        var used = gap * max(0, children.size - 1)
        var flex = 0
        for ((i, ch) in children.withIndex()) {
            if (ch is Inline && !(ch is SpaceNode && ch.grow)) {
                out[i] = ch.inlineWidth(m)
                used += out[i]
            } else {
                out[i] = -1
                flex++
            }
        }
        if (flex > 0) {
            val each = max(0, (w - used) / flex)
            for (i in out.indices) if (out[i] < 0) out[i] = each
        }
        return out
    }

    override fun inlineWidth(m: KitMeasure): Int =
        children.sumOf { if (it is Inline) it.inlineWidth(m) else 0 } + gap * max(0, children.size - 1)

    override fun measure(m: KitMeasure, w: Int): Int {
        val ws = widths(m, w)
        return children.indices.maxOfOrNull { children[it].height(m, ws[it]) } ?: 0
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val ws = widths(c, w)
        var cx = x
        for ((i, ch) in children.withIndex()) {
            val chH = ch.height(c, ws[i])
            val cy = if (alignTop) y else y + (h - chH) / 2
            ch.paint(c, cx, cy, ws[i], chH)
            cx += ws[i] + gap
        }
    }
}

/**
 * `.grid`: auto-fill columns at least [min] wide, items placed row by row honoring [Node.span]
 * (`grid-column: span n` / `1 / -1`); rows are as tall as their tallest item, items keep their own
 * height (`align-items: start`). With [masonry] the items flow into the shortest of at most
 * [KitStyle.MASONRY_MAX_COLS] columns instead (`.mason`), spans ignored.
 */
class GridNode(val min: Int, val items: List<Node>, val masonry: Boolean = false, override val span: Int = 1) : Node() {
    private class Plan(val x: IntArray, val y: IntArray, val w: IntArray, val h: Int)

    private var planW = -1
    private var plan: Plan? = null

    private fun plan(m: KitMeasure, w: Int): Plan {
        plan?.let { if (planW == w) return it }
        val n = items.size
        val xs = IntArray(n)
        val ys = IntArray(n)
        val ws = IntArray(n)
        var total: Int
        if (masonry) {
            val cols = KitLayout.masonryColumns(w, min, S.GAP, S.MASONRY_MAX_COLS)
            val tracks = KitLayout.trackX(w, cols, S.GAP)
            val cw = KitLayout.spanWidth(tracks, 0, 1, S.GAP)
            val heights = items.map { it.height(m, cw) }
            val colOf = KitLayout.masonry(heights, cols, S.GAP)
            val bottoms = IntArray(cols)
            for (i in 0 until n) {
                val col = colOf[i]
                xs[i] = tracks[col]
                ws[i] = KitLayout.spanWidth(tracks, col, 1, S.GAP)
                ys[i] = bottoms[col]
                bottoms[col] += heights[i] + S.GAP
            }
            total = max(0, (bottoms.maxOrNull() ?: 0) - S.GAP)
        } else {
            val cols = KitLayout.autoFillColumns(w, min, S.GAP)
            val tracks = KitLayout.trackX(w, cols, S.GAP)
            val cells = KitLayout.place(items.map { it.span }, cols)
            val rowCount = (cells.maxOfOrNull { it.row } ?: -1) + 1
            val rowH = IntArray(rowCount)
            for ((i, cell) in cells.withIndex()) {
                ws[i] = KitLayout.spanWidth(tracks, cell.col, cell.span, S.GAP)
                xs[i] = tracks[cell.col]
                rowH[cell.row] = max(rowH[cell.row], items[i].height(m, ws[i]))
            }
            val rowY = IntArray(rowCount)
            var y = 0
            for (r in 0 until rowCount) {
                rowY[r] = y
                y += rowH[r] + S.GAP
            }
            for ((i, cell) in cells.withIndex()) ys[i] = rowY[cell.row]
            total = max(0, y - S.GAP)
        }
        return Plan(xs, ys, ws, total).also {
            plan = it
            planW = w
        }
    }

    override fun measure(m: KitMeasure, w: Int): Int = plan(m, w).h

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val p = plan(c, w)
        for ((i, item) in items.withIndex()) {
            val ih = item.height(c, p.w[i])
            val iy = y + p.y[i]
            if (iy + ih < c.viewTop || iy > c.viewBottom) continue
            item.paint(c, x + p.x[i], iy, p.w[i], ih)
        }
    }
}

/**
 * `.cols3`: columns of stacked cards that wrap below [basis] px each (`flex: 1 1 <basis>`). Each
 * column is its own [StackNode], so cards in one column never wait for another column's height.
 */
class ColumnsNode(val columns: List<List<Node>>, val basis: Int = S.COLUMN_BASIS) : Node() {
    private fun lines(w: Int) = KitLayout.flexLines(columns.size, w, basis, S.GAP)

    override fun measure(m: KitMeasure, w: Int): Int {
        var idx = 0
        var h = 0
        for ((n, count) in lines(w).withIndex()) {
            if (n > 0) h += S.GAP
            val tracks = KitLayout.trackX(w, count, S.GAP)
            var lineH = 0
            for (k in 0 until count) {
                lineH = max(lineH, StackNode(columns[idx + k]).height(m, KitLayout.spanWidth(tracks, k, 1, S.GAP)))
            }
            h += lineH
            idx += count
        }
        return h
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var idx = 0
        var ly = y
        for (count in lines(w)) {
            val tracks = KitLayout.trackX(w, count, S.GAP)
            var lineH = 0
            for (k in 0 until count) {
                val cw = KitLayout.spanWidth(tracks, k, 1, S.GAP)
                val stack = StackNode(columns[idx + k])
                val sh = stack.height(c, cw)
                stack.paint(c, x + tracks[k], ly, cw, sh)
                lineH = max(lineH, sh)
            }
            ly += lineH + S.GAP
            idx += count
        }
    }
}

/**
 * Two areas side by side with a fixed-width [left] (`.ov-top`: 118 px figure | 1fr), the left one
 * as tall as the right column (a single left child is painted at that full height, `grid-row: span 2`).
 * When the right column is more than [maxStretch] times the left child's own height, the left child keeps
 * its own height at the top instead of becoming a tall, mostly empty box.
 */
class SplitNode(val leftW: Int, val left: Node, val right: Node, val gap: Int = S.GAP, val minRightW: Int = 0, val maxStretch: Float = Float.POSITIVE_INFINITY) : Node() {
    private fun stacked(w: Int) = minRightW > 0 && w - leftW - gap < minRightW
    private val leftOnly get() = (left as? StackNode)?.children?.singleOrNull() ?: left

    override fun measure(m: KitMeasure, w: Int): Int =
        if (stacked(w)) left.height(m, w) + gap + right.height(m, w) else max(left.height(m, leftW), right.height(m, w - leftW - gap))

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        if (stacked(w)) {
            val lh = left.height(c, w)
            left.paint(c, x, y, w, lh)
            right.paint(c, x, y + lh + gap, w, right.height(c, w))
            return
        }
        val natural = if (maxStretch.isFinite()) leftOnly.height(c, leftW) else 0
        leftOnly.paint(c, x, y, leftW, if (maxStretch.isFinite() && h > natural * maxStretch) natural else h)
        right.paint(c, x + leftW + gap, y, w - leftW - gap, right.height(c, w - leftW - gap))
    }
}

// ------------------------------------------------------------------ cards

/**
 * A card (`.c`). [kind] picks the look: [PLAIN], [SHARED] (Soul data another player shares: blue edge
 * + SOUL chip), [MINE] (your own shareable Soul data), [LOCAL] (stats.json only: SOUL + ONLY YOU),
 * [EMPTY] (`.soul-empty`), [GENERIC] (a Soul namespace without a designed card), [LATER] (outline only).
 */
class CardNode(
    val title: String?,
    val body: List<Node>,
    val kind: Int = PLAIN,
    val chips: List<Chip> = emptyList(),
    val sub: Rich? = null,
    val meta: Node? = null,
    val tools: List<Node> = emptyList(),
    val titleTip: String? = null,
    val bodyGap: Int = 6,
    override val span: Int = 1,
    val padX: Int = S.CARD_PAD_X,
    val padTop: Int = S.CARD_PAD_TOP,
    val padBottom: Int = S.CARD_PAD_BOTTOM,
) : Node() {
    private val allChips: List<Chip> = when (kind) {
        SHARED -> listOf(Chip.soul(tip = "Collected by Soul and shared by this player. Hypixel does not track it."))
        LOCAL -> listOf(Chip.soul(tip = "Collected by Soul on this computer."), Chip.onlyYou())
        MINE -> listOf(Chip.soul(tip = "Collected by Soul. You choose whether others see it (Share my Soul stats)."))
        GENERIC -> listOf(Chip.soul())
        else -> emptyList()
    } + chips
    private val stack = StackNode(body, bodyGap)

    /** Header height, and whether the tools wrapped onto a second header line. */
    private fun header(m: KitMeasure, w: Int): Pair<Int, Boolean> {
        if (title == null && tools.isEmpty() && meta == null) return 0 to false
        val fixed = titleW(m) + chipsW(m) + metaW(m) + toolsW(m)
        val wraps = tools.isNotEmpty() && fixed > w
        val toolsH = tools.maxOfOrNull { it.height(m, (it as? Inline)?.inlineWidth(m) ?: w) } ?: 0
        val line = max(S.CARD_HEAD_H, if (wraps) 0 else toolsH)
        return (if (wraps) line + 4 + toolsH else line) to wraps
    }

    private fun titleW(m: KitMeasure) = if (title == null) 0 else m.width(title, S.BODY, true)
    private fun chipsW(m: KitMeasure) = allChips.sumOf { it.width(m) + S.CHIP_GAP }
    private fun metaW(m: KitMeasure) = if (meta == null) 0 else 8 + ((meta as? Inline)?.inlineWidth(m) ?: 0)
    private fun toolsW(m: KitMeasure) = tools.sumOf { 8 + ((it as? Inline)?.inlineWidth(m) ?: 0) }

    override fun measure(m: KitMeasure, w: Int): Int {
        val inner = w - padX * 2
        val (hh, _) = header(m, inner)
        val bodyH = stack.height(m, inner)
        val gap = if (hh > 0 && bodyH > 0) S.CARD_HEAD_GAP else 0
        return padTop + hh + gap + bodyH + padBottom
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.layer()
        val x1 = x + w
        val y1 = y + h
        when (kind) {
            SHARED, MINE, LOCAL -> {
                c.rect(x, y, x1, y1, S.CARD_SOUL_BG, Corner.MEDIUM)
                c.rect(x, y + 3, x + S.SOUL_EDGE_W, y1 - 3, S.ACCENT, Corner.SQUARE)
            }
            GENERIC -> {
                c.framed(x, y, x1, y1, S.CARD_SOUL_BG, S.CARD_GENERIC_EDGE, Corner.MEDIUM)
                c.rect(x, y + 3, x + S.SOUL_EDGE_W, y1 - 3, S.ACCENT, Corner.SQUARE)
            }
            EMPTY -> c.framed(x, y, x1, y1, S.CARD_EMPTY_BG, S.CARD_EMPTY_EDGE, Corner.MEDIUM)
            LATER -> c.framed(x, y, x1, y1, 0, S.CARD_LATER_EDGE, Corner.MEDIUM)
            else -> c.rect(x, y, x1, y1, S.CARD_BG, Corner.MEDIUM)
        }
        val ix = x + padX
        val iw = w - padX * 2
        val (hh, wraps) = header(c, iw)
        var cy = y + padTop
        if (hh > 0) {
            val lineH = if (wraps) S.CARD_HEAD_H else hh
            val top = cy
            val bottom = cy + lineH
            var hx = ix
            if (title != null) {
                c.text(title, hx, top, bottom, S.TEXT, S.BODY, true)
                val tw = titleW(c)
                if (titleTip != null) c.tooltip(hx, top, hx + tw, bottom, listOf(titleTip), "card:$title")
                hx += tw
            }
            for (chip in allChips) hx += S.CHIP_GAP + chip.paint(c, hx + S.CHIP_GAP, (top + bottom) / 2, "card:$title:${chip.text}")
            val rightW = metaW(c) + (if (wraps) 0 else toolsW(c))
            if (sub != null) {
                val room = ix + iw - rightW - (hx + 7)
                if (room > 12) sub.ellipsize(c, S.CAPTION, room).draw(c, hx + 7, top, bottom, S.CAPTION, S.TEXT_DIM)
            }
            var rx = ix + iw
            if (!wraps) {
                for (t in tools.asReversed()) {
                    val tw = (t as? Inline)?.inlineWidth(c) ?: 0
                    rx -= tw
                    val th = t.height(c, tw)
                    t.paint(c, rx, top + (lineH - th) / 2, tw, th)
                    rx -= 8
                }
            }
            if (meta != null) {
                val mw = (meta as? Inline)?.inlineWidth(c) ?: 0
                rx -= mw
                val mh = meta.height(c, mw)
                meta.paint(c, rx, top + (lineH - mh) / 2, mw, mh)
            }
            if (wraps) {
                var tx = ix
                val ty = bottom + 4
                for (t in tools) {
                    val tw = (t as? Inline)?.inlineWidth(c) ?: 0
                    t.paint(c, tx, ty, tw, t.height(c, tw))
                    tx += tw + 8
                }
            }
            cy += hh
        }
        val bodyH = stack.height(c, iw)
        if (bodyH > 0) {
            if (hh > 0) cy += S.CARD_HEAD_GAP
            stack.paint(c, ix, cy, iw, bodyH)
        }
    }

    companion object {
        const val PLAIN = 0
        const val SHARED = 1
        const val MINE = 2
        const val LOCAL = 3
        const val EMPTY = 4
        const val GENERIC = 5
        const val LATER = 6
    }
}

// ------------------------------------------------------------------ stat tiles

/** One `.tile`: label, big value, an optional line under it and an optional bar; clickable when [onClick]. */
class TileNode(
    val label: String,
    val value: Rich,
    val sub: Rich? = null,
    val bar: Float? = null,
    val maxed: Boolean = false,
    val color: Int = S.TEXT,
    val locked: Boolean = false,
    val tip: String? = null,
    val onClick: (() -> Unit)? = null,
) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int =
        S.TILE_PAD_TOP + S.TILE_K_H + S.TILE_V_TOP + S.TILE_V_H +
            (if (sub != null) S.TILE_SUB_TOP + S.TILE_SUB_H else 0) +
            (if (bar != null) S.TILE_BAR_TOP + S.BAR_H else 0) + S.TILE_PAD_BOTTOM

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val hover = onClick != null && c.hovered(x, y, x + w, y + h)
        c.rect(x, y, x + w, y + h, if (hover) S.TILE_HOVER else S.CARD_BG, Corner.MEDIUM)
        val ix = x + S.TILE_PAD_X
        val iw = w - S.TILE_PAD_X * 2
        var cy = y + S.TILE_PAD_TOP
        val labelW = Rich.of(label).ellipsize(c, S.CAPTION, iw - if (locked) 12 else 0)
        val lEnd = labelW.draw(c, ix, cy, cy + S.TILE_K_H, S.CAPTION, S.TEXT_DIM)
        if (locked) c.icon("pv_lock", lEnd + 3, cy + 2, 8, S.TEXT_DIM)
        cy += S.TILE_K_H + S.TILE_V_TOP
        value.ellipsize(c, S.VALUE, iw, true).draw(c, ix, cy, cy + S.TILE_V_H, S.VALUE, if (locked) S.TEXT_FAINT else color, true)
        cy += S.TILE_V_H
        if (sub != null) {
            cy += S.TILE_SUB_TOP
            sub.ellipsize(c, S.CAPTION, iw).draw(c, ix, cy, cy + S.TILE_SUB_H, S.CAPTION, S.TEXT_DIM)
            cy += S.TILE_SUB_H
        }
        if (bar != null) c.bar(ix, cy + S.TILE_BAR_TOP, iw, bar, maxed)
        if (tip != null) c.tooltip(x, y, x + w, y + h, listOf(tip), "tile:$label")
        if (onClick != null) c.onClick(x, y, x + w, y + h, onClick)
    }
}

/** `.tiles`: auto-fit columns of at least 132 px; tiles in a row share its height. */
class TilesNode(val tiles: List<TileNode>, override val span: Int = Node.FULL) : Node() {
    private fun cols(w: Int) = KitLayout.autoFitColumns(w, S.TILE_MIN, S.GAP, tiles.size)

    override fun measure(m: KitMeasure, w: Int): Int {
        if (tiles.isEmpty()) return 0
        val cols = cols(w)
        val tracks = KitLayout.trackX(w, cols, S.GAP)
        val rows = (tiles.size + cols - 1) / cols
        var h = 0
        for (r in 0 until rows) {
            var rh = 0
            for (k in 0 until cols) {
                val i = r * cols + k
                if (i < tiles.size) rh = max(rh, tiles[i].height(m, KitLayout.spanWidth(tracks, k, 1, S.GAP)))
            }
            h += rh + if (r > 0) S.GAP else 0
        }
        return h
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        if (tiles.isEmpty()) return
        val cols = cols(w)
        val tracks = KitLayout.trackX(w, cols, S.GAP)
        var ry = y
        var i = 0
        while (i < tiles.size) {
            val end = min(tiles.size, i + cols)
            var rh = 0
            for (k in i until end) rh = max(rh, tiles[k].height(c, KitLayout.spanWidth(tracks, k - i, 1, S.GAP)))
            for (k in i until end) {
                val tw = KitLayout.spanWidth(tracks, k - i, 1, S.GAP)
                tiles[k].paint(c, x + tracks[k - i], ry, tw, rh)
            }
            ry += rh + S.GAP
            i = end
        }
    }
}

// ------------------------------------------------------------------ rows

/** One `.kv` row: dim label left, bold value right. */
class Kv(val label: Rich, val value: Rich, val color: Int = S.TEXT, val tip: String? = null)

/** `.kvs` (or `.kvs.cols`: two columns, 14 px apart). */
class KvsNode(val rows: List<Kv>, val cols: Int = 1) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = ((rows.size + cols - 1) / cols) * S.KV_H

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val colW = (w - S.KV_COLS_GAP * (cols - 1)) / cols
        val lastRow = (rows.size + cols - 1) / cols - 1
        for ((i, row) in rows.withIndex()) {
            val r = i / cols
            val k = i % cols
            val rx = x + k * (colW + S.KV_COLS_GAP)
            val ry = y + r * S.KV_H
            val vw = row.value.width(c, S.BODY, true)
            row.value.draw(c, rx + colW - vw, ry, ry + S.KV_H - 1, S.BODY, row.color, true)
            row.label.ellipsize(c, S.BODY, colW - vw - 8).draw(c, rx, ry, ry + S.KV_H - 1, S.BODY, S.TEXT_DIM)
            if (r < lastRow) c.rect(rx, ry + S.KV_H - 1, rx + colW, ry + S.KV_H, S.KV_LINE, Corner.SQUARE)
            if (row.tip != null) c.tooltip(rx, ry, rx + colW, ry + S.KV_H, listOf(row.tip), "kv:${row.label.plain}")
        }
    }
}

/** `.cl`: label (+ chip) left, a 13 px value right, a full-width bar under both. */
class LevelRowNode(
    val label: Rich,
    val value: String,
    val fraction: Float,
    val maxed: Boolean,
    val selected: Boolean = false,
    val chip: Chip? = null,
    val valueColor: Int? = null,
    val tip: String? = null,
) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = S.CL_H

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val top = y + 4
        val vw = c.width(value, S.HEADING, true)
        c.text(value, x + w - vw, top, top + 14, valueColor ?: if (maxed) S.MAXED else S.TEXT, S.HEADING, true)
        val end = label.ellipsize(c, S.BODY, w - vw - 8).draw(c, x, top, top + 14, S.BODY, if (selected) S.TEXT else S.TEXT_MID)
        chip?.paint(c, end + S.CHIP_GAP, top + 7)
        c.bar(x, top + 16, w, fraction, maxed)
        if (tip != null) c.tooltip(x, y, x + w, y + h, listOf(tip), "cl:${label.plain}")
    }
}

/** `.sl-top`: a 26 px number with a bar and a caption beside it. */
class BigStatNode(val value: String, val fraction: Float, val maxed: Boolean, val caption: Rich, val color: Int? = null) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = 28

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val vw = max(22, c.width(value, S.BIG, true))
        c.text(value, x, y, y + 28, color ?: if (maxed) S.MAXED else S.TEXT, S.BIG, true)
        val rx = x + vw + 12
        val rw = w - vw - 12
        c.bar(rx, y + 6, rw, fraction, maxed)
        caption.ellipsize(c, S.CAPTION, rw).draw(c, rx, y + 13, y + 26, S.CAPTION, S.TEXT_DIM)
    }
}

/** One `.tier` box: a small label over a bold value; [dim] greys the value. */
class Box(val label: String, val value: String, val dim: Boolean = false, val tip: String? = null)

/** `.tiers`: equal boxes in one row, 4 px apart. */
class BoxesNode(val boxes: List<Box>) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = if (boxes.isEmpty()) 0 else 31

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        if (boxes.isEmpty()) return
        val tracks = KitLayout.trackX(w, boxes.size, 4)
        for ((i, b) in boxes.withIndex()) {
            val bx = x + tracks[i]
            val bw = KitLayout.spanWidth(tracks, i, 1, 4)
            c.rect(bx, y, bx + bw, y + h, S.TIER_BG, Corner.SMALL)
            val lw = c.width(b.label, S.LABEL)
            c.text(b.label, bx + (bw - lw) / 2, y + 3, y + 13, S.TEXT_FAINT, S.LABEL)
            val vw = c.width(b.value, S.BODY, true)
            c.text(b.value, bx + (bw - vw) / 2, y + 13, y + 27, if (b.dim) S.TEXT_GHOST else S.TEXT, S.BODY, true)
            if (b.tip != null) c.tooltip(bx, y, bx + bw, y + h, listOf(b.tip), "box:${b.label}")
        }
    }
}

/** One `.ms` mini stat: name, value, thin bar; clickable. */
class Mini(val label: String, val value: String, val fraction: Float, val maxed: Boolean, val onClick: (() -> Unit)? = null)

/** `.mini2`: mini stats in two columns (3 px row gap, 14 px column gap). */
class MiniGridNode(val items: List<Mini>, val cols: Int = 2) : Node() {
    private val rowH = 22

    override fun measure(m: KitMeasure, w: Int): Int {
        val rows = (items.size + cols - 1) / cols
        return if (rows == 0) 0 else rows * rowH + (rows - 1) * 3
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val colW = (w - 14 * (cols - 1)) / cols
        for ((i, it) in items.withIndex()) {
            val rx = x + (i % cols) * (colW + 14)
            val ry = y + (i / cols) * (rowH + 3)
            val hover = it.onClick != null && c.hovered(rx, ry, rx + colW, ry + rowH)
            val vw = c.width(it.value, S.BODY, true)
            c.text(it.value, rx + colW - vw, ry + 2, ry + 16, if (it.maxed) S.MAXED else S.TEXT, S.BODY, true)
            Rich.of(it.label).ellipsize(c, S.SMALL, colW - vw - 6).draw(c, rx, ry + 2, ry + 16, S.SMALL, if (hover) S.TEXT else S.TEXT_MID)
            c.bar(rx, ry + 17, colW, it.fraction, it.maxed, h = 3)
            if (it.onClick != null) c.onClick(rx, ry, rx + colW, ry + rowH, it.onClick)
        }
    }
}

/** One `.br` bar-list row. */
class BarRow(val label: String, val value: String, val fraction: Float, val tip: String? = null)

/** `.bars` (or `.bars.two`: auto-fit columns of at least 260 px, 18 px apart). */
class BarListNode(val rows: List<BarRow>, val twoColumns: Boolean = false) : Node() {
    private fun cols(w: Int) = if (twoColumns) KitLayout.autoFitColumns(w, 260, 18, rows.size) else 1

    override fun measure(m: KitMeasure, w: Int): Int {
        val perCol = (rows.size + cols(w) - 1) / cols(w)
        return if (perCol == 0) 0 else 4 + perCol * S.BR_H + (perCol - 1) * 3
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val cols = cols(w)
        val perCol = (rows.size + cols - 1) / cols
        val colW = (w - 18 * (cols - 1)) / cols
        for ((i, r) in rows.withIndex()) {
            // Grid auto-flow fills rows first: item i sits in row i / cols, column i % cols.
            val col = i % cols
            val row = i / cols
            val rx = x + col * (colW + 18)
            val ry = y + 4 + row * (S.BR_H + 3)
            val labelW = (colW * 0.3f).toInt().coerceIn(80, 120)
            val valueW = 54
            Rich.of(r.label).ellipsize(c, S.SMALL, labelW).draw(c, rx, ry, ry + S.BR_H, S.SMALL, S.TEXT_MID)
            val bx = rx + labelW + 8
            val bw = colW - labelW - valueW - 16
            c.bar(bx, ry + (S.BR_H - S.HB_H) / 2, bw, r.fraction, false, h = S.HB_H, track = S.HB_TRACK)
            val vw = c.width(r.value, S.SMALL, true)
            c.text(r.value, rx + colW - vw, ry, ry + S.BR_H, S.TEXT, S.SMALL, true)
            if (r.tip != null) c.tooltip(rx, ry, rx + colW, ry + S.BR_H, listOf(r.tip), "br:${r.label}")
        }
    }
}

/** `.hist`: equal-width bars bottom-aligned in a 34 px band, optional axis labels under it. */
class HistogramNode(val values: List<Double>, val axisLeft: String? = null, val axisRight: String? = null, val tip: String? = null) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = S.HIST_H + if (axisLeft != null || axisRight != null) LH_CAPTION + 2 else 0

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val mx = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        if (values.isNotEmpty()) {
            val tracks = KitLayout.trackX(w, values.size, 2)
            for ((i, v) in values.withIndex()) {
                val bh = max(1, (S.HIST_H * v / mx).toInt())
                val bx = x + tracks[i]
                c.rect(bx, y + S.HIST_H - bh, bx + KitLayout.spanWidth(tracks, i, 1, 2), y + S.HIST_H, 0xCC3B82F6.toInt(), Corner.SQUARE)
            }
        }
        if (axisLeft != null) c.text(axisLeft, x, y + S.HIST_H + 1, y + S.HIST_H + 1 + LH_CAPTION, S.TEXT_FAINT, S.LABEL)
        if (axisRight != null) c.text(axisRight, x + w - c.width(axisRight, S.LABEL), y + S.HIST_H + 1, y + S.HIST_H + 1 + LH_CAPTION, S.TEXT_FAINT, S.LABEL)
        if (tip != null) c.tooltip(x, y, x + w, y + S.HIST_H, listOf(tip), "hist:$tip")
    }
}

/** A plain progress bar on its own line. */
class BarNode(val fraction: Float, val maxed: Boolean = false, val h: Int = S.BAR_H) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = h
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) = c.bar(x, y, w, fraction, maxed, this.h)
}

// ------------------------------------------------------------------ notices

/** `.empty`: a centered icon, title and line; [maxW] narrows the text (`.soulpage .empty`). */
class EmptyNode(val icon: String, val title: String, val text: String, val maxW: Int = Int.MAX_VALUE) : Node() {
    private fun textW(w: Int) = min(w - 16, maxW)

    override fun measure(m: KitMeasure, w: Int): Int {
        val title = Rich.of(title).wrap(m, S.BODY, textW(w), true).size * LH_BODY
        val body = if (text.isEmpty()) 0 else 4 + Rich.of(text).wrap(m, S.SMALL, textW(w)).size * LH_SMALL
        return S.EMPTY_PAD_Y + S.EMPTY_ICON + 2 + 4 + title + body + S.EMPTY_PAD_Y
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var cy = y + S.EMPTY_PAD_Y
        c.icon(icon, x + (w - S.EMPTY_ICON) / 2, cy, S.EMPTY_ICON, S.TEXT_FAINT)
        cy += S.EMPTY_ICON + 2 + 4
        val tw = textW(w)
        val tx = x + (w - tw) / 2
        for (line in Rich.of(title).wrap(c, S.BODY, tw, true)) {
            val lw = line.width(c, S.BODY, true)
            line.draw(c, tx + (tw - lw) / 2, cy, cy + LH_BODY, S.BODY, S.TEXT_SOFT, true)
            cy += LH_BODY
        }
        if (text.isEmpty()) return
        cy += 4
        for (line in Rich.of(text).wrap(c, S.SMALL, tw)) {
            val lw = line.width(c, S.SMALL)
            line.draw(c, tx + (tw - lw) / 2, cy, cy + LH_SMALL, S.SMALL, S.TEXT_DIM)
            cy += LH_SMALL
        }
    }
}

/** `.apioff`: a lock, "<Name>'s <toggle> is off" and where to turn it on. */
class ApiOffNode(val title: String, val text: String) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int =
        9 + LH_BODY + Rich.of(text).wrap(m, S.SMALL, w - 24 - 26).size * LH_SMALL + 9

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.framed(x, y, x + w, y + h, S.APIOFF_BG, S.APIOFF_EDGE, Corner.MEDIUM)
        c.icon("pv_lock", x + 12, y + (h - 14) / 2, 14, S.TEXT_DIM)
        val tx = x + 12 + 14 + 10
        val tw = w - (tx - x) - 12
        var cy = y + 9
        c.text(title, tx, cy, cy + LH_BODY, S.TEXT, S.BODY, true)
        cy += LH_BODY
        for (line in Rich.of(text).wrap(c, S.SMALL, tw)) {
            line.draw(c, tx, cy, cy + LH_SMALL, S.SMALL, S.TEXT_DIM)
            cy += LH_SMALL
        }
    }
}

/** `.soulnote`: a Soul-tinted strip with a SOUL chip (or none) and one line of text. */
class NoteNode(val text: Rich, val chip: Chip? = Chip.soul()) : Node() {
    private fun textX(m: KitMeasure) = 9 + (chip?.width(m)?.plus(6) ?: 0)

    override fun measure(m: KitMeasure, w: Int): Int = 10 + text.wrap(m, S.SMALL, w - textX(m) - 9).size * LH_SMALL

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.rect(x, y, x + w, y + h, S.CARD_SOUL_BG, Corner.SMALL)
        c.rect(x, y + 2, x + 2, y + h - 2, S.ACCENT, Corner.SQUARE)
        chip?.paint(c, x + 9, y + 5 + LH_SMALL / 2)
        var cy = y + 5
        for (line in text.wrap(c, S.SMALL, w - textX(c) - 9)) {
            line.draw(c, x + textX(c), cy, cy + LH_SMALL, S.SMALL, S.NOTE_TEXT)
            cy += LH_SMALL
        }
    }
}

// ------------------------------------------------------------------ controls

/** `.seg`: a segmented control bound to [ViewState] key [key]. */
class SegNode(val key: String, val value: String, val options: List<Pair<String, String>>) : Node(), Inline {
    private fun btnW(m: KitMeasure, label: String) = m.width(label, S.CAPTION) + S.SEG_BTN_PAD_X * 2

    override fun inlineWidth(m: KitMeasure): Int =
        S.SEG_PAD * 2 + options.sumOf { btnW(m, it.second) } + (options.size - 1)

    override fun measure(m: KitMeasure, w: Int): Int = S.SEG_H

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val sw = inlineWidth(c)
        c.framed(x, y, x + sw, y + S.SEG_H, S.SEG_BG, S.SEG_EDGE, Corner.MEDIUM)
        var bx = x + S.SEG_PAD
        for ((v, label) in options) {
            val bw = btnW(c, label)
            val on = v == value
            val hover = c.hovered(bx, y + S.SEG_PAD, bx + bw, y + S.SEG_H - S.SEG_PAD)
            if (on) c.rect(bx, y + S.SEG_PAD, bx + bw, y + S.SEG_H - S.SEG_PAD, S.ACCENT, Corner.SMALL)
            c.text(label, bx + S.SEG_BTN_PAD_X, y, y + S.SEG_H, if (on) 0xFFFFFFFF.toInt() else if (hover) S.TEXT else S.TEXT_DIM, S.CAPTION)
            if (!on) c.onClick(bx, y, bx + bw, y + S.SEG_H) { c.state.set(key, v) }
            bx += bw + 1
        }
    }
}

/** `.fld.sm`: a search field bound to [ViewState.field]([key]); [count] shows matches at the right. */
class SearchFieldNode(val key: String, val placeholder: String, val w: Int = S.FIELD_W, val count: String? = null) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = w
    override fun measure(m: KitMeasure, w: Int): Int = S.FIELD_H

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val cw = if (count != null) c.width(count, S.CAPTION) + 5 else 0
        c.field(key, x, y, x + this.w, y + S.FIELD_H, placeholder, cw)
        if (count != null) c.text(count, x + this.w - cw, y, y + S.FIELD_H, S.ACCENT_LIGHT, S.CAPTION)
    }
}

/** `.chk`: a switch with a label, bound to a click. */
class ToggleNode(val label: String, val on: Boolean, val big: Boolean = false, val onClick: () -> Unit) : Node(), Inline {
    private val sw get() = if (big) 36 else 26
    private val sh get() = if (big) 18 else 14
    private val size get() = if (big) S.BODY else S.SMALL

    override fun inlineWidth(m: KitMeasure): Int = sw + 6 + m.width(label, size)
    override fun measure(m: KitMeasure, w: Int): Int = sh

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val iw = inlineWidth(c)
        c.rect(x, y, x + sw, y + sh, if (on) S.ACCENT else 0xFF3A3A3A.toInt(), Corner.PILL)
        val knob = sh - 4
        val kx = if (on) x + sw - 2 - knob else x + 2
        c.rect(kx, y + 2, kx + knob, y + 2 + knob, 0xFFFFFFFF.toInt(), Corner.PILL)
        c.text(label, x + sw + 6, y, y + sh, if (big) S.TEXT else S.TEXT_MID, size)
        c.onClick(x, y, x + iw, y + sh, onClick)
    }
}
