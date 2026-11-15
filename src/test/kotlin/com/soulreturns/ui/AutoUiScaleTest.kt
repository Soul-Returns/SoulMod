package com.soulreturns.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoUiScaleTest {
    @Test
    fun referenceMonitors() {
        assertEquals(1.0f, AutoUiScale.forMonitorHeight(768))
        assertEquals(1.25f, AutoUiScale.forMonitorHeight(900))
        assertEquals(1.5f, AutoUiScale.forMonitorHeight(1080))
        assertEquals(2.0f, AutoUiScale.forMonitorHeight(1440))
        assertEquals(3.0f, AutoUiScale.forMonitorHeight(2160))
    }

    @Test
    fun clampedToTheSliderRange() {
        assertEquals(SoulUi.MIN_SCALE, AutoUiScale.forMonitorHeight(480))
        assertEquals(SoulUi.MAX_SCALE, AutoUiScale.forMonitorHeight(8640))
    }
}
