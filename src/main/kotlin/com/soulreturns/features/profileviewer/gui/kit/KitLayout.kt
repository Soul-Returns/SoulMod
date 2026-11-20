package com.soulreturns.features.profileviewer.gui.kit

import kotlin.math.max
import kotlin.math.min

/**
 * The width-driven layout rules of `mocks/profile-viewer/mock.css`, as pure integer math (unit-tested
 * in `KitLayoutTest` with values read off the mock). No drawing here.
 */
object KitLayout {
    /** `repeat(auto-fill, minmax(min(<min>, 100%), 1fr))`: as many columns of at least [min] as fit. */
    fun autoFillColumns(width: Int, min: Int, gap: Int): Int = max(1, (width + gap) / (min + gap))

    /** `repeat(auto-fit, …)` over [count] items: empty tracks collapse, so never more columns than items. */
    fun autoFitColumns(width: Int, min: Int, gap: Int, count: Int): Int =
        max(1, min(max(1, count), autoFillColumns(width, min, gap)))

    /** CSS `columns: <min> <max>` (column-width + column-count). */
    fun masonryColumns(width: Int, min: Int, gap: Int, maxColumns: Int): Int =
        min(maxColumns, autoFillColumns(width, min, gap))

    /**
     * Left offsets of [cols] equal `1fr` tracks across [width] with [gap] between them. Integer widths:
     * the leftover pixels go to the first tracks, one each, so the row always fills exactly.
     */
    fun trackX(width: Int, cols: Int, gap: Int): IntArray {
        val inner = width - gap * (cols - 1)
        val base = inner / cols
        val extra = inner - base * cols
        val out = IntArray(cols + 1)
        var x = 0
        for (i in 0 until cols) {
            out[i] = x
            x += base + (if (i < extra) 1 else 0) + gap
        }
        out[cols] = width + gap
        return out
    }

    /** Width of the cell from track [col] spanning [span] tracks (gaps inside the span included). */
    fun spanWidth(tracks: IntArray, col: Int, span: Int, gap: Int): Int = tracks[col + span] - gap - tracks[col]

    /** Where one grid item went: its row, first column and the columns it spans. */
    data class Cell(val row: Int, val col: Int, val span: Int)

    /**
     * CSS grid auto-placement (sparse, row by row) of items with column [spans] into [cols] tracks: an
     * item that no longer fits in the current row starts the next; holes are never back-filled.
     * [Node.FULL] or any span above [cols] takes the whole row.
     */
    fun place(spans: List<Int>, cols: Int): List<Cell> {
        val out = ArrayList<Cell>(spans.size)
        var row = 0
        var col = 0
        for (raw in spans) {
            val span = raw.coerceIn(1, cols)
            if (col + span > cols) {
                row++
                col = 0
            }
            out += Cell(row, col, span)
            col += span
            if (col >= cols) {
                row++
                col = 0
            }
        }
        return out
    }

    /** Shortest-column masonry: each item, in order, goes to the column that is currently shortest (leftmost on a tie). */
    fun masonry(heights: List<Int>, cols: Int, gap: Int): List<Int> {
        val bottoms = IntArray(cols)
        val out = ArrayList<Int>(heights.size)
        for (h in heights) {
            var best = 0
            for (c in 1 until cols) if (bottoms[c] < bottoms[best]) best = c
            out += best
            bottoms[best] += h + gap
        }
        return out
    }

    /**
     * `.cols3`-style flex wrap: equal items with `flex: 1 1 <basis>`, [gap] between them. Returns the
     * item counts per line (each line's items share its width equally).
     */
    fun flexLines(count: Int, width: Int, basis: Int, gap: Int): List<Int> {
        if (count <= 0) return emptyList()
        val perLine = max(1, (width + gap) / (basis + gap))
        val out = ArrayList<Int>()
        var left = count
        while (left > 0) {
            val n = min(perLine, left)
            out += n
            left -= n
        }
        return out
    }

    /**
     * Auto table layout: every column gets its content width; spare width is shared out in proportion
     * to those widths (CSS `table-layout: auto` at `width: 100%`); fixed columns keep their width.
     * When the content is wider than [width], flexible columns shrink proportionally (cells ellipsize).
     */
    fun tableWidths(content: IntArray, fixed: BooleanArray, width: Int): IntArray {
        val out = content.copyOf()
        val fixedSum = content.indices.filter { fixed[it] }.sumOf { content[it] }
        val flexSum = content.indices.filter { !fixed[it] }.sumOf { content[it] }
        if (flexSum <= 0) return out
        val room = width - fixedSum
        var given = 0
        val flexIdx = content.indices.filter { !fixed[it] }
        for ((n, i) in flexIdx.withIndex()) {
            val w = if (n == flexIdx.lastIndex) room - given else (content[i].toLong() * room / flexSum).toInt()
            out[i] = max(0, w)
            given += out[i]
        }
        return out
    }
}
