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
                assertTrue(cell.width >= 20)
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
        assertTrue(player.y >= frame.wall.bottom)
        assertFalse(frame.wall.contains(player.x.toDouble(), player.y.toDouble()))
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
}
