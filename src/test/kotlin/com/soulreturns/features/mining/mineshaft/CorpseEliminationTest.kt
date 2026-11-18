package com.soulreturns.features.mining.mineshaft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorpseEliminationTest {
    private fun rows(vararg r: Pair<String, Pair<Int, Int>>) = mapOf(*r)

    @Test
    fun theLastOneIsWhatIsLeft() {
        // Lapis, Lapis, Umber: one Lapis seen, the other looted -> the unseen one is the Umber.
        val tab = rows("Lapis" to (2 to 1), "Umber" to (1 to 0))
        assertEquals("Umber", CorpseElimination.typeOfRest(tab, listOf("LAPIS"), listOf("Lapis"), 1))
        // The loot line counts before the tab catches up.
        assertEquals("Umber", CorpseElimination.typeOfRest(rows("Lapis" to (2 to 0), "Umber" to (1 to 0)), listOf("LAPIS"), listOf("Lapis"), 1))
    }

    @Test
    fun aShaftOfOneTypeNamesEveryCorpse() {
        assertEquals("Lapis", CorpseElimination.typeOfRest(rows("Lapis" to (3 to 0)), emptyList(), emptyList(), 2))
    }

    @Test
    fun twoTypesLeftNameNothing() {
        assertNull(CorpseElimination.typeOfRest(rows("Lapis" to (2 to 0), "Umber" to (1 to 0)), emptyList(), listOf("Lapis"), 1))
        assertNull(CorpseElimination.typeOfRest(rows("Lapis" to (1 to 0), "Tungsten" to (1 to 0)), emptyList(), emptyList(), 2))
    }

    @Test
    fun countsThatDoNotAddUpNameNothing() {
        // More unknown corpses than rows left, or more seen than the tab lists.
        assertNull(CorpseElimination.typeOfRest(rows("Lapis" to (1 to 0)), emptyList(), emptyList(), 2))
        assertNull(CorpseElimination.typeOfRest(rows("Lapis" to (1 to 0), "Umber" to (1 to 0)), emptyList(), listOf("Lapis", "Lapis"), 1))
        assertNull(CorpseElimination.typeOfRest(emptyMap(), emptyList(), emptyList(), 1))
        assertNull(CorpseElimination.typeOfRest(rows("Lapis" to (1 to 0)), emptyList(), emptyList(), 0))
    }
}
