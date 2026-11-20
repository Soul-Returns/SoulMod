package com.soulreturns.features.profileviewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketValueTest {
    private val prices = mapOf(
        "HYPERION" to 900_000_000L,
        "ENCHANTED_DIAMOND" to 1_000L,
        "ENDER_DRAGON;4+100" to 600_000_000L,
        "ESSENCE_WITHER" to 3_000L,
        "ZERO" to 0L,
    )

    private fun estimate(inputs: MarketValue.Inputs) = MarketValue.estimate(inputs) { prices[it] ?: 0L }

    private fun inputs(items: List<MarketValue.Group>? = emptyList(), hidden: List<String> = emptyList()) = MarketValue.Inputs(
        purse = 1_500_000.0,
        bank = 20_000_000.0,
        items = items,
        sacks = mapOf("ENCHANTED_DIAMOND" to 640L, "UNPRICED_SACK_ITEM" to 12L, "EMPTY" to 0L),
        pets = listOf("ENDER_DRAGON;4+100", null),
        essence = mapOf("WITHER" to 1_000L),
        hidden = hidden,
    )

    @Test
    fun `the total is purse plus bank plus items, pets, sacks and essence at their prices`() {
        val items = listOf(
            MarketValue.Group("Inventory", listOf(MarketValue.Stack("HYPERION", 1), MarketValue.Stack("ENCHANTED_DIAMOND", 64))),
            MarketValue.Group("Ender Chest", listOf(MarketValue.Stack("ENCHANTED_DIAMOND", 10))),
        )
        val e = estimate(inputs(items))
        val expected = 1_500_000.0 + 20_000_000.0 + 900_000_000.0 + 64_000.0 + 10_000.0 + 600_000_000.0 + 640_000.0 + 3_000_000.0
        assertEquals(expected, e.total)
        val parts = e.parts.associate { it.label to it.coins }
        assertEquals(900_064_000.0, parts["Inventory"])
        assertEquals(10_000.0, parts["Ender Chest"])
        assertEquals(600_000_000.0, parts["Pets"])
        assertEquals(640_000.0, parts["Sacks"])
        assertEquals(3_000_000.0, parts["Essence"])
        assertEquals(e.parts.sortedByDescending { it.coins }.map { it.label }, e.parts.map { it.label })
        assertFalse(e.itemsHidden)
    }

    @Test
    fun `items without a price are counted, not guessed`() {
        val items = listOf(MarketValue.Group("Inventory", listOf(MarketValue.Stack(null, 1), MarketValue.Stack("ZERO", 3), MarketValue.Stack("HYPERION", 1))))
        val e = estimate(inputs(items))
        // the keyless stack, the zero-priced stack, the keyless pet and the unpriced sack type
        assertEquals(4, e.unpriced)
    }

    @Test
    fun `parts with nothing in them are left out of the breakdown`() {
        val e = estimate(inputs(listOf(MarketValue.Group("Wardrobe", emptyList()))))
        assertFalse(e.parts.any { it.label == "Wardrobe" })
    }

    @Test
    fun `Inventory API off says so instead of silently shrinking the number`() {
        val e = estimate(inputs(items = null, hidden = listOf("Bank: Banking API off")))
        assertTrue(e.itemsHidden)
        assertEquals(listOf(MarketValue.ITEMS_HIDDEN, "Bank: Banking API off"), e.hidden)
        assertEquals(setOf("Purse", "Bank", "Pets", "Sacks", "Essence"), e.parts.map { it.label }.toSet())
    }
}
