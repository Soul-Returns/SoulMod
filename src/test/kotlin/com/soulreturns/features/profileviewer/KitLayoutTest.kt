package com.soulreturns.features.profileviewer

import com.soulreturns.features.profileviewer.gui.kit.KitLayout
import com.soulreturns.features.profileviewer.gui.kit.KitLayout.Cell
import com.soulreturns.features.profileviewer.gui.kit.Node
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The card toolkit's layout math against the mock's CSS (`mocks/profile-viewer/mock.css`): the
 * expected values are what a browser lays out for the same widths (read off the mock at the body
 * widths of a 1280x720 Soul-px card: 1177 card - 176 rail - 32 padding = 969).
 */
class KitLayoutTest {
    private val gap = 8

    @Test
    fun `auto-fill columns match repeat(auto-fill, minmax(min, 1fr))`() {
        // .grid --min:300 at the 1280x720 body width: three columns.
        assertEquals(3, KitLayout.autoFillColumns(969, 300, gap))
        // --min:250 (Slayers): three as well, (969 + 8) / 258 = 3.78.
        assertEquals(3, KitLayout.autoFillColumns(969, 250, gap))
        // Narrow preset body (552 card - 44 mini rail - 32) = 476: one column of 300.
        assertEquals(1, KitLayout.autoFillColumns(476, 300, gap))
        // Never zero, even narrower than one track.
        assertEquals(1, KitLayout.autoFillColumns(100, 300, gap))
        // Exactly two tracks and a gap.
        assertEquals(2, KitLayout.autoFillColumns(608, 300, gap))
        assertEquals(1, KitLayout.autoFillColumns(607, 300, gap))
    }

    @Test
    fun `tiles auto-fit never makes more columns than tiles`() {
        // Five Overview tiles in the 1280x720 right column (969 - 118 figure - 8) = 843: all five on one row.
        assertEquals(5, KitLayout.autoFitColumns(843, 132, gap, 5))
        // Three Skills tiles at 969: three columns, empty tracks collapse (auto-fit).
        assertEquals(3, KitLayout.autoFitColumns(969, 132, gap, 3))
        // Narrow preset (476 - 126) = 350: two columns of 132.
        assertEquals(2, KitLayout.autoFitColumns(350, 132, gap, 5))
    }

    @Test
    fun `masonry caps at three columns`() {
        assertEquals(3, KitLayout.masonryColumns(969, 240, gap, 3))
        assertEquals(3, KitLayout.masonryColumns(2000, 240, gap, 3))
        assertEquals(1, KitLayout.masonryColumns(400, 240, gap, 3))
    }

    @Test
    fun `tracks fill the width exactly`() {
        val t = KitLayout.trackX(969, 3, gap)
        // (969 - 16) / 3 = 317.67: tracks 318, 318, 317 (extra pixels first).
        assertContentEquals(intArrayOf(0, 326, 652, 977), t)
        assertEquals(318, KitLayout.spanWidth(t, 0, 1, gap))
        assertEquals(317, KitLayout.spanWidth(t, 2, 1, gap))
        // A span of two includes the gap between: 318 + 8 + 318.
        assertEquals(644, KitLayout.spanWidth(t, 0, 2, gap))
        // The full row is the width.
        assertEquals(969, KitLayout.spanWidth(t, 0, 3, gap))
    }

    @Test
    fun `sparse auto-placement wraps a span that does not fit`() {
        // Dungeons: Classes (1) + Floors (span 2) in three columns: one row.
        assertEquals(listOf(Cell(0, 0, 1), Cell(0, 1, 2)), KitLayout.place(listOf(1, 2), 3))
        // Two columns: Floors no longer fits beside Classes, goes to the next row (no back-fill).
        assertEquals(listOf(Cell(0, 0, 1), Cell(1, 0, 2)), KitLayout.place(listOf(1, 2), 2))
        // Mining (mock miningSec): hotm 2, glacite 1, mineshaft 2, forge 1 in three columns.
        assertEquals(listOf(Cell(0, 0, 2), Cell(0, 2, 1), Cell(1, 0, 2), Cell(1, 2, 1)), KitLayout.place(listOf(2, 1, 2, 1), 3))
        // `full` takes the whole row whatever the column count; a span is clamped to the columns.
        assertEquals(listOf(Cell(0, 0, 1), Cell(1, 0, 3), Cell(2, 0, 1)), KitLayout.place(listOf(1, Node.FULL, 1), 3))
        assertEquals(listOf(Cell(0, 0, 1), Cell(1, 0, 1)), KitLayout.place(listOf(2, 2), 1))
    }

    @Test
    fun `masonry puts each item in the shortest column`() {
        // Heights 100, 40, 40, 40 in two columns: col 0 = 100; col 1 = 40, 40, 40 (bottoms 48, 96 < 108).
        assertEquals(listOf(0, 1, 1, 1), KitLayout.masonry(listOf(100, 40, 40, 40), 2, gap))
        // A tie goes left.
        assertEquals(listOf(0, 1, 0), KitLayout.masonry(listOf(10, 10, 10), 2, gap))
    }

    @Test
    fun `cols3 wraps below 250 px per column`() {
        // Overview at 969: three columns in one line.
        assertEquals(listOf(3), KitLayout.flexLines(3, 969, 250, gap))
        // 600: two, then one on its own line (which takes the full width).
        assertEquals(listOf(2, 1), KitLayout.flexLines(3, 600, 250, gap))
        assertEquals(listOf(1, 1, 1), KitLayout.flexLines(3, 476, 250, gap))
    }

    @Test
    fun `auto table widths share the spare room by content`() {
        val w = KitLayout.tableWidths(intArrayOf(40, 60, 100), booleanArrayOf(false, false, false), 400)
        assertContentEquals(intArrayOf(80, 120, 200), w)
        // A fixed column keeps its width.
        val f = KitLayout.tableWidths(intArrayOf(96, 50, 50), booleanArrayOf(true, false, false), 296)
        assertContentEquals(intArrayOf(96, 100, 100), f)
    }
}
