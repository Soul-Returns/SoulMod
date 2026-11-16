package com.soulreturns.features.search.forge

import com.soulreturns.data.recipes.RecipeCatalogSnapshot
import com.soulreturns.data.recipes.RecipeIndex
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Forge categories: the player's menus > the backend's served `category` / `order` > "Not sorted yet". */
class ForgeCategoriesTest {
    // BackendCatalog's decoder settings.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    private fun index(payload: String) = RecipeIndex.of(json.decodeFromString(RecipeCatalogSnapshot.serializer(), payload))

    private val old = """{"schemaVersion":1,"forge":[
        {"output":"REFINED_DIAMOND","count":1,"inputs":{"ENCHANTED_DIAMOND_BLOCK":2},"coins":0,"baseSeconds":28800},
        {"output":"FUEL_TANK","count":1,"inputs":{"COAL":1},"coins":0,"baseSeconds":30}]}"""

    private val served = """{"schemaVersion":1,"forge":[
        {"output":"REFINED_DIAMOND","inputs":{"ENCHANTED_DIAMOND_BLOCK":2},"baseSeconds":28800,"category":"Refine Items","order":12},
        {"output":"REFINED_MITHRIL","inputs":{"ENCHANTED_MITHRIL":160},"baseSeconds":21600,"category":"Refine Items","order":11},
        {"output":"FUEL_TANK","inputs":{"COAL":1},"baseSeconds":30,"category":"Drill Parts","order":null},
        {"output":"MITHRIL_PLATE","inputs":{"REFINED_MITHRIL":5},"baseSeconds":64800,"category":"Drill Parts","order":10},
        {"output":"BEACON_2","inputs":{"BEACON_1":1},"baseSeconds":72000,"category":null},
        {"output":"PERFECT_RUBY_GEM","inputs":{"FLAWLESS_RUBY_GEM":5},"baseSeconds":72000}]}"""

    @Test
    fun aPayloadWithoutTheFieldsLeavesEveryRecipeUnsorted() {
        val idx = index(old)
        assertNull(idx.forge["REFINED_DIAMOND"]!!.category)
        assertNull(idx.forge["REFINED_DIAMOND"]!!.order)
        val cats = ForgeCategories.of(idx, ForgeBookData())
        assertTrue(cats.categories.isEmpty())
        assertTrue(cats.unsorted("REFINED_DIAMOND") && cats.unsorted("FUEL_TANK"))
    }

    @Test
    fun servedCategoriesPlaceRecipesNoMenuShowed() {
        val cats = ForgeCategories.of(index(served), ForgeBookData())
        assertEquals(listOf("Drill Parts", "Refine Items"), cats.categories)
        assertEquals("Refine Items", cats.categoryOf("REFINED_DIAMOND"))
        assertEquals(11, cats.orderOf("REFINED_MITHRIL"))
        assertEquals("Drill Parts", cats.categoryOf("FUEL_TANK"))
        assertEquals(Int.MAX_VALUE, cats.orderOf("FUEL_TANK"))
        assertTrue(cats.unsorted("BEACON_2"))
        assertTrue(cats.unsorted("PERFECT_RUBY_GEM"))
    }

    @Test
    fun whatTheMenusShowedWins() {
        val book = ForgeBookData(
            categories = listOf("Refine Items", "Forge Items"),
            recipes = mapOf(
                "REFINED_DIAMOND" to ForgeBookEntry("Refine Items", 3),
                "MITHRIL_PLATE" to ForgeBookEntry("Forge Items", 1),
                "BEACON_2" to ForgeBookEntry("Forge Items", 7),
            ),
        )
        val cats = ForgeCategories.of(index(served), book)
        // Menu order first, then categories only the backend names, alphabetically.
        assertEquals(listOf("Refine Items", "Forge Items", "Drill Parts"), cats.categories)
        assertEquals(3, cats.orderOf("REFINED_DIAMOND"))
        assertEquals("Forge Items", cats.categoryOf("MITHRIL_PLATE"))
        assertEquals("Forge Items", cats.categoryOf("BEACON_2"))
        assertEquals(11, cats.orderOf("REFINED_MITHRIL"))
        assertTrue(cats.unsorted("PERFECT_RUBY_GEM"))
    }
}
