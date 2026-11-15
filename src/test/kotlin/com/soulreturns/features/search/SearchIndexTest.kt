package com.soulreturns.features.search

import com.soulreturns.features.islandchests.StoredChestItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected rows, totals and chip counts were read from `mocks/search-everywhere/` (its RAW data and `resultsIn`). */
class SearchIndexTest {
    private class Place(override val key: String) : SearchPlace

    // [id, name, island hits, storage hits] from the mock's RAW table.
    private val raw = listOf(
        Raw("ENCHANTED_IRON", "Enchanted Iron", mapOf("A" to 512, "B" to 128, "D" to 64), mapOf("e2" to 576, "b5" to 64)),
        Raw("ENCHANTED_DIAMOND", "Enchanted Diamond", mapOf("D" to 96, "F" to 32), mapOf("b3" to 256)),
        Raw("RECOMBOBULATOR_3000", "Recombobulator 3000", mapOf("F" to 2), mapOf("e1" to 5)),
        Raw("ENCHANTED_ENDER_PEARL", "Enchanted Ender Pearl", mapOf("C" to 192, "D" to 64), mapOf("b6" to 32)),
        Raw("ENCHANTED_REDSTONE", "Enchanted Redstone", mapOf("B" to 1024, "E" to 256), mapOf()),
        Raw("ENCHANTED_COAL", "Enchanted Coal", mapOf("C" to 256), mapOf()),
        Raw("ENCHANTED_COBBLESTONE", "Enchanted Cobblestone", mapOf("C" to 2048, "E" to 512), mapOf()),
        Raw("SUPER_COMPACTOR_3000", "Super Compactor 3000", mapOf("F" to 12), mapOf()),
        Raw("ENCHANTED_BLAZE_ROD", "Enchanted Blaze Rod", mapOf("F" to 24), mapOf()),
        Raw("TITANIUM_ORE", "Titanium", mapOf("B" to 32), mapOf()),
        Raw("SCORCHED_POWER_CRYSTAL", "Scorched Power Crystal", mapOf("E" to 1, "F" to 2), mapOf()),
        Raw("STORM_THE_FISH", "Storm the Fish", mapOf("D" to 1, "F" to 2), mapOf()),
        Raw("MAXOR_THE_FISH", "Maxor the Fish", mapOf("D" to 3), mapOf()),
        Raw("HYPERION", "Hyperion", mapOf(), mapOf("e1" to 1)),
        Raw("ASPECT_OF_THE_DRAGON", "Aspect of the Dragons", mapOf(), mapOf("e2" to 1)),
        Raw("BOOSTER_COOKIE", "Booster Cookie", mapOf(), mapOf("e1" to 2)),
        Raw("FUMING_POTATO_BOOK", "Fuming Potato Book", mapOf(), mapOf("e3" to 9)),
        Raw("HOT_POTATO_BOOK", "Hot Potato Book", mapOf(), mapOf("e3" to 23, "b8" to 14)),
        Raw("KISMET_FEATHER", "Kismet Feather", mapOf(), mapOf("e3" to 7)),
        Raw("WITHER_CATALYST", "Wither Catalyst", mapOf(), mapOf("e4" to 12)),
        Raw("SUMMONING_EYE", "Summoning Eye", mapOf(), mapOf("e5" to 4)),
        Raw("SPIRIT_LEAP", "Spirit Leap", mapOf(), mapOf("b1" to 48)),
        Raw("SUPERBOOM_TNT", "Superboom TNT", mapOf(), mapOf("b1" to 64)),
        Raw("DUNGEON_DECOY", "Decoy", mapOf(), mapOf("b1" to 32)),
        Raw("ENCHANTED_GOLD", "Enchanted Gold", mapOf(), mapOf("b2" to 384)),
        Raw("ENCHANTED_LAPIS_LAZULI", "Enchanted Lapis Lazuli", mapOf(), mapOf("b2" to 1280, "b4" to 640)),
        Raw("ENCHANTED_MITHRIL", "Enchanted Mithril", mapOf(), mapOf("b7" to 320)),
        Raw("ENDER_PEARL", "Ender Pearl", mapOf(), mapOf("b6" to 16)),
        Raw("ENCHANTED_SUGAR_CANE", "Enchanted Sugar Cane", mapOf(), mapOf("b8" to 192)),
    )

    private class Raw(val id: String, val name: String, val island: Map<String, Int>, val storage: Map<String, Int>) {
        fun item(count: Int) = StoredChestItem("minecraft:paper", id, "§a$name", count = count)
    }

    private fun entries(pick: (Raw) -> Map<String, Int>): List<SearchEntry> {
        val places = LinkedHashMap<String, MutableList<StoredChestItem?>>()
        for (row in raw) for ((place, count) in pick(row)) places.getOrPut(place) { mutableListOf() } += row.item(count)
        return places.map { (key, items) -> SearchEntry(Place(key), items) }
    }

