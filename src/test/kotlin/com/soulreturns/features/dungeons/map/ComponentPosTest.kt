package com.soulreturns.features.dungeons.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentPosTest {
    @Test
    fun everyBlockOfTheLastRoomIsInsideTheGrid() {
        // Room 5 spans x -40..-10; its east half used to map to component 11 and hid the map.
        for (wx in -40..-10) {
            val c = ComponentPos.fromWorld(wx, wx)
            assertEquals(ComponentPos(10, 10), c, "x=z=$wx")
            assertTrue(c.isValid)
        }
    }

    @Test
    fun roomsAndGapsMapToEvenAndOddCells() {
        assertEquals(0, ComponentPos.fromWorld(-200, -200).x)
        assertEquals(0, ComponentPos.fromWorld(-170, -200).x)
        assertEquals(1, ComponentPos.fromWorld(-169, -200).x)
        assertEquals(2, ComponentPos.fromWorld(-168, -200).x)
    }

    @Test
    fun positionsOutsideTheRoomsAreInvalid() {
        assertFalse(ComponentPos.fromWorld(-201, -100).isValid)
        assertFalse(ComponentPos.fromWorld(-100, -201).isValid)
        assertFalse(ComponentPos.fromWorld(-9, -100).isValid)
        assertFalse(ComponentPos.fromWorld(73.0, 40.0).isValid)
    }

    @Test
    fun negativeFractionalPositionsFloor() {
        // -200.5 is outside room 0; truncating toward zero would have put it at -200.
        assertFalse(ComponentPos.fromWorld(-200.5, -100.0).isValid)
        assertEquals(ComponentPos(10, 10), ComponentPos.fromWorld(-10.5, -10.5))
    }
}
