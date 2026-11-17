package com.soulreturns.features.search.forge

import com.soulreturns.data.recipes.RecipeCatalogSnapshot
import com.soulreturns.data.recipes.RecipeIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compacted forms for Still to gather, on the mock fixture (goal TITANIUM_DRILL_4). */
class ForgeCompactedTest {
    private val fixture: JsonObject = Json.parseToJsonElement(javaClass.getResource("/forge/mock-recipes.json")!!.readText()).jsonObject
    private val json = Json { ignoreUnknownKeys = true }
    private val index = RecipeIndex.of(json.decodeFromJsonElement(RecipeCatalogSnapshot.serializer(), fixture["catalog"]!!))
    private val book = ForgeRecipes(index, fixture["locks"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })

    private fun owned(scenario: String): Map<String, Long> =
        fixture["scenarios"]!!.jsonObject[scenario]!!.jsonObject.mapValues { it.value.jsonPrimitive.long }

    private class Run(val agg: List<ForgeStep>, val forms: Map<String, List<ForgeStep>>) {
        val steps = agg.associateBy { it.id }
    }

    private fun run(have: Map<String, Long>): Run {
        val agg = ForgeEngine.aggregate(ForgeEngine.expand("TITANIUM_DRILL_4", book) { have[it] ?: 0L })
        return Run(agg, ForgeCompacted.formsByBase(agg, book))
    }

    @Test
    fun baseWithTwoCompactionLevelsListsBothClosestFirst() {
        val r = run(emptyMap())
        assertEquals(listOf("ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND_BLOCK"), r.forms.getValue("DIAMOND").map { it.id })
        assertEquals(listOf("ENCHANTED_GOLD", "ENCHANTED_GOLD_BLOCK"), r.forms.getValue("GOLD_INGOT").map { it.id })
    }

    @Test
    fun baseWithOneFormAndBaseWithNone() {
        val r = run(emptyMap())
        assertEquals(listOf("ENCHANTED_MITHRIL"), r.forms.getValue("MITHRIL_ORE").map { it.id })
        // A base material the tree never compacts gets no forms (and no entry).
        val gatherIds = ForgeEngine.gather(r.agg).map { it.id }
        val without = gatherIds.filter { it !in r.forms }
        assertTrue(without.isNotEmpty(), "the fixture has bases without forms: $gatherIds")
        assertTrue(r.forms.keys.all { it in gatherIds })
    }

    @Test
    fun forgeProcessesAreNotForms() {
        val r = run(emptyMap())
        val all = r.forms.values.flatten()
        assertTrue(all.all { it.kind == StepKind.CRAFT })
        for (forge in listOf("REFINED_MITHRIL", "REFINED_TITANIUM", "REFINED_DIAMOND")) {
            assertEquals(StepKind.FORGE, r.steps.getValue(forge).kind)
            assertFalse(all.any { it.id == forge })
        }
    }

    @Test
    fun formNumbersAreTheTreeStepsNumbers() {
        val r = run(owned("mid"))
        val form = r.forms.getValue("MITHRIL_ORE").single()
        val step = r.steps.getValue("ENCHANTED_MITHRIL")
        assertTrue(form === step)
        assertTrue(step.use > 0)
        assertEquals(step.need - step.use, step.missing)
    }

    @Test
    fun ownedCompactedStockReducesTheRawNeedAndShowsInBothRows() {
        val none = run(emptyMap())
        val mid = run(owned("mid"))
        val ench = mid.steps.getValue("ENCHANTED_MITHRIL")
        val ore = mid.steps.getValue("MITHRIL_ORE")
        // Owned Enchanted Mithril is spent first: only what is still missing of it is crafted from ore (160 each).
        assertEquals(160 * ench.missing, ore.need)
        assertTrue(ore.need < none.steps.getValue("MITHRIL_ORE").need)
        assertEquals(ench.need - ench.use, ench.missing)
        assertTrue(ench.need <= none.steps.getValue("ENCHANTED_MITHRIL").need) // owned upstream stock shrinks it too
        assertEquals(owned("mid").getValue("ENCHANTED_MITHRIL").coerceAtMost(ench.need), ench.use)
        // Default fold state follows what the player holds.
        val have = owned("mid")
        assertTrue(ForgeCompacted.defaultOpen(mid.forms.getValue("MITHRIL_ORE")) { have[it] ?: 0L })
        assertFalse(ForgeCompacted.defaultOpen(none.forms.getValue("MITHRIL_ORE")) { 0L })
    }
}
