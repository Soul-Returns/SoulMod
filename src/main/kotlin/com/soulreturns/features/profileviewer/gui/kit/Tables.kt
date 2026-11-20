package com.soulreturns.features.profileviewer.gui.kit

import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import kotlin.math.max

/**
 * A table column. [width] > 0 fixes it; [grow] columns share what the fixed and content-sized ones
 * leave (clamped to [min]..[max]); any other column is as wide as its widest cell (`.tbl` auto layout).
 * [align] -1 left, 1 right.
 */
class Col(
    val header: String? = null,
    val align: Int = -1,
    val width: Int = 0,
    val grow: Boolean = false,
    val min: Int = 0,
    val max: Int = Int.MAX_VALUE,
    val tip: String? = null,
)

/**
 * One table cell: text (with an optional smaller [suffix], `<b>32</b><small>/ 50</small>`), a bar, or
 * chips. [color] null = the row's color (dim rows grey out).
 */
class Cell(
    val text: Rich? = null,
    val bold: Boolean = false,
    val size: Float = S.SMALL,
    val color: Int? = null,
    val suffix: Rich? = null,
    val suffixSize: Float = S.CAPTION,
    val suffixColor: Int = S.TEXT_FAINT,
    val bar: Float? = null,
    val barMaxed: Boolean = false,
    val chips: List<Chip> = emptyList(),
    val tip: String? = null,
) {
    fun width(m: KitMeasure): Int {
        var w = text?.width(m, size, bold) ?: 0
        if (suffix != null) w += 3 + suffix.width(m, suffixSize)
        if (chips.isNotEmpty()) w += chips.sumOf { it.width(m) } + S.CHIP_GAP * (chips.size - 1)
        if (bar != null) w = max(w, 40)
        return w
    }

    companion object {
        fun of(text: String, bold: Boolean = false, color: Int? = null, tip: String? = null) = Cell(Rich.of(text), bold, color = color, tip = tip)
        val DASH = Cell(Rich.of("—"))
    }
}

class TableRow(val cells: List<Cell>, val dim: Boolean = false, val tip: String? = null)

/**
 * `.tbl` (and the header-less `.sk` rows: pass [header] false, fixed/grow columns and [rowH] 26).
 */
class TableNode(
    val cols: List<Col>,
    val rows: List<TableRow>,
    val header: Boolean = cols.any { it.header != null },
    val rowH: Int = S.TD_H,
    val gap: Int = 0,
    val padRight: Int = S.TD_PAD_R,
    val separators: Boolean = true,
) : Node() {
    private var widthsFor = -1
    private var widths = IntArray(0)

    private fun widths(m: KitMeasure, w: Int): IntArray {
        if (widthsFor == w) return widths
        val n = cols.size
        val content = IntArray(n)
        val fixed = BooleanArray(n)
        for ((i, col) in cols.withIndex()) {
            if (col.width > 0) {
                content[i] = col.width
                fixed[i] = true
                continue
            }
            var cw = if (col.header != null) m.trackedWidth(col.header.uppercase(), S.LABEL, S.TH_TRACKING) else 0
            for (r in rows) r.cells.getOrNull(i)?.let { cw = max(cw, it.width(m)) }
            content[i] = cw + padRight
            fixed[i] = col.grow
        }
        val inner = w - gap * (n - 1)
        val out: IntArray
        if (cols.any { it.grow }) {
            out = content.copyOf()
            val growIdx = cols.indices.filter { cols[it].grow }
            for (i in growIdx) out[i] = 0
            val left = inner - out.sum()
            for (i in growIdx) out[i] = (left / growIdx.size).coerceIn(cols[i].min, cols[i].max)
        } else {
            out = KitLayout.tableWidths(content, fixed, inner)
        }
        widths = out
        widthsFor = w
        return out
    }

    override fun measure(m: KitMeasure, w: Int): Int = (if (header) S.TH_H else 0) + rows.size * rowH

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val ws = widths(c, w)
        var cy = y
        if (header) {
            var cx = x
            for ((i, col) in cols.withIndex()) {
                val label = col.header?.uppercase()
                if (label != null) {
                    val lw = c.trackedWidth(label, S.LABEL, S.TH_TRACKING)
                    val lx = if (col.align > 0) cx + ws[i] - padRight - lw else cx
                    c.tracked(label, lx + lw / 2, cy + 7, S.TEXT_FAINT, S.LABEL, S.TH_TRACKING)
                    if (col.tip != null) c.tooltip(lx, cy, lx + lw, cy + S.TH_H, listOf(col.tip), "th:${col.header}")
                }
                cx += ws[i] + gap
            }
            c.rect(x, cy + S.TH_H - 1, x + w, cy + S.TH_H, S.TH_LINE, Corner.SQUARE)
            cy += S.TH_H
        }
        for ((ri, row) in rows.withIndex()) {
            if (cy + rowH >= c.viewTop && cy <= c.viewBottom) paintRow(c, row, x, cy, ws)
            if (separators && ri < rows.lastIndex) c.rect(x, cy + rowH - 1, x + w, cy + rowH, S.TD_LINE, Corner.SQUARE)
            if (row.tip != null) c.tooltip(x, cy, x + w, cy + rowH, listOf(row.tip), "tr:$ri:${row.tip.hashCode()}")
            cy += rowH
        }
    }

    private fun paintRow(c: KitCanvas, row: TableRow, x: Int, y: Int, ws: IntArray) {
        var cx = x
        val bottom = y + rowH - 1
        for ((i, col) in cols.withIndex()) {
            val cell = row.cells.getOrNull(i)
            val cw = ws[i] - padRight
            if (cell != null && cw > 0) {
                val rowColor = if (row.dim) S.TEXT_GHOST else S.TEXT
                if (cell.bar != null) {
                    c.bar(cx, y + (rowH - S.BAR_H) / 2, cw, cell.bar, cell.barMaxed)
                } else if (cell.chips.isNotEmpty()) {
                    var chx = cx
                    for (chip in cell.chips) {
                        val chw = chip.width(c)
                        if (chx + chw > cx + cw) break
                        chip.paint(c, chx, (y + bottom) / 2)
                        chx += chw + S.CHIP_GAP
                    }
                } else if (cell.text != null) {
                    val suffixW = if (cell.suffix != null) 3 + cell.suffix.width(c, cell.suffixSize) else 0
                    val text = cell.text.ellipsize(c, cell.size, cw - suffixW, cell.bold)
                    val tw = text.width(c, cell.size, cell.bold) + suffixW
                    val tx = if (col.align > 0) cx + cw - tw else cx
                    val end = text.draw(c, tx, y, bottom, cell.size, if (row.dim) S.TEXT_GHOST else cell.color ?: rowColor, cell.bold)
                    cell.suffix?.draw(c, end + 3, y, bottom, cell.suffixSize, if (row.dim) S.TEXT_GHOST else cell.suffixColor)
                }
                if (cell.tip != null) c.tooltip(cx, y, cx + ws[i], y + rowH, listOf(cell.tip), "td:${cell.tip.hashCode()}")
            }
            cx += ws[i] + gap
        }
    }
}
