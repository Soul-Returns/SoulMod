package com.soulreturns.features.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageOverlayLayoutTest {
    private val BUTTONS = intArrayOf(64, 110, 100)

    @Test
    fun cardsHugTheirGridsAndOnlyLoseColumnsWhenNecessary() {
        val compact = StorageOverlayLayout.frame(1280, 720, 3, false, BUTTONS, contentHeight = 135)
        val wide = StorageOverlayLayout.frame(2560, 1440, 3, false, BUTTONS, contentHeight = 135)
        assertEquals(3, compact.columns)
        assertEquals(compact.cardWidth, wide.cardWidth)
        val required = 3 * compact.cardWidth + 2 * StorageOverlayLayout.GAP + 48
        assertEquals(3, StorageOverlayLayout.columnsFor(required, 3, 1f))
        assertEquals(2, StorageOverlayLayout.columnsFor(required - 1, 3, 1f))
        assertEquals(1, StorageOverlayLayout.columnsFor(100, 6, 1f))
        for (column in 0 until compact.columns) {
            val card = StorageOverlayLayout.card(compact, column, 0, 5, true, 0, 0)
            assertEquals(20, card.bounds.width - card.grid.width)
            for (index in 0 until 45) {
                val cell = card.cell(index)
                assertTrue(cell.x >= card.bounds.x && cell.right <= card.bounds.right)
                assertTrue(cell.y >= card.bounds.y && cell.bottom <= card.bounds.bottom)
            }
        }
    }

    @Test
    fun nativeSlotPitchAndIconSizeAreIndependentOfSoulScale() {
        for (minecraftScale in 1..4) for (soulScale in listOf(1f, 1.5f, 2f, 3f, 6f)) {
            val factor = soulScale / minecraftScale
            val frame = StorageOverlayLayout.frame((1920 / soulScale).toInt(), (1080 / soulScale).toInt(),
                3, false, BUTTONS, factor, 500)
            val card = StorageOverlayLayout.card(frame, 0, 0, 5, true, 0, 0)
            val storagePitch = card.cell(1).x - card.cell(0).x
            val playerPitch = frame.playerCell(1).x - frame.playerCell(0).x
            assertEquals(18f * minecraftScale, storagePitch * soulScale, 0.001f)
            assertEquals(18f * minecraftScale, playerPitch * soulScale, 0.001f)
            assertEquals(16f * minecraftScale, (frame.slotSize - 2f / factor) * soulScale, 0.001f)
            assertEquals(frame.slotSize, card.cell(0).width)
            assertEquals(frame.slotSize, frame.playerCell(0).width)
        }
    }

    @Test
    fun wholeContentGroupIsCenteredAndControlsAlignWithInventoryTop() {
        for (width in listOf(400, 640, 1280)) {
            val frame = StorageOverlayLayout.frame(width, 900, 3, true, BUTTONS, contentHeight = 135)
            assertTrue(kotlin.math.abs(frame.panel.y * 2 + frame.panel.height - 900) <= 1)
            assertTrue(frame.header.y > 12)
            assertEquals(frame.dock.y, frame.filtersButton.y)
            assertEquals(frame.dock.y, frame.search.y)
            assertEquals(frame.dock.y, frame.emptyButton.y)
            assertEquals(frame.dock.x - 12, frame.emptyButton.right)
            assertEquals(frame.search.height, frame.filtersButton.height)
            assertTrue(kotlin.math.abs(frame.wall.x * 2 + frame.wall.width - width) <= 1)
            assertTrue(kotlin.math.abs(frame.dock.x * 2 + frame.dock.width - width) <= 1)
            assertTrue(frame.filtersButton.right < frame.dock.x)
            assertTrue(frame.search.x > frame.dock.right)
            assertTrue(frame.wall.bottom < frame.dockArea.y)
        }
    }

    @Test
    fun wallScrollingNeverMovesOrOccludesTheNativePlayerSlots() {
        val frame = StorageOverlayLayout.frame(1280, 720, 3, false, BUTTONS, contentHeight = 2000)
        val first = StorageOverlayLayout.card(frame, 2, 320, 5, true, 0, 0)
        val scrolled = StorageOverlayLayout.card(frame, 2, 320, 5, true, 100, 240)
        for (index in 0 until 45) {
            assertEquals(first.cell(index).x - 100, scrolled.cell(index).x)
            assertEquals(first.cell(index).y - 240, scrolled.cell(index).y)
        }
        val player = frame.playerCell(35)
        assertTrue(player.bottom <= frame.dock.bottom)
        assertFalse(frame.storageContains(player.x + 1.0, player.y + 1.0))
        assertTrue(frame.nativeContains(true, player.x + 1.0, player.y + 1.0))
        assertTrue(frame.wall.bottom < frame.inventoryClip.y)
    }

    @Test
    fun adjacentDisplayedCellsHaveExclusivePointerEdges() {
        val frame = StorageOverlayLayout.frame(1280, 720, 3, false, BUTTONS)
        val card = StorageOverlayLayout.card(frame, 0, 0, 5, true, 0, 0)
        val first = card.cell(0)
        val next = card.cell(1)
        assertTrue(first.contains(first.x.toDouble(), first.y.toDouble()))
        assertFalse(first.contains(first.right.toDouble(), first.y.toDouble()))
        assertTrue(next.contains(first.right.toDouble(), first.y.toDouble()))
        assertEquals(first.y + first.height, card.cell(9).y)
    }

    @Test
    fun clippedWallAndScrollbarCannotReceiveNativeSlotInput() {
        val frame = StorageOverlayLayout.frame(400, 240, 3, false, BUTTONS, contentHeight = 2000)
        val card = StorageOverlayLayout.card(frame, 0, 0, 5, true, 0, 0)
        val bottomCell = card.cell(44)
        assertFalse(frame.nativeContains(false, bottomCell.x + 1.0, bottomCell.bottom - 1.0))
        assertFalse(frame.nativeContains(false, frame.wall.right - 3.0, frame.wall.y + 3.0))
        assertFalse(frame.nativeContains(false, frame.search.x + 1.0, frame.search.y + 1.0))
        assertFalse(frame.nativeContains(true, frame.search.x + 1.0, frame.search.y + 1.0))
    }

    @Test
    fun shortViewportsScrollTheDockWithoutOverlappingTheWall() {
        val first = StorageOverlayLayout.frame(400, 150, 3, false, BUTTONS, contentHeight = 1000)
        assertTrue(first.dockContentHeight > first.dockArea.height)
        val scroll = first.dockContentHeight - first.dockArea.height
        val last = StorageOverlayLayout.frame(400, 150, 3, false, BUTTONS, contentHeight = 1000, dockScroll = scroll)
        assertEquals(first.wall, last.wall)
        assertEquals(first.playerCell(35).y - scroll, last.playerCell(35).y)
        assertEquals(last.dock.y, last.search.y)
        assertEquals(last.dock.y, last.filtersButton.y)
        assertTrue(last.inventoryClip.y >= last.dockArea.y)
        assertTrue(last.inventoryClip.bottom <= last.dockArea.bottom)
        assertFalse(last.nativeContains(true, last.playerCell(0).x + 1.0, last.playerCell(0).y + 1.0))
    }
}
