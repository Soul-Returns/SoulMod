package com.soulreturns.features.mining.commissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CommissionColorsTest {
    @Test
    fun keywords() {
        assertEquals(CommissionColors.RED, CommissionColors.forName("Goblin Raid Slayer"))
        assertEquals(CommissionColors.YELLOW, CommissionColors.forName("Raffle"))
        assertEquals(CommissionColors.WHITE, CommissionColors.forName("Upper Mines Titanium"))
        assertEquals(CommissionColors.GREEN, CommissionColors.forName("Royal Mines Mithril"))
    }

    @Test
    fun caseInsensitiveSubstring() {
        assertEquals(CommissionColors.RED, CommissionColors.forName("GOBLIN SLAYER"))
        assertEquals(CommissionColors.GREEN, CommissionColors.forName("Lava Springs mithril"))
    }

    @Test
    fun firstKeywordWins() {
        assertEquals(CommissionColors.RED, CommissionColors.forName("Goblin Mithril"))
        assertEquals(CommissionColors.YELLOW, CommissionColors.forName("Titanium Raffle"))
        assertEquals(CommissionColors.WHITE, CommissionColors.forName("Mithril Titanium"))
    }

    @Test
    fun othersAreNotWhite() {
        assertEquals(CommissionColors.OTHER, CommissionColors.forName("Ice Walker Slayer"))
        assertNotEquals(CommissionColors.WHITE, CommissionColors.OTHER)
    }
}
