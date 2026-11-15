package com.soulreturns.features.islandchests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IslandChestSearchIndexTest {
    private fun book(enchantment: String, count: Int = 1) = StoredChestItem(
        "minecraft:enchanted_book", "ENCHANTED_BOOK", "§fEnchanted Book", listOf(enchantment), count,
    )

    private fun chest(x: Int, vararg items: StoredChestItem) = StoredIslandChest(
        "$x,100,0", listOf(ChestPos(x, 100, 0)), "minecraft:chest", items.toList(), "observer", observedAt = 1,
    )

    @Test
    fun unknownEnchantmentsAreFoundWithoutAnyNameCatalog() {
        val futureBook = book("§9Uncatalogued Enchantment XXIV")
        val rows = IslandChestSearchIndex().search(listOf(chest(0, futureBook, book("§9Bank I"))), "UNCATALOGUED enchantment")
        assertEquals(listOf(futureBook), rows.map { it.item })
    }

    @Test
    fun enchantmentVariantsKeepSeparateCountsAcrossEveryMatchingChest() {
        val featherSix = book("§9Feather Falling VI")
        val featherSeven = book("§9Feather Falling VII")
        val rows = IslandChestSearchIndex().search(
            listOf(chest(0, featherSix, featherSix, book("§9Infinite Quiver VI")), chest(1, featherSix.copy(count = 2), featherSeven)),
            "feather falling",
        )
        assertEquals(2, rows.size)
        val six = rows.single { it.item.loreLines == featherSix.loreLines }
        assertEquals(4L, six.count)
        assertEquals(listOf(2L, 2L), six.matches.map { it.count })
        assertEquals(1L, rows.single { it.item.loreLines == featherSeven.loreLines }.count)
    }

    @Test
    fun bookLoreSearchWorksWithoutSkyblockIdAndRebuildsAfterContentsChange() {
        val index = IslandChestSearchIndex()
        val original = book("§9Sugar Rush III").copy(skyblockId = null)
        assertEquals(listOf(original), index.search(listOf(chest(0, original)), "sugar rush").map { it.item })
        assertTrue(index.search(listOf(chest(0, book("§9Ultimate Wise V"))), "sugar rush").isEmpty())
    }

    @Test
    fun identityKeepsAbsentMetadataDistinctFromEmptyMetadata() {
        val absent = StoredChestItem("minecraft:vine", count = 1)
        val emptyId = absent.copy(skyblockId = "")
        val emptyName = absent.copy(displayNameRaw = "")
        assertNotEquals(absent.searchKey(), emptyId.searchKey())
        assertNotEquals(absent.searchKey(), emptyName.searchKey())
        assertEquals(3, IslandChestSearchIndex().search(listOf(chest(0, absent, emptyId, emptyName)), "").size)
    }

    @Test
    fun bookNamesIgnoreAnvilBannerAndDescriptionsButKeepEveryEnchantmentAndLevel() {
        val item = book("§9Uncatalogued Enchantment XXIV").copy(loreLines = listOf(
            "§8Combinable in Anvil", "", "§9Uncatalogued Enchantment XXIV,", "§9Sugar Rush III",
            "§7Increases your speed.", "", "§7Applicable on: §9Boots", "§9§lRARE",
        ))
        assertEquals("Uncatalogued Enchantment XXIV, Sugar Rush III", item.searchName())
        assertEquals("Corruption I", book("§7Corruption I").searchName())
        assertEquals("Enchanted Book", book("").searchName())
    }
}
