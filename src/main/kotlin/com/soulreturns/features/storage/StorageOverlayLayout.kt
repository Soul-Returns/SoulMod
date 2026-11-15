package com.soulreturns.features.storage

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Chrome uses Soul pixels; slot cells retain fractional native-GUI coordinates. */
internal object StorageOverlayLayout {
    const val GAP = 8
    private const val PADDING = 10
    private const val TITLE_HEIGHT = 24
    private const val CONTROL_WIDTH = 200

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right get() = x + width
        val bottom get() = y + height
        fun contains(px: Double, py: Double) = px >= x && px < right && py >= y && py < bottom
        fun intersects(other: Rect) = width > 0 && height > 0 && other.width > 0 && other.height > 0 && x < other.right && right > other.x && y < other.bottom && bottom > other.y
    }

    data class Cell(val x: Float, val y: Float, val width: Float, val height: Float) {
        val right get() = x + width
        val bottom get() = y + height
        fun contains(px: Double, py: Double) = px >= x && px < right && py >= y && py < bottom
        fun intersects(rect: Rect) = x < rect.right && right > rect.x && y < rect.bottom && bottom > rect.y
    }

    data class Frame(
        val panel: Rect,
        val header: Rect,
        val search: Rect,
        val filtersButton: Rect,
        val manageButton: Rect,
        val emptyButton: Rect,
        val filters: Rect,
        val notice: Rect,
        val wall: Rect,
        val dock: Rect,
        val dockArea: Rect,
        val dockContentHeight: Int,
        val playerGrid: Rect,
        val inventoryClip: Rect,
        val storageClip: Rect,
        val columns: Int,
        val cardWidth: Int,
        val slotSize: Float,
        val wallWidth: Int,
    ) {
        private val playerCells = Array(36) { index ->
            Cell(playerGrid.x + index % 9 * slotSize,
                playerGrid.y + index / 9 * slotSize + if (index >= 27) slotSize / 6 else 0f,
                slotSize, slotSize)
        }
        fun playerCell(index: Int): Cell = playerCells[index]
        fun storageContains(x: Double, y: Double) = storageClip.contains(x, y)
        fun nativeContains(player: Boolean, x: Double, y: Double) =
            if (player) inventoryClip.contains(x, y) else storageContains(x, y)
    }

    data class Card(val bounds: Rect, val grid: Rect, val slotSize: Float, val rows: Int) {
        private val cells = Array(rows * 9) { index ->
            Cell(grid.x + index % 9 * slotSize, grid.y + index / 9 * slotSize, slotSize, slotSize)
        }
        fun cell(index: Int): Cell = cells[index]
    }

    fun cardWidth(factor: Float) = ceil(162f / factor).toInt() + PADDING * 2

    fun columnsFor(width: Int, requested: Int, factor: Float): Int =
        min(requested.coerceIn(1, 6), max(1, (width - 48 + GAP) / (cardWidth(factor) + GAP)))

    fun frame(
        width: Int, height: Int, columns: Int, filters: Boolean, filterButtonWidth: Int,
        factor: Float = 1f, contentHeight: Int = 0, dockScroll: Int = 0,
    ): Frame {
        val slot = 18f / factor
        val groupGap = if (width < 760) GAP else 12
        val cardWidth = cardWidth(factor)
        val cols = columnsFor(width, columns, factor)
        val wallWidth = cols * cardWidth + (cols - 1) * GAP
        val availableWidth = max(1, width - 24)
        val inventoryHeight = 28 + ceil(slot * 4 + slot / 6).toInt() + PADDING
        val flank = min(CONTROL_WIDTH, max(1, (availableWidth - cardWidth - 24) / 2))
        val buttonHeight = max(24, ceil(max(120, filterButtonWidth).toFloat() / max(1, flank - 8)).toInt() * 11 + 8)
        val filterHeight = if (filters) (buttonHeight - 6) * 3 + 8 else 0
        val controlsHeight = (buttonHeight + 6) * 3 + filterHeight
        val dockHeight = max(inventoryHeight, controlsHeight)
        val availableHeight = max(0, height - 24 - TITLE_HEIGHT - groupGap * 2)
        val dockViewHeight = min(dockHeight, max(0, availableHeight - min(40, contentHeight)))
        val wallHeight = min(max(0, contentHeight), max(0, availableHeight - dockViewHeight))
        val groupHeight = TITLE_HEIGHT + groupGap + wallHeight + groupGap + dockViewHeight
        val top = max(12, (height - groupHeight) / 2)
        val groupWidth = max(wallWidth + 24, cardWidth + (flank + 12) * 2)
        val panel = Rect((width - groupWidth) / 2, top, groupWidth, groupHeight)
        val header = Rect(panel.x, top, groupWidth, TITLE_HEIGHT)
        val wall = Rect((width - min(availableWidth, wallWidth + 24)) / 2, header.bottom + groupGap, min(availableWidth, wallWidth + 24), wallHeight)
        val dockArea = Rect(panel.x, wall.bottom + groupGap, panel.width, dockViewHeight)
        val dock = Rect((width - cardWidth) / 2, dockArea.y - dockScroll, cardWidth, inventoryHeight)
        val controlWidth = flank
        val left = dock.x - 12 - controlWidth
        val right = dock.right + 12
        val search = Rect(right, dock.y, controlWidth, 26)
        val filter = Rect(left, dock.y, controlWidth, buttonHeight)
        val manage = Rect(left, filter.bottom + 6, controlWidth, buttonHeight)
        val empty = Rect(left, manage.bottom + 6, controlWidth, buttonHeight)
        val filtersRect = Rect(left, empty.bottom + 6, controlWidth, filterHeight)
        val notice = Rect(right, search.bottom + 7, controlWidth, controlsHeight - 33)
        val grid = Rect(dock.x + PADDING, dock.y + 28, ceil(slot * 9).toInt(), ceil(slot * 4 + slot / 6).toInt())
        val clipX = max(0, dock.x)
        val clipY = max(dockArea.y, dock.y)
        val clip = Rect(clipX, clipY, max(0, min(width, dock.right) - clipX), max(0, min(dockArea.bottom, dock.bottom) - clipY))
        val storageClip = Rect(wall.x, wall.y, max(0, wall.width - if (contentHeight > wallHeight) 12 else 0),
            max(0, wall.height - if (wallWidth + 24 > wall.width) 13 else 0))
        return Frame(panel, header, search, filter, manage, empty, filtersRect, notice, wall, dock, dockArea, dockHeight, grid, clip, storageClip, cols, cardWidth, slot, wallWidth)
    }

    fun cardHeight(rows: Int, seen: Boolean, slot: Float): Int =
        35 + ceil((if (seen) rows else 5) * slot).toInt() + PADDING

    fun card(frame: Frame, column: Int, top: Int, rows: Int, seen: Boolean, scrollX: Int, scrollY: Int): Card {
        val x = frame.wall.x + 12 + column * (frame.cardWidth + GAP) - scrollX
        val y = frame.wall.y + top - scrollY
        val bounds = Rect(x, y, frame.cardWidth, cardHeight(rows, seen, frame.slotSize))
        return Card(bounds, Rect(x + PADDING, y + 35, ceil(frame.slotSize * 9).toInt(),
            ceil(frame.slotSize * (if (seen) rows else 5)).toInt()), frame.slotSize, rows)
    }
}
