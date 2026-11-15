package com.soulreturns.features.storage

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Chrome uses Soul pixels; slot cells retain fractional native-GUI coordinates. */
internal object StorageOverlayLayout {
    const val GAP = 8
    /** Inner padding of the shared panel around the page-card grid. */
    const val WALL_INSET = 12
    const val CONTROL_HEIGHT = 26
    private const val PADDING = 10
    private const val TITLE_HEIGHT = 26
    private const val MIN_SEARCH_WIDTH = 140

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

    /** [buttonWidths] holds the content widths of the Filters, Manage storage and Empty & locked segments. */
    fun frame(
        width: Int, height: Int, columns: Int, filters: Boolean, buttonWidths: IntArray,
        factor: Float = 1f, contentHeight: Int = 0, dockScroll: Int = 0,
    ): Frame {
        val slot = 18f / factor
        val groupGap = if (width < 760) GAP else 12
        val cardWidth = cardWidth(factor)
        val cols = columnsFor(width, columns, factor)
        val wallWidth = cols * cardWidth + (cols - 1) * GAP
        val availableWidth = max(1, width - 24)
        val inventoryHeight = 28 + ceil(slot * 4 + slot / 6).toInt() + PADDING
        val filterHeight = if (filters) CONTROL_HEIGHT - 4 else 0
        val controlsHeight = CONTROL_HEIGHT + if (filters) 6 + filterHeight else 0
        val dockHeight = max(inventoryHeight, controlsHeight)
        val availableHeight = max(0, height - 24 - TITLE_HEIGHT - groupGap * 2)
        val wallContent = max(0, contentHeight) + WALL_INSET * 2
        val dockViewHeight = min(dockHeight, max(0, availableHeight - min(40 + WALL_INSET * 2, wallContent)))
        val wallHeight = min(wallContent, max(0, availableHeight - dockViewHeight))
        val groupHeight = TITLE_HEIGHT + groupGap + wallHeight + groupGap + dockViewHeight
        val top = max(12, (height - groupHeight) / 2)
        val header = Rect(0, top, width, TITLE_HEIGHT)
        val wallOuter = min(availableWidth, wallWidth + WALL_INSET * 2)
        val wall = Rect((width - wallOuter) / 2, header.bottom + groupGap, wallOuter, wallHeight)
        val dockY = wall.bottom + groupGap
        val dock = Rect((width - cardWidth) / 2, dockY - dockScroll, cardWidth, inventoryHeight)
        // Segmented bar hugs the inventory's left edge; segments shrink evenly when the flank is too narrow.
        val flank = max(3, dock.x - 24)
        val wanted = buttonWidths.sum().coerceAtLeast(1)
        val segments = IntArray(3) { if (wanted <= flank) buttonWidths[it] else max(1, buttonWidths[it] * flank / wanted) }
        val barRight = dock.x - 12
        val empty = Rect(barRight - segments[2], dock.y, segments[2], CONTROL_HEIGHT)
        val manage = Rect(empty.x - segments[1], dock.y, segments[1], CONTROL_HEIGHT)
        val filter = Rect(manage.x - segments[0], dock.y, segments[0], CONTROL_HEIGHT)
        val filtersRect = Rect(filter.x, filter.bottom + 6, barRight - filter.x, filterHeight)
        val searchX = dock.right + 12
        val searchRight = min(width - 12, max(wall.right, searchX + MIN_SEARCH_WIDTH))
        val search = Rect(searchX, dock.y, max(1, searchRight - searchX), CONTROL_HEIGHT)
        val notice = Rect(searchX, search.bottom + 6, search.width, max(0, dockHeight - CONTROL_HEIGHT - 6))
        val left = min(wall.x, filter.x)
        val right = max(wall.right, search.right)
        val panel = Rect(left, top, right - left, groupHeight)
        val dockArea = Rect(left, dockY, right - left, dockViewHeight)
        val grid = Rect(dock.x + PADDING, dock.y + 28, ceil(slot * 9).toInt(), ceil(slot * 4 + slot / 6).toInt())
        val clipX = max(0, dock.x)
        val clipY = max(dockArea.y, dock.y)
        val clip = Rect(clipX, clipY, max(0, min(width, dock.right) - clipX), max(0, min(dockArea.bottom, dock.bottom) - clipY))
        // Inset by the panel border so scrolled cards never paint over it.
        val storageClip = Rect(wall.x + 1, wall.y + 1, max(0, wall.width - 2 - if (wallContent > wallHeight) 12 else 0),
            max(0, wall.height - 2 - if (wallWidth + WALL_INSET * 2 > wall.width) 13 else 0))
        return Frame(panel, header, search, filter, manage, empty, filtersRect, notice, wall, dock, dockArea, dockHeight, grid, clip, storageClip, cols, cardWidth, slot, wallWidth)
    }

    fun cardHeight(rows: Int, seen: Boolean, slot: Float): Int =
        35 + ceil((if (seen) rows else 5) * slot).toInt() + PADDING

    fun card(frame: Frame, column: Int, top: Int, rows: Int, seen: Boolean, scrollX: Int, scrollY: Int): Card {
        val x = frame.wall.x + WALL_INSET + column * (frame.cardWidth + GAP) - scrollX
        val y = frame.wall.y + WALL_INSET + top - scrollY
        val bounds = Rect(x, y, frame.cardWidth, cardHeight(rows, seen, frame.slotSize))
        return Card(bounds, Rect(x + PADDING, y + 35, ceil(frame.slotSize * 9).toInt(),
            ceil(frame.slotSize * (if (seen) rows else 5)).toInt()), frame.slotSize, rows)
    }
}
