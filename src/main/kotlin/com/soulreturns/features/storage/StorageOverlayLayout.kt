package com.soulreturns.features.storage

import kotlin.math.max
import kotlin.math.min

/** Geometry from mocks/storage-overlay/mock.css and mock.js, in Soul pixels. */
internal object StorageOverlayLayout {
    const val PLAYER_SLOT = 36
    const val MIN_SLOT = 20
    const val MAX_SLOT = 64
    const val GAP = 10
    const val MIN_CARD = 206

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right get() = x + width
        val bottom get() = y + height
        fun contains(px: Double, py: Double) = px >= x && px < right && py >= y && py < bottom
        fun intersects(other: Rect) = width > 0 && height > 0 && other.width > 0 && other.height > 0 && x < other.right && right > other.x && y < other.bottom && bottom > other.y
    }

    data class Frame(
        val panel: Rect,
        val header: Rect,
        val search: Rect,
        val filtersButton: Rect,
        val closeButton: Rect,
        val filters: Rect,
        val wall: Rect,
        val dock: Rect,
        val playerGrid: Rect,
        val footer: Rect,
        val columns: Int,
        val cardWidth: Int,
        val slotSize: Int,
        val wallWidth: Int,
    ) {
        private val playerCells = Array(36) { index ->
            Rect(playerGrid.x + index % 9 * (PLAYER_SLOT + 2),
                playerGrid.y + index / 9 * (PLAYER_SLOT + 2) + if (index >= 27) 3 else 0,
                PLAYER_SLOT, PLAYER_SLOT)
        }
        fun playerCell(index: Int): Rect = playerCells[index]
    }

    data class Card(val bounds: Rect, val grid: Rect, val slotSize: Int, val rows: Int) {
        private val cells = Array(rows * 9) { index ->
            Rect(grid.x + index % 9 * (slotSize + 1), grid.y + index / 9 * (slotSize + 1), slotSize, slotSize)
        }
        fun cell(index: Int): Rect = cells[index]
    }

    fun frame(width: Int, height: Int, columns: Int, filters: Boolean, filterButtonWidth: Int): Frame {
        val pw = min(1360, max(1, width - 40))
        val ph = max(1, height - 40)
        val panel = Rect((width - pw) / 2, (height - ph) / 2, pw, ph)
        val innerWidth = max(1, pw - 38)
        val titleWidth = 174
        val buttonWidth = max(64, filterButtonWidth)
        val searchWidth = min(400, max(160, innerWidth - titleWidth - buttonWidth - 18 - 42))
        val wrapped = titleWidth + searchWidth + buttonWidth + 18 + 42 > innerWidth
        val headerHeight = if (wrapped) 112 else 74
        val header = Rect(panel.x + 1, panel.y + 1, pw - 2, headerHeight)
        val close = Rect(panel.right - 37, header.y + if (wrapped) 76 else 28, 18, 18)
        val filterButton = Rect(close.x - 14 - buttonWidth, close.y, buttonWidth, 18)
        val search = Rect(
            if (wrapped) panel.x + 19 else filterButton.x - 14 - searchWidth,
            header.y + if (wrapped) 66 else 19,
            if (wrapped) max(1, min(searchWidth, filterButton.x - panel.x - 33)) else searchWidth,
            36,
        )
        val nav = Rect(panel.x + 1, header.bottom + 1, pw - 2, if (filters) 34 else 0)
        val footer = Rect(panel.x + 1, panel.bottom - 35, pw - 2, 34)
        val dock = Rect(panel.x + 1, footer.y - 194, pw - 2, 194)
        val wall = Rect(panel.x + 1, nav.bottom, pw - 2, max(0, dock.y - nav.bottom))
        val cols = columns.coerceIn(1, 6)
        val available = max(1, wall.width - 32)
        val cardWidth = max(MIN_CARD, (available - (cols - 1) * GAP) / cols)
        val slot = ((available - (cols - 1) * GAP) / cols - 18 - 8).div(9).coerceIn(MIN_SLOT, MAX_SLOT)
        val gridWidth = PLAYER_SLOT * 9 + 2 * 8
        return Frame(panel, header, search, filterButton, close, nav, wall, dock,
            Rect(panel.x + (pw - gridWidth) / 2, dock.y + 32, gridWidth, 4 * PLAYER_SLOT + 6 + 3),
            footer, cols, cardWidth, slot, max(available, cols * cardWidth + (cols - 1) * GAP))
    }

    fun cardHeight(rows: Int, seen: Boolean, slot: Int): Int {
        val gridHeight = if (seen) rows * slot + max(0, rows - 1) else 5 * slot + 4
        return max(5 * slot + 64, 18 + 26 + gridHeight + 20)
    }

    fun card(frame: Frame, column: Int, top: Int, rows: Int, seen: Boolean, scrollX: Int, scrollY: Int): Card {
        val x = frame.wall.x + 16 + column * (frame.cardWidth + GAP) - scrollX
        val y = frame.wall.y + 10 + top - scrollY
        val bounds = Rect(x, y, frame.cardWidth, cardHeight(rows, seen, frame.slotSize))
        val gridWidth = frame.slotSize * 9 + 8
        return Card(bounds, Rect(x + (frame.cardWidth - gridWidth) / 2, y + 35, gridWidth, rows * frame.slotSize + max(0, rows - 1)), frame.slotSize, rows)
    }
}
