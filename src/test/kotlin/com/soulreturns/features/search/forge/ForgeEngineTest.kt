package com.soulreturns.features.search.forge

import com.soulreturns.data.recipes.RecipeCatalogSnapshot
import com.soulreturns.data.recipes.RecipeIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Expected values come from `mocks/search-everywhere/forge-engine.js` run on the mock's data and owned
 * scenarios (fixture `forge/mock-recipes.json`, generated from `forge-data.js` / `forge.js`), Quick Forge 30 %.
 */
class ForgeEngineTest {
    private val fixture: JsonObject = Json.parseToJsonElement(javaClass.getResource("/forge/mock-recipes.json")!!.readText()).jsonObject
    private val json = Json { ignoreUnknownKeys = true }
    private val index = RecipeIndex.of(json.decodeFromJsonElement(RecipeCatalogSnapshot.serializer(), fixture["catalog"]!!))
    private val locks = fixture["locks"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val book = ForgeRecipes(index, locks)
    private val goal = "TITANIUM_DRILL_4"

    private fun have(scenario: String): (String) -> Long {
        val owned = fixture["scenarios"]!!.jsonObject[scenario]!!.jsonObject.mapValues { it.value.jsonPrimitive.long }
        return { owned[it] ?: 0L }
    }

    private class Run(val root: ForgeNode, val agg: List<ForgeStep>, val time: TimeLeft, val plan: ForgePlan, val gather: List<ForgeStep>)

    private fun run(scenario: String, qf: Double = 0.30): Run {
        val root = ForgeEngine.expand(goal, book, have(scenario))
        val agg = ForgeEngine.aggregate(root)
        return Run(root, agg, ForgeEngine.timeLeft(root, agg, 7, qf), ForgeEngine.plan(root, agg, 7, qf), ForgeEngine.gather(agg))
    }

    @Test
    fun drX655FromNothing() {
        val r = run("none")
        assertEquals(372, r.time.processes)
        assertEquals(8064063, r.time.serial)
        assertEquals("93d 8h 1m 3s", ForgeFormat.duration(r.time.serial))
        assertEquals("1d 9h 37m 3s", ForgeFormat.duration(r.time.chain)) // 33h 37m
        assertEquals("13d 8h 9s", ForgeFormat.duration(r.time.bound))
        assertEquals(17, r.gather.size)
        assertEquals(17, r.gather.count { it.missing > 0 })
        assertEquals(listOf("DIAMOND", "MITHRIL_ORE", "GOLD_INGOT", "IRON_INGOT", "ROUGH_RUBY_GEM"), r.gather.take(5).map { it.id })
        assertEquals(3_430_400, r.gather[0].need)
        val steps = r.agg.associateBy { it.id }
        assertEquals(93, steps.getValue("REFINED_TITANIUM").times)
        assertEquals(133, steps.getValue("REFINED_MITHRIL").times)
        assertEquals(21_440, steps.getValue("ENCHANTED_DIAMOND").times)
        assertEquals(7, steps.getValue("REFINED_TITANIUM").at)
        assertTrue(r.plan.slots.isEmpty())
        assertEquals(listOf("ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND_BLOCK", "ENCHANTED_GOLD", "ENCHANTED_COAL", "ENCHANTED_GOLD_BLOCK"),
            r.plan.waiting.take(5).map { it.step.id })
        assertEquals("DIAMOND", r.plan.waiting[0].block!!.id)
        // Everything red: nothing can be made.
        assertTrue(ForgeEngine.nodes(r.root).all { ForgeEngine.status(it).state == StepState.MISSING })
    }

    @Test
    fun owningADrX555StopsThatWholeBranch() {
        val r = run("x555")
        assertEquals(144, r.time.processes)
        assertEquals("35d 19h 36m 21s", ForgeFormat.duration(r.time.serial))
        assertEquals("22h 24m 21s", ForgeFormat.duration(r.time.chain))
        assertEquals("5d 2h 48m 3s", ForgeFormat.duration(r.time.bound))
        assertEquals(13, r.gather.size)
        val drill = r.root.children.first { it.id == "TITANIUM_DRILL_3" }
        assertEquals(StepState.COVERED, ForgeEngine.status(drill).state)
        assertTrue(drill.children.isEmpty())
        val steps = r.agg.associateBy { it.id }
        assertEquals(42, steps.getValue("REFINED_TITANIUM").times)
        assertEquals(15, steps.getValue("REFINED_DIAMOND").times)
        assertEquals(4800, steps.getValue("ENCHANTED_DIAMOND").times)
    }

    @Test
    fun midProgressIronman() {
        val r = run("mid")
        assertEquals(190, r.time.processes)
        assertEquals("48d 19h 48m 42s", ForgeFormat.duration(r.time.serial))
        assertEquals("22h 24m 42s", ForgeFormat.duration(r.time.chain))
        assertEquals("6d 23h 24m 6s", ForgeFormat.duration(r.time.bound))
        assertEquals(11, r.gather.size)
        assertEquals(10, r.gather.count { it.missing > 0 })
        assertEquals(List(7) { "REFINED_TITANIUM" }, r.plan.slots)
        assertEquals(listOf("REFINED_TITANIUM" to 7L, "REFINED_MITHRIL" to 0L, "REFINED_DIAMOND" to 0L, "GEMSTONE_MIXTURE" to 0L),
            r.plan.forge.map { it.step.id to it.now })
        assertEquals(listOf(10L, 18L, 12L, 2L), r.plan.forge.map { it.step.ready })
        assertEquals(listOf("ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND_BLOCK", "ENCHANTED_GOLD_BLOCK", "ENCHANTED_MITHRIL", "ENCHANTED_TITANIUM",
            "ENCHANTED_IRON_BLOCK", "FLAWED_AMETHYST_GEM", "FLAWED_JADE_GEM", "FLAWED_RUBY_GEM", "FLAWED_SAPPHIRE_GEM", "FINE_JADE_GEM",
            "FINE_RUBY_GEM", "FINE_SAPPHIRE_GEM"), r.plan.craft.map { it.id })
        assertEquals(601, r.plan.craft[0].ready)
        val first = r.plan.waiting.first()
        assertEquals("ENCHANTED_DIAMOND", first.step.id)
        assertEquals(4851, first.step.times - first.step.ready)
        assertEquals(Triple("DIAMOND", 96210L, 872320L), Triple(first.block!!.id, first.block.use, first.block.need))
        // Quick Forge 11 %: every time scales, nothing is stored per process.
        assertEquals("8d 20h 50m 22s", ForgeFormat.duration(run("mid", 0.11).time.bound))
    }

    @Test
    fun readinessStates() {
        val r = run("mid")
        val byKey = ForgeEngine.nodes(r.root).associateBy { it.key }
        // The reported case: need 1,920 Enchanted Diamond, own 1,588, Diamond fully owned -> ready to craft 332, nothing red.
        val ed = byKey.getValue("TITANIUM_DRILL_4/0/1/0/0")
        assertEquals(listOf(1920L, 1588L, 332L), listOf(ed.need, ed.use, ed.missing))
        assertEquals(ed.children.single().need, ed.children.single().use)
        val s = ForgeEngine.status(ed)
        assertEquals(StepState.READY, s.state)
        assertEquals(332, s.make)
        assertEquals(0, s.short)
        // Its parent is ready through that craft (owned 24 + 12 craftable from it).
        val block = byKey.getValue("TITANIUM_DRILL_4/0/1/0")
        assertEquals(StepState.READY, ForgeEngine.status(block).state)
        assertEquals(12, block.make)
        // Partly ready: 269 of 320 craftable, 51 short; its parent 1 of 2.
        val partial = ForgeEngine.status(byKey.getValue("TITANIUM_DRILL_4/0/4/2/0/0/0"))
        assertEquals(Triple(StepState.PARTIAL, 269L, 51L), Triple(partial.state, partial.make, partial.short))
        assertEquals(StepState.PARTIAL, ForgeEngine.status(byKey.getValue("TITANIUM_DRILL_4/0/4/2/0/0")).state)
        // Missing: no Diamond allocated to this place any more (the pool went to earlier places).
        assertEquals(StepState.MISSING, ForgeEngine.status(byKey.getValue("TITANIUM_DRILL_4/3/0/0")).state)
        // Base materials are only covered or missing.
        assertTrue(ForgeEngine.nodes(r.root).filter { it.kind == StepKind.BASE }
            .all { ForgeEngine.status(it).state.let { st -> st == StepState.COVERED || st == StepState.MISSING } })
        // Forge steps can be ready through crafts too; forge outputs never make a parent ready.
        val titanium = r.root.children.first { it.id == "REFINED_TITANIUM" }
        assertEquals(9, titanium.make)
        assertEquals(0, r.root.make)
        // Aggregates: one owned pool feeding several parents is never counted twice.
        val steps = r.agg.associateBy { it.id }
        val agg = ForgeEngine.status(steps.getValue("ENCHANTED_DIAMOND"))
        assertEquals(Triple(StepState.PARTIAL, 601L, 4851L), Triple(agg.state, agg.make, agg.short))
        assertEquals(StepState.READY, ForgeEngine.status(steps.getValue("FLAWED_SAPPHIRE_GEM")).state)
        assertEquals(StepState.COVERED, ForgeEngine.status(steps.getValue("ENCHANTED_GOLD")).state)
        val mithril = ForgeEngine.status(steps.getValue("REFINED_MITHRIL"))
        assertEquals(Triple(StepState.PARTIAL, 34L, 29L), Triple(mithril.state, mithril.make, mithril.short))
    }

    @Test
    fun readyToForge() {
        val r = run("ready")
        assertEquals(1, r.time.processes)
        assertEquals(listOf(goal), r.plan.slots)
        assertEquals(1, ForgeEngine.readyTimes(r.root))
        assertEquals(StepState.READY, ForgeEngine.status(r.root).state)
    }

    @Test
    fun lockedRecipesStartNothing() {
        val locked = ForgeRecipes(index, mapOf("REFINED_TITANIUM" to "Requires Tungsten Collection III"))
        val root = ForgeEngine.expand(goal, locked, have("mid"))
        val titanium = root.children.first { it.id == "REFINED_TITANIUM" }
        assertEquals(0, ForgeEngine.readyTimes(titanium))
        assertEquals(0, titanium.make)
        val plan = ForgeEngine.plan(root, ForgeEngine.aggregate(root), 7, 0.3)
        assertTrue(plan.waiting.first { it.step.id == "REFINED_TITANIUM" }.locked)
    }

    @Test
    fun craftingAlternativesFollowTheMock() {
        // First crafting recipe in served order; ENCHANTED_DIAMOND's 9-from-a-block version comes second.
        val ed = book.recipeOf("ENCHANTED_DIAMOND")!!
        assertEquals(listOf("DIAMOND" to 160L), ed.inputs)
        assertEquals(1, index.alternatives["ENCHANTED_DIAMOND"])
        // A first recipe that yields several items is no recipe: DIAMOND stays a base material.
        assertNull(book.recipeOf("DIAMOND"))
        val drill = book.recipeOf(goal)!!
        assertEquals(StepKind.FORGE, drill.kind)
        assertEquals(30, drill.baseSeconds)
        // Coins are the last input.
        assertEquals(ForgeEngine.COINS to 50_000_000L, book.recipeOf("DIVAN_DRILL")!!.inputs.last())
    }

    @Test
    fun formatsMatchTheMock() {
        assertEquals(listOf("3.43M", "76.8k", "238k", "5,120", "9,999", "10k", "1.23B"),
            listOf(3_430_400L, 76_800, 238_080, 5120, 9999, 10_000, 1_234_567_890).map(ForgeFormat::amount))
        assertEquals("21s", ForgeFormat.duration(ForgeEngine.shownSeconds(30, 0.3)))
        assertEquals("3h 9m", ForgeFormat.duration(ForgeEngine.shownSeconds(16200, 0.3)))
        assertEquals("5h 36m", ForgeFormat.duration(ForgeEngine.shownSeconds(8 * 3600, 0.3)))
        assertEquals("1d 9h", ForgeFormat.duration(121023, 2))
        assertEquals("0s", ForgeFormat.duration(0))
    }

    @Test
    fun idOrderIsTheMocksLocaleCompare() {
        val expected = fixture["localeOrder"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected, expected.shuffled(java.util.Random(7)).sortedWith(IdOrder))
    }
}
