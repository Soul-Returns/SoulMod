package com.soulreturns.features.mining.nucleus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays capture `2026-11-08_04-25-39` (Hypixel, 26.2 client, 04:25-05:03): the end of one run and one
 * whole run (Jungle Key, Goblin Egg, Apparatus, stocked Divan tools, Bal), chat plus every change of the
 * tab `Crystals:` widget. Other players' chat is left out of the resource.
 */
class NucleusRunReplayTest {
    private sealed interface Entry {
        val t: Long
    }

    private data class Chat(override val t: Long, val raw: String) : Entry

    private data class Tab(override val t: Long, val lines: List<String>) : Entry

    private val entries: List<Entry> by lazy {
        val text = javaClass.getResource("/nucleus/2026-11-08_04-25-39.ndjson")!!.readText()
        text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            val t = o.getValue("t").jsonPrimitive.long
            val tab = o["tab"]
            if (tab != null) Tab(t, tab.jsonArray.map { it.jsonPrimitive.content }) else Chat(t, o.getValue("chat").jsonPrimitive.content)
        }.toList()
    }

    private fun events(): List<Pair<Long, NucleusEvent>> {
        val parser = NucleusChatParser()
        return entries.filterIsInstance<Chat>().mapNotNull { c -> parser.feed(c.raw)?.let { c.t to it } }
    }

    @Test
    fun parserReadsTheRun() {
        val run = events().map { it.second }.filter { it != NucleusEvent.BalActive }
        val bundles = run.filterIsInstance<NucleusEvent.LootBundle>()
        assertEquals(
            listOf<NucleusEvent>(
                NucleusEvent.CrystalPlaced("Jade"),
                NucleusEvent.CrystalPlaced("Sapphire"),
                NucleusEvent.KitUsed("Goblin Egg"),
                NucleusEvent.CrystalFound("Amber", 3, 5),
                NucleusEvent.CrystalPlaced("Amber"),
                NucleusEvent.KitUsed("Jungle Key"),
                NucleusEvent.CrystalFound("Amethyst", 4, 5),
                NucleusEvent.CrystalPlaced("Amethyst"),
                NucleusEvent.CrystalFound("Topaz", 5, 5),
                NucleusEvent.CrystalPlaced("Topaz"),
                bundles[0],
                // Three keeper lines and `You found all of the items!`: no kit event (tools cost nothing).
                NucleusEvent.CrystalFound("Jade", 1, 5),
                NucleusEvent.KitUsed("Jungle Key"),
                // The temple's `LOOT CHEST COLLECTED` block in between is not a bundle.
                NucleusEvent.CrystalFound("Amethyst", 2, 5),
                NucleusEvent.KitUsed("Goblin Egg"),
                NucleusEvent.CrystalFound("Amber", 3, 5),
                NucleusEvent.KitUsed("Precursor Apparatus"),
                NucleusEvent.CrystalFound("Sapphire", 4, 5),
                NucleusEvent.CrystalFound("Topaz", 5, 5),
                NucleusEvent.CrystalPlaced("Sapphire"),
                NucleusEvent.CrystalPlaced("Jade"),
                NucleusEvent.CrystalPlaced("Topaz"),
                NucleusEvent.CrystalPlaced("Amethyst"),
                NucleusEvent.CrystalPlaced("Amber"),
                bundles[1],
            ),
            run,
        )
        assertEquals(16, bundles[0].items.size)
        assertTrue(NucleusLoot("Jaderald", 1) in bundles[0].items)
        assertTrue(NucleusLoot("Mithril Powder", 4005) in bundles[0].items)
        assertEquals(17, bundles[1].items.size)
        for (item in listOf(
            NucleusLoot("Divan Fragment", 1),
            NucleusLoot("Pickonimbus 2000", 1),
            NucleusLoot("Bob-omb", 20),
            NucleusLoot("Fine Amber Gemstone", 4),
            NucleusLoot("Mithril Powder", 4450),
        )) {
            assertTrue(item in bundles[1].items, "$item in ${bundles[1].items}")
        }
    }

    /** Both Topaz runs had no retreat line: Bal's down state comes from the Topaz found. */
    @Test
    fun balNeverSaidTheRetreatLine() {
        val all = events().map { it.second }
        assertEquals(0, all.count { it == NucleusEvent.BalDown })
        assertEquals(21, all.count { it == NucleusEvent.BalActive })
    }

    /**
     * The tab restore over the captured timeline (one read per second, as in [NucleusRuns]): a crystal's
     * state never goes backwards within a run, and the widget's finished run (still `✔ Placed` 4-10 s
     * after the bundle) never leaks into the next one.
     */
    @Test
    fun tabRestoreFollowsTheCapturedWidget() {
        val events = events()
        val tabs = entries.filterIsInstance<Tab>()
        val restore = NucleusTabRestore()
        // What chat had set before the capture started: Jade and Sapphire found.
        val state = linkedMapOf("Jade" to CrystalState.FOUND, "Sapphire" to CrystalState.FOUND)
        var lastEventMs = 0L
        var tab: List<String>? = null
        var afterBundle = false
        var e = 0
        var tb = 0
        val start = entries.first().t
        val end = entries.last().t
        var now = start
        while (now <= end) {
            while (e < events.size && events[e].first <= now) {
                val (t, event) = events[e++]
                lastEventMs = t
                if (event is NucleusEvent.CrystalFound) {
                    state[event.crystal] = CrystalState.FOUND
                    afterBundle = false
                } else if (event is NucleusEvent.CrystalPlaced) {
                    state[event.crystal] = CrystalState.PLACED
                } else if (event is NucleusEvent.LootBundle) {
                    state.clear()
                    restore.onBundle(t)
                    afterBundle = true
                }
            }
            while (tb < tabs.size && tabs[tb].t <= now) tab = tabs[tb++].lines
            val before = HashMap(state)
            val changes = restore.changes(tab?.let(NucleusTabCrystals::parse), state, now, lastEventMs)
            for ((name, s) in changes) state[name] = s
            for ((name, s) in changes) {
                val was = before[name] ?: CrystalState.MISSING
                if (s.ordinal < was.ordinal) fail("$name went back from $was to $s at ${now - start} ms")
            }
            if (afterBundle && state.values.any { it != CrystalState.MISSING }) fail("finished run leaked into the next at ${now - start} ms: $state")
            now += 1_000L
        }
        assertTrue(state.values.all { it == CrystalState.MISSING }, "after the last bundle: $state")
    }
}
