package com.soulreturns.data.items

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fixture `forge/item-models.json`: every SkyBlock id of the 2026-11-16 capture with its `item_model` (or none). */
class ModelIndexTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/forge/item-models.json")!!.readText()).jsonObject
    private val models = fixture["models"]!!.jsonArray.map { it.jsonObject }
    private val index = ModelIndex.of(models.map { it.str("model")!! })

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun capturedIdsResolveToTheirModel() {
        val unmatched = setOf("MAGMA_FISH_DIAMOND", "NEX_TITANUM")
        for (m in models) {
            val id = m.str("id")!!
            val expected = if (id in unmatched) null else m.str("model")
            assertEquals(expected, index.resolve(id, m.str("name")), id)
        }
    }

    @Test
    fun capturedIdsWithoutModelResolveNothing() {
        for (m in fixture["withoutModel"]!!.jsonArray.map { it.jsonObject }) {
            assertNull(index.resolve(m.str("id")!!, m.str("name")), m.str("id"))
        }
    }

    @Test
    fun theShallowestOfSeveralModelsWinsAndATieResolvesNothing() {
        val idx = ModelIndex.of(listOf(
            "hypixel_skyblock:item/uncategorized/widget",
            "hypixel_skyblock:item/island_relevant/mining_3/widget",
            "hypixel_skyblock:item/a/gadget",
            "hypixel_skyblock:item/b/gadget",
            "hypixel_skyblock:font/refined_diamond",
        ))
        assertEquals("hypixel_skyblock:item/uncategorized/widget", idx.resolve("WIDGET", null))
        assertNull(idx.resolve("GADGET", null))
        assertNull(idx.resolve("REFINED_DIAMOND", "Refined Diamond"))
    }

    @Test
    fun petsAndAnEmptyIndexResolveNothing() {
        val idx = ModelIndex.of(listOf("hypixel_skyblock:item/pets/ammonite"))
        assertNull(idx.resolve("AMMONITE;4", "Ammonite"))
        assertNull(ModelIndex.EMPTY.resolve("REFINED_DIAMOND", "Refined Diamond"))
    }

    @Test
    fun displayNamesBecomePaths() {
        assertEquals("divans_drill", ModelIndex.pathName("Divan's Drill"))
        assertEquals("titanium_drill_dr_x655", ModelIndex.pathName("§6Titanium Drill DR-X655"))
        assertEquals("glacite_plated_chisel", ModelIndex.pathName("Glacite-Plated Chisel"))
    }
}
