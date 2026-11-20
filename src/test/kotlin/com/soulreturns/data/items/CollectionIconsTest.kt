package com.soulreturns.data.items

import com.soulreturns.data.hypixel.CollectionTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fixture `items/collection-catalog-ids.txt`: the `/items` ids collections, Jacob crops and minions use (catalog of 2026-11-20). */
class CollectionIconsTest {
    private val catalog = javaClass.getResource("/items/collection-catalog-ids.txt")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.toSet()

    @Test
    fun everyListedCollectionResolvesToACatalogItem() {
        for (def in CollectionTable.DEFS) {
            assertTrue(CollectionIcons.candidates(def.id).any { it in catalog }, "${def.id} has no catalog item")
        }
    }

    @Test
    fun aliasesExistOnlyForIdsTheCatalogLacks() {
        for ((id, target) in CollectionIcons.ALIASES) {
            assertTrue(id !in catalog, "$id is in the catalog: drop its alias")
            assertTrue(target in catalog, "$id aliases to $target, which the catalog lacks")
        }
    }

    @Test
    fun catalogIdsAreTriedFirst() {
        assertEquals(listOf("INK_SACK:3"), CollectionIcons.candidates("INK_SACK:3"))
        assertEquals(listOf("TUNGSTEN_COLLECTION", "TUNGSTEN"), CollectionIcons.candidates("TUNGSTEN_COLLECTION"))
        assertEquals(listOf("SOME_NEW_COLLECTION"), CollectionIcons.candidates("SOME_NEW_COLLECTION"))
    }

    @Test
    fun minionTypesBecomeTheirMinionItem() {
        assertEquals("CAVESPIDER_GENERATOR_4", CollectionIcons.minionId("CAVESPIDER", 4))
        assertEquals("WHEAT_GENERATOR_1", CollectionIcons.minionId("WHEAT", 0))
        for (type in listOf("COBBLESTONE", "CAVESPIDER", "NETHER_WARTS", "ENDER_STONE", "COCOA", "SUNFLOWER", "SUGAR_CANE")) {
            assertTrue(CollectionIcons.minionId(type, 1) in catalog, type)
        }
    }
}
