package com.soulreturns.features.search

import com.soulreturns.features.islandchests.StoredChestItem
import com.soulreturns.features.sacks.SackCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SackSearchEntriesTest {
    private class Chest(override val key: String) : SearchPlace

    private val names = mapOf("ENCHANTED_DIAMOND" to "Enchanted Diamond", "COBBLESTONE" to "Cobblestone", "RAW_FISH" to "Raw Fish")
    private fun info(id: String) = SackItemInfo(names[id], "minecraft:paper", null)
    private fun entries(vararg counts: Pair<String, Long>) = SackSearchEntries.build(counts.associate { (id, n) -> id to SackCount(n) }, ::info)
    private fun search(sacks: List<SearchEntry>, query: String, chest: List<SearchEntry> = emptyList()): List<SearchRow> {
        val rows = SearchIndex.build(listOf("island" to chest, "sacks" to sacks))
        return SearchIndex.filter(rows, query, listOf("island", "sacks"))
    }

    @Test
    fun matchesByNameAndIdAndSkipsZeroCounts() {
        val sacks = entries("ENCHANTED_DIAMOND" to 12L, "COBBLESTONE" to 0L, "RAW_FISH" to 5L)
        assertEquals(listOf("Enchanted Diamond"), search(sacks, "diamond").map { it.name })
        assertEquals(listOf("Raw Fish"), search(sacks, "raw_fish").map { it.name })
        assertTrue(search(sacks, "cobble").isEmpty())
        assertEquals(12L, search(sacks, "diamond").single().total(listOf("sacks")))
    }

    @Test
    fun unknownItemsAreAbsentAndNothingCountedGivesNoEntry() {
        assertTrue(search(entries("RAW_FISH" to 5L), "diamond").isEmpty())
        assertTrue(entries().isEmpty())
        assertTrue(entries("RAW_FISH" to 0L).isEmpty())
    }

    @Test
    fun joinsTheSingleChestRowOfTheSameId() {
        val chest = listOf(SearchEntry(Chest("A"), listOf(StoredChestItem("minecraft:diamond", "ENCHANTED_DIAMOND", "§aEnchanted Diamond", listOf("§7Lore"), 64))))
        val rows = search(entries("ENCHANTED_DIAMOND" to 100L), "enchanted diamond", chest)
        val row = rows.single()
        assertEquals(64L, row.total(listOf("island")))
        assertEquals(100L, row.total(listOf("sacks")))
    }

    @Test
    fun ambiguousChestVariantsKeepTheSackRowSeparate() {
        val chest = listOf(SearchEntry(Chest("A"), listOf(
            StoredChestItem("minecraft:paper", "RAW_FISH", "§fRaw Fish", listOf("a"), 1),
            StoredChestItem("minecraft:paper", "RAW_FISH", "§fRaw Fish", listOf("b"), 1),
        )))
        assertEquals(3, search(entries("RAW_FISH" to 5L), "raw fish", chest).size)
    }

    @Test
    fun prefixMatchesComeFirstAcrossSources() {
        val chest = listOf(SearchEntry(Chest("A"), listOf(
            StoredChestItem("minecraft:paper", "X", "Diamond Fragment", count = 1),
            StoredChestItem("minecraft:paper", "Y", "Diamond", count = 1),
        )))
        assertEquals(listOf("Diamond", "Diamond Fragment", "Enchanted Diamond"),
            search(entries("ENCHANTED_DIAMOND" to 1L), "diamond", chest).map { it.name })
        assertEquals(listOf("Diamond", "Diamond Fragment", "Enchanted Diamond"),
            search(entries("ENCHANTED_DIAMOND" to 1L), "", chest).map { it.name })
    }

    @Test
    fun fullLongCountsSurviveTheStackAdapter() {
        assertEquals(5_000_000_000L, search(entries("RAW_FISH" to 5_000_000_000L), "fish").single().total(listOf("sacks")))
    }
}