    private val index = SearchIndex().also { it.update(listOf("island" to entries { r -> r.island }, "storage" to entries { r -> r.storage })) }
    private val both = listOf("island", "storage")

    private fun ids(query: String, sources: List<String> = both) = index.search(query, sources).map { it.item.skyblockId }

    @Test
    fun blankQueryListsEverythingAlphabeticallyWithTheMocksChipCounts() {
        assertEquals(listOf("ASPECT_OF_THE_DRAGON", "BOOSTER_COOKIE", "DUNGEON_DECOY", "ENCHANTED_BLAZE_ROD", "ENCHANTED_COAL",
            "ENCHANTED_COBBLESTONE", "ENCHANTED_DIAMOND", "ENCHANTED_ENDER_PEARL", "ENCHANTED_GOLD", "ENCHANTED_IRON",
            "ENCHANTED_LAPIS_LAZULI", "ENCHANTED_MITHRIL", "ENCHANTED_REDSTONE", "ENCHANTED_SUGAR_CANE", "ENDER_PEARL",
            "FUMING_POTATO_BOOK", "HOT_POTATO_BOOK", "HYPERION", "KISMET_FEATHER", "MAXOR_THE_FISH", "RECOMBOBULATOR_3000",
            "SCORCHED_POWER_CRYSTAL", "SPIRIT_LEAP", "STORM_THE_FISH", "SUMMONING_EYE", "SUPER_COMPACTOR_3000",
            "SUPERBOOM_TNT", "TITANIUM_ORE", "WITHER_CATALYST"), ids(""))
        assertEquals(listOf(29, 13, 20), listOf(ids("").size, ids("", listOf("island")).size, ids("", listOf("storage")).size))
    }

    @Test
    fun mergedRowsSumTheFilteredSources() {
        val iron = index.search("iron", both).single()
        assertEquals(1344L, iron.total(both))
        assertEquals(704L, iron.total(listOf("island")))
        assertEquals(640L, iron.total(listOf("storage")))
        assertEquals(listOf("A", "B", "D"), iron.hitsIn("island").map { it.place.key })
        assertEquals(listOf("e2", "b5"), iron.hitsIn("storage").map { it.place.key })
        assertEquals(listOf(1, 1, 1), listOf(ids("iron").size, ids("iron", listOf("island")).size, ids("iron", listOf("storage")).size))
    }

    @Test
    fun chipCountsAndOrderForTheMocksQueries() {
        val enchanted = listOf("ENCHANTED_BLAZE_ROD", "ENCHANTED_COAL", "ENCHANTED_COBBLESTONE", "ENCHANTED_DIAMOND", "ENCHANTED_ENDER_PEARL",
            "ENCHANTED_GOLD", "ENCHANTED_IRON", "ENCHANTED_LAPIS_LAZULI", "ENCHANTED_MITHRIL", "ENCHANTED_REDSTONE", "ENCHANTED_SUGAR_CANE")
        assertEquals(enchanted, ids("enchanted"))
        assertEquals(listOf(11, 7, 7), listOf(ids("enchanted").size, ids("enchanted", listOf("island")).size, ids("enchanted", listOf("storage")).size))
        assertEquals(listOf("ENCHANTED_ENDER_PEARL", "ENDER_PEARL"), ids("pearl"))
        assertEquals(listOf(2, 1, 2), listOf(ids("pearl").size, ids("pearl", listOf("island")).size, ids("pearl", listOf("storage")).size))
        assertEquals(listOf("MAXOR_THE_FISH", "STORM_THE_FISH"), ids("fish"))
        assertEquals(listOf(2, 2, 0), listOf(ids("fish").size, ids("fish", listOf("island")).size, ids("fish", listOf("storage")).size))
        assertEquals(emptyList(), ids("necron's handle"))
    }

    @Test
    fun namesStartingWithTheQueryComeFirst() {
        assertEquals(listOf("ENDER_PEARL", "ENCHANTED_ENDER_PEARL"), ids("ender"))
    }

    @Test
    fun rebuildsOnlyWhenASourceHandsOutANewList() {
        val island = entries { it.island }
        val storage = entries { it.storage }
        val fresh = SearchIndex()
        val first = fresh.update(listOf("island" to island, "storage" to storage))
        assertEquals(first, fresh.update(listOf("island" to island, "storage" to storage)))
        val onlyIsland = fresh.update(listOf("island" to island))
        assertEquals(13, onlyIsland.size)
        assertEquals(listOf(3, 1), listOf(onlyIsland.first { it.item.skyblockId == "ENCHANTED_IRON" }.hitsIn("island").size,
            onlyIsland.first { it.item.skyblockId == "ENCHANTED_IRON" }.hitsIn("island")[0].stacks))
    }
}
