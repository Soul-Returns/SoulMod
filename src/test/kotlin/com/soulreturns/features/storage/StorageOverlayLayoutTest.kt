package com.soulreturns.features.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageOverlayLayoutTest {
    @Test
    fun minimumWidthCardsKeepEverySlotInsideTheirOwnPage() {
        val frame = StorageOverlayLayout.frame(600, 600, 6, false, 90)
        assertTrue(frame.wallWidth > frame.wall.width)
        for (column in 0 until frame.columns) {
            val card = StorageOverlayLayout.card(frame, column, 0, 5, true, 0, 0)
            for (index in 0 until 45) {
                val cell = card.cell(index)
                assertEquals(20, cell.width)
                assertTrue(cell.x >= card.bounds.x && cell.right <= card.bounds.right)
                assertTrue(cell.y >= card.bounds.y && cell.bottom <= card.bounds.bottom)
            }
        }
    }

    @Test
    fun scrollingMovesEveryStorageHitCellWithoutMovingThePlayerInventory() {
        val frame = StorageOverlayLayout.frame(1280, 720, 3, false, 90)
        val first = StorageOverlayLayout.card(frame, 2, 320, 5, true, 0, 0)
        val scrolled = StorageOverlayLayout.card(frame, 2, 320, 5, true, 100, 240)
        val player = frame.playerCell(35)
        for (index in 0 until 45) {
            assertEquals(first.cell(index).x - 100, scrolled.cell(index).x)
            assertEquals(first.cell(index).y - 240, scrolled.cell(index).y)
        }
        assertTrue(player.bottom <= frame.dock.bottom)
        assertTrue(player.y < frame.wall.bottom)
        assertTrue(frame.wall.contains(player.x.toDouble(), player.y.toDouble()))
        assertFalse(frame.storageContains(player.x.toDouble(), player.y.toDouble()))
    }

    @Test
    fun displayedCellEdgesBelongToOnlyOneSlot() {
        val frame = StorageOverlayLayout.frame(1280, 720, 3, false, 90)
        val card = StorageOverlayLayout.card(frame, 0, 0, 5, true, 0, 0)
        val first = card.cell(0)
        val next = card.cell(1)
        assertTrue(first.contains(first.x.toDouble(), first.y.toDouble()))
        assertFalse(first.contains(first.right.toDouble(), first.y.toDouble()))
        assertFalse(next.contains(first.right.toDouble(), first.y.toDouble()))
        assertTrue(next.contains(next.x.toDouble(), next.y.toDouble()))
        assertEquals(first.y + first.height + 1, card.cell(9).y)
    }

    @Test
    fun slotSizesStayFixedWhenCardsHaveExtraWidth() {
        for (width in listOf(600, 1280, 2560)) for (columns in 1..6) {
            val frame = StorageOverlayLayout.frame(width, 720, columns, false, 90)
            assertEquals(20, frame.slotSize)
            assertEquals(22, frame.playerCell(0).width)
            val card = StorageOverlayLayout.card(frame, 0, 0, 5, true, 0, 0)
            assertEquals(188, card.grid.width)
            assertTrue(card.grid.x > card.bounds.x)
            assertTrue(card.grid.right < card.bounds.right)
        }
    }

    @Test
    fun mockReferenceGeometryUsesOneHeaderRowAndAFloatingDock() {
        val frame = StorageOverlayLayout.frame(1280, 720, 3, false, 90)
        assertEquals(48, frame.header.height)
        assertEquals(26, frame.search.height)
        assertEquals(frame.search.y, frame.filtersButton.y)
        assertEquals(frame.search.y, frame.closeButton.y)
        assertEquals(StorageOverlayLayout.Rect(523, 521, 234, 134), frame.dock)
        assertEquals(StorageOverlayLayout.Rect(533, 549, 214, 97), frame.playerGrid)
        assertEquals(665, frame.wall.bottom)
        assertEquals(frame.footer.y, frame.wall.bottom)
        assertTrue(frame.dock.x > frame.wall.x)
        assertTrue(frame.dock.right < frame.wall.right)
    }

    @Test
    fun headerWrapsOnlyWhenTheCompactControlsCannotFit() {
        val frame = StorageOverlayLayout.frame(400, 600, 3, true, 90)
        assertEquals(80, frame.header.height)
        assertTrue(frame.search.y > frame.header.y + 30)
        assertTrue(frame.search.right < frame.filtersButton.x)
        assertTrue(frame.filtersButton.right < frame.closeButton.x)
        assertEquals(34, frame.filters.height)
        assertEquals(frame.filters.bottom, frame.wall.y)
    }

    @Test
    fun floatingDockWinsStorageHitTestsButKeepsNativePlayerSlots() {
        val frame = StorageOverlayLayout.frame(1280, 720, 3, false, 90)
        val player = frame.playerCell(1)
        val x = player.x + 2.0
        val y = player.y + 2.0
        val behind = StorageOverlayLayout.card(frame, 1, player.y - frame.wall.y - 45, 5, true, 0, 0)
        assertTrue(behind.cell(0).contains(x, y))
        assertTrue(behind.bounds.intersects(frame.dock))
        assertFalse(frame.nativeContains(false, x, y))
        assertTrue(frame.nativeContains(true, x, y))
        assertFalse(frame.storageContains(frame.dock.x + 1.0, frame.dock.y + 1.0))
        assertTrue(frame.storageContains(frame.dock.x - 1.0, frame.dock.y + 1.0))
        assertTrue(frame.storageContains(frame.dock.right.toDouble(), frame.dock.y + 1.0))
    }

    @Test
    fun shortViewportsClipInventoryBeforeTheHeaderControls() {
        val frame = StorageOverlayLayout.frame(400, 200, 3, false, 90)
        assertTrue(frame.dock.y < frame.header.bottom)
        assertEquals(frame.wall.y, frame.inventoryClip.y)
        assertFalse(frame.nativeContains(true, frame.search.x + 1.0, frame.search.y + 1.0))
    }
}
