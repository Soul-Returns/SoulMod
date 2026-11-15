package com.soulreturns.features.search

import com.soulreturns.features.islandchests.IslandChestLayout
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Layout of `mocks/search-everywhere/` in Soul px, ported 1:1 from the mock's CSS (measured in the
 * mock at 900×610). The panel is the mock's `.se` border box: a 1 px edge around 77 header, 35 scope,
 * the body and the 28 footer; columns split 1.03 : 1.
 */
internal object SearchEverywhereLayout {
    const val HEADER = 77
    const val SCOPE = 35
    const val FOOT = 28
    const val COMPACT_SEARCH = 300
    const val COMPACT_DETAIL = 330

    // Search column (`.col-search`: padding 16 14 0 20).
    const val FIELD_H = 35
    const val CHIP_H = 24
    const val CHIP_GAP = 6
    /** `.list-h`: 12 above, a 16 px line, 7 below. */
    const val LIST_HEADER = 35
    /** `.list-foot` 32 high plus its 7 px margin. */
    const val LIST_FOOT = 32
    const val ROW_H = 54
    const val ROW_PITCH = 57

    // Details column (`.col-detail`: padding 18 20).
    const val PAD_X = 20
    const val PAD_Y = 18
    /** `.d-head` 48 + `.summary` 71 + its 11 margin: the places list starts 148 below the column. */
    const val PLACES_TOP = 148
    const val SECTION_H = 36
    const val SECTION_GAP = 8
    /** `.src-h:not(:first-child)` margin-top. */
    const val SECTION_MARGIN = 4
    const val CARD_H = 58
    const val NOTE_LINE = 15
    const val SRC_ROW_H = 60
    const val SRC_ROW_GAP = 8

    data class Rect(val x: Int, val y: Int, val right: Int, val bottom: Int) {
        val width get() = right - x
        val height get() = bottom - y
        fun contains(px: Int, py: Int) = px >= x && px < right && py >= y && py < bottom
    }

    data class Frame(
        val outer: Rect,
        val header: Rect,
        val scope: Rect,
        val body: Rect,
        val search: Rect,
        val detail: Rect,
        val foot: Rect,
        val compact: Boolean,
    )

    /** Column rects; in compact mode they stack ([search] above [detail]) and [body] scrolls. */
    fun frame(width: Float, height: Float, detailHeight: Int = COMPACT_DETAIL, scroll: Int = 0): Frame {
        val p = IslandChestLayout.panel(width, height)
        val x = p.x.roundToInt()
        val y = p.y.roundToInt()
        val right = (p.x + p.width).roundToInt()
        val bottom = (p.y + p.height).roundToInt()
        val outer = Rect(x, y, right, bottom)
        val header = Rect(x + 1, y + 1, right - 1, y + 1 + HEADER)
        val scope = Rect(x + 1, header.bottom, right - 1, header.bottom + SCOPE)
        val foot = Rect(x + 1, bottom - 1 - FOOT, right - 1, bottom - 1)
        val body = Rect(x + 1, scope.bottom, right - 1, foot.y)
        if (p.compact) {
            val top = body.y - scroll
            val search = Rect(body.x, top, body.right, top + COMPACT_SEARCH)
            return Frame(outer, header, scope, body, search, Rect(body.x, search.bottom, body.right, search.bottom + max(COMPACT_DETAIL, detailHeight)), foot, true)
        }
        val split = body.x + (body.width * 1.03f / 2.03f).roundToInt()
        // `.col-search` ends in its 1 px border-right at split - 1.
        return Frame(outer, header, scope, body, Rect(body.x, body.y, split - 1, body.bottom), Rect(split, body.y, body.right, body.bottom), foot, false)
    }

    // ---------------------------------------------------------------- search column

    fun field(column: Rect) = Rect(column.x + 20, column.y + 16, column.right - 14, column.y + 16 + FIELD_H)

    /** Chip lefts/tops for [widths], wrapping like `.sources` (flex-wrap, gap 6). Returns rects. */
    fun chips(column: Rect, widths: List<Int>): List<Rect> {
        val field = field(column)
        var cx = field.x
        var cy = field.bottom + 10
        return widths.map { w ->
            if (cx > field.x && cx + w > field.right) { cx = field.x; cy += CHIP_H + CHIP_GAP }
            Rect(cx, cy, cx + w, cy + CHIP_H).also { cx += w + CHIP_GAP }
        }
    }

    /** `.chip`: 10 padding + 1 border each side, dot 7 + gap 6, label, gap 6, count. */
    fun chipWidth(label: Int, count: Int, dot: Boolean) = 22 + (if (dot) 13 else 0) + label + 6 + count

    fun list(column: Rect, chipsBottom: Int): Rect {
        val field = field(column)
        return Rect(field.x, chipsBottom + LIST_HEADER, field.right, max(chipsBottom + LIST_HEADER, column.bottom - LIST_FOOT - 7))
    }

    fun listFoot(column: Rect): Rect { val field = field(column); return Rect(field.x, column.bottom - LIST_FOOT, field.right, column.bottom) }

    /** `.row` inside the list (4 px reserved for the scrollbar). */
    fun row(list: Rect, index: Int, scroll: Int) = Rect(list.x, list.y + index * ROW_PITCH - scroll, list.right - 4, list.y + index * ROW_PITCH - scroll + ROW_H)

    // ---------------------------------------------------------------- details column

    fun inner(column: Rect) = Rect(column.x + PAD_X, column.y + PAD_Y, column.right - PAD_X, column.bottom - PAD_Y)

    /** The scrolling `.places` area above [noteLines] lines of `.note` (10 px margin). */
    fun places(column: Rect, noteLines: Int): Rect {
        val inner = inner(column)
        return Rect(inner.x, column.y + PLACES_TOP, inner.right, max(column.y + PLACES_TOP + 1, inner.bottom - noteLines * NOTE_LINE - 10))
    }

    /** One source section's vertical extent, relative to the places top (mock `.places` flow). */
    data class SectionBox(val top: Int, val noteTop: Int, val cardsTop: Int, val bottom: Int)

    fun sections(noteLines: List<Int>, cards: List<Int>): List<SectionBox> {
        var y = 0
        return noteLines.indices.map { i ->
            if (i > 0) y += SECTION_GAP + SECTION_MARGIN
            val top = y
            y += SECTION_H
            // `.src-note`: margin -3 0 2, so it hugs the header.
            val noteTop = y + SECTION_GAP - 3
            if (noteLines[i] > 0) y = noteTop + noteLines[i] * NOTE_LINE + 2
            val cardsTop = y + SECTION_GAP
            if (cards[i] > 0) y = cardsTop + cards[i] * (CARD_H + SECTION_GAP) - SECTION_GAP
            SectionBox(top, noteTop, cardsTop, y)
        }
    }
}
